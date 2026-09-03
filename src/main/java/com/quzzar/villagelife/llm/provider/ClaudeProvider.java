package com.quzzar.villagelife.llm.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.llm.LlmService;

/**
 * Cloud provider for Anthropic's Messages API. Notable differences from the
 * OpenAI dialect (from the #32 research brief): auth is {@code x-api-key}
 * (not Bearer), {@code system} is a top-level field, {@code max_tokens} is
 * required, temperature is deliberately never sent (the newest models reject
 * it while older ones accept it; omitting is the only uniform choice), and
 * reply text is the concatenation of the {@code content} blocks with
 * {@code type == "text"}.
 *
 * The API key is a secret: never logged, at most the last four characters.
 */
public final class ClaudeProvider implements LlmProvider {

  private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
  private static final String API_VERSION = "2023-06-01";
  private static final String DEFAULT_MODEL = "claude-haiku-4-5";
  private static final int MAX_RETRIES = 2;

  private final Supplier<String> apiKey;
  private final Supplier<String> configuredModel;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  private final AtomicReference<LlmService.Status> status = new AtomicReference<>(LlmService.Status.NOT_LOADED);
  private volatile String statusDetail = "";

  public ClaudeProvider(Supplier<String> apiKey, Supplier<String> configuredModel) {
    this.apiKey = apiKey;
    this.configuredModel = configuredModel;
  }

  private String model() {
    String configured = configuredModel.get();
    return configured == null || configured.isBlank() ? DEFAULT_MODEL : configured;
  }

  @Override
  public void start() {
    if (apiKey.get() == null || apiKey.get().isBlank()) {
      statusDetail = "no API key configured for Claude. Paste your key into villagelife-common.toml "
          + "(treat that file like a password).";
      status.set(LlmService.Status.FAILED);
      return;
    }
    statusDetail = "Claude, model " + model();
    status.set(LlmService.Status.READY);
    LlmService.get().onProviderReady();
  }

  @Override
  public void shutdown() {
    // Release the HTTP client's threads. An unclosed java.net.http.HttpClient keeps
    // non-daemon worker threads alive, and one of those is what hangs the server JVM
    // on stop (it saves and releases the world, then never exits). shutdownNow so the
    // stop does not block on any in-flight request.
    http.shutdownNow();
  }

  @Override
  public LlmService.Status getStatus() {
    return status.get();
  }

  @Override
  public String getStatusDetail() {
    return statusDetail;
  }

  @Override
  public CompletableFuture<Optional<String>> complete(CompletionRequest request) {
    JsonObject body = new JsonObject();
    body.addProperty("model", model());
    body.addProperty("system", request.system());
    body.addProperty("max_tokens", Math.max(256, request.maxNewTokens()));
    JsonArray messages = new JsonArray();
    for (LlmService.FewShotExample example : request.examples()) {
      messages.add(message("user", example.user()));
      messages.add(message("assistant", example.assistant()));
    }
    messages.add(message("user", request.user()));
    body.add("messages", messages);

    return attempt(body.toString(), 0);
  }

  private static JsonObject message(String role, String content) {
    JsonObject message = new JsonObject();
    message.addProperty("role", role);
    message.addProperty("content", content);
    return message;
  }

  private CompletableFuture<Optional<String>> attempt(String body, int tryNumber) {
    HttpRequest httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(ENDPOINT))
        .timeout(Duration.ofSeconds(45))
        .header("Content-Type", "application/json")
        .header("x-api-key", apiKey.get())
        .header("anthropic-version", API_VERSION)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
        .thenCompose(response -> handleResponse(response, body, tryNumber))
        .exceptionallyCompose(t -> {
          Villagelife.LOGGER.warn("Claude request failed: {}", t.getMessage());
          return retryOrEmpty(body, tryNumber);
        });
  }

  private CompletableFuture<Optional<String>> handleResponse(HttpResponse<String> response, String body,
      int tryNumber) {
    int code = response.statusCode();
    if (code == 200) {
      try {
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        StringBuilder text = new StringBuilder();
        for (JsonElement block : json.getAsJsonArray("content")) {
          JsonObject blockObject = block.getAsJsonObject();
          if ("text".equals(blockObject.get("type").getAsString())) {
            text.append(blockObject.get("text").getAsString());
          }
        }
        String stopReason = json.has("stop_reason") && !json.get("stop_reason").isJsonNull()
            ? json.get("stop_reason").getAsString()
            : "";
        if ("refusal".equals(stopReason) && text.isEmpty()) {
          Villagelife.LOGGER.warn("Claude refused the request");
          return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.completedFuture(
            text.isEmpty() ? Optional.empty() : Optional.of(text.toString()));
      } catch (Exception e) {
        Villagelife.LOGGER.warn("Claude: unparseable response body", e);
        return CompletableFuture.completedFuture(Optional.empty());
      }
    }

    if (code == 401) {
      statusDetail = "invalid API key for Claude (…" + maskedKey() + ")";
      status.set(LlmService.Status.FAILED);
      return CompletableFuture.completedFuture(Optional.empty());
    }
    if (code == 404) {
      statusDetail = "Claude model not found: " + model() + " (check the model id in the config)";
      status.set(LlmService.Status.FAILED);
      return CompletableFuture.completedFuture(Optional.empty());
    }
    if (code == 429 || code == 500 || code == 529) {
      Villagelife.LOGGER.warn("Claude: HTTP {}, retrying", code);
      return retryOrEmpty(body, tryNumber);
    }
    Villagelife.LOGGER.warn("Claude: HTTP {}", code);
    return CompletableFuture.completedFuture(Optional.empty());
  }

  private CompletableFuture<Optional<String>> retryOrEmpty(String body, int tryNumber) {
    if (tryNumber >= MAX_RETRIES) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    long backoffSeconds = tryNumber == 0 ? 2 : 5;
    return CompletableFuture.supplyAsync(() -> null,
        CompletableFuture.delayedExecutor(backoffSeconds, TimeUnit.SECONDS))
        .thenCompose(ignored -> attempt(body, tryNumber + 1));
  }

  private String maskedKey() {
    String key = apiKey.get();
    return key == null || key.length() < 4 ? "????" : key.substring(key.length() - 4);
  }

}
