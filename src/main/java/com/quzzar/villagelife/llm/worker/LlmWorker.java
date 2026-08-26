package com.quzzar.villagelife.llm.worker;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.model.functions.Generator;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.prompt.PromptContext;
import com.github.tjake.jlama.util.Downloader;

/**
 * Entry point of the LLM worker process. The mod spawns this in a child JVM
 * (with the Vector API / preview flags Jlama needs, so players never have to
 * configure them) and talks to it over stdio with one JSON object per line.
 *
 * MUST NOT reference any Minecraft, NeoForge, or mod class: it runs on a plain
 * classpath of the mod jar plus Jlama's libraries.
 *
 * In:  {"id": 1, "op": "generate", "system": "...", "user": "...", "examples": [...], "max_tokens": 96, "temperature": 0.4}
 *      {"op": "shutdown"}
 * Out: {"event": "status", "state": "DOWNLOADING|LOADING|READY|FAILED", "detail": "..."}
 *      {"id": 1, "ok": true, "raw": "<model response text>", "tokens": 28, "ms": 556}
 *      {"id": 1, "ok": false, "error": "..."}
 *
 * Args: <modelName> <modelDir> <maxNewTokens> <temperature>
 */
public final class LlmWorker {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final PrintStream out;
  private final AbstractModel model;
  private final int maxNewTokens;
  private final float temperature;

  private LlmWorker(PrintStream out, AbstractModel model, int maxNewTokens, float temperature) {
    this.out = out;
    this.model = model;
    this.maxNewTokens = maxNewTokens;
    this.temperature = temperature;
  }

  public static void main(String[] args) throws Exception {
    // The protocol owns stdout; make sure nothing else (JVM warnings go to
    // stderr already) can corrupt it.
    PrintStream protocolOut = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    System.setOut(System.err);

    try {
      String modelName = args[0];
      Path modelDir = Path.of(args[1]);
      int maxNewTokens = Integer.parseInt(args[2]);
      float temperature = Float.parseFloat(args[3]);

      Files.createDirectories(modelDir);
      sendStatus(protocolOut, "DOWNLOADING", "checking model cache");
      File modelPath = new Downloader(modelDir.toString(), modelName)
          .withProgressReporter((filename, sofar, total) -> {
            if (total > 0) {
              sendStatus(protocolOut, "DOWNLOADING", filename + " " + (sofar * 100 / total) + "%");
            }
          })
          .huggingFaceModel();

      sendStatus(protocolOut, "LOADING", "loading weights");
      AbstractModel model = ModelSupport.loadModel(modelPath, DType.F32, DType.I8);

      // Catches broken compute backends at load time and pre-warms caches.
      sendStatus(protocolOut, "LOADING", "warming up");
      model.generateBuilder()
          .session(UUID.randomUUID())
          .promptContext(PromptContext.of("Hi"))
          .temperature(0.0f)
          .ntokens(8)
          .generate();

      sendStatus(protocolOut, "READY", modelName);
      new LlmWorker(protocolOut, model, maxNewTokens, temperature).serve();
    } catch (Throwable t) {
      t.printStackTrace();
      sendStatus(protocolOut, "FAILED", t.getClass().getSimpleName() + ": " + t.getMessage());
      System.exit(1);
    }
    System.exit(0);
  }

  private void serve() throws Exception {
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode request;
      try {
        request = JSON.readTree(line);
      } catch (Exception e) {
        continue; // not ours; ignore
      }
      if ("shutdown".equals(request.path("op").asText())) {
        return;
      }
      if ("generate".equals(request.path("op").asText())) {
        handleGenerate(request);
      }
    }
    // EOF: parent is gone, exit with it.
  }

  /**
   * Free-text generation: caller-supplied system+user prompt, raw text back.
   * Optional "examples" — [{"user": ..., "assistant": ...}, ...] — become real
   * chat turns before the user message: small models follow example turns far
   * more reliably than instructions or examples embedded in the system text.
   */
  private void handleGenerate(JsonNode request) {
    long id = request.path("id").asLong();
    ObjectNode response = JSON.createObjectNode();
    response.put("id", id);
    try {
      String system = request.path("system").asText();
      String userMessage = request.path("user").asText();
      int maxTokens = Math.min(Math.max(request.path("max_tokens").asInt(120), 16), 1024);

      var promptBuilder = model.promptSupport().orElseThrow()
          .builder()
          .addSystemMessage(system);
      for (JsonNode example : request.path("examples")) {
        promptBuilder.addUserMessage(example.path("user").asText());
        promptBuilder.addAssistantMessage(example.path("assistant").asText());
      }
      PromptContext ctx = promptBuilder
          .addUserMessage(userMessage)
          .build();

      // Jlama's ntokens is the total token budget (prompt + generation)
      int promptTokens = model.getTokenizer().encode(ctx.getPrompt()).length;

      long start = System.currentTimeMillis();
      Generator.Response result = model.generateBuilder()
          .session(UUID.randomUUID())
          .promptContext(ctx)
          .temperature((float) request.path("temperature").asDouble(temperature))
          .ntokens(promptTokens + maxTokens)
          .generate();

      response.put("ok", true);
      response.put("raw", result.responseText);
      response.put("tokens", result.generatedTokens);
      response.put("ms", System.currentTimeMillis() - start);
    } catch (Throwable t) {
      t.printStackTrace();
      response.put("ok", false);
      response.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
    }
    send(out, response);
  }

  private static void sendStatus(PrintStream out, String state, String detail) {
    ObjectNode event = JSON.createObjectNode();
    event.put("event", "status");
    event.put("state", state);
    event.put("detail", detail);
    send(out, event);
  }

  private static synchronized void send(PrintStream out, ObjectNode message) {
    out.println(message.toString());
  }

}
