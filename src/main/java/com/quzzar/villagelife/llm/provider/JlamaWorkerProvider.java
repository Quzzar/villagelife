package com.quzzar.villagelife.llm.provider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.llm.LlmWorkerLauncher;

import net.neoforged.fml.loading.FMLPaths;

/**
 * The offline provider: a Jlama model in a worker child JVM (spawned with the
 * Vector API / preview flags Jlama needs, so players configure nothing),
 * spoken to over stdio with one JSON object per line. See
 * {@link com.quzzar.villagelife.llm.worker.LlmWorker} for the other end.
 */
public final class JlamaWorkerProvider implements LlmProvider {

  private static final long REQUEST_TIMEOUT_SECONDS = 60;

  /** The LLM is required, so a dead worker earns a few automatic restarts. */
  private static final int MAX_AUTO_RESTARTS = 3;

  private final AtomicReference<LlmService.Status> status = new AtomicReference<>(LlmService.Status.NOT_LOADED);
  private volatile String statusDetail = "";

  private final Object workerLock = new Object();
  private Process worker;
  private BufferedWriter workerStdin;
  private final AtomicLong nextRequestId = new AtomicLong(1);
  private final Map<Long, CompletableFuture<Optional<String>>> pending = new ConcurrentHashMap<>();
  private final AtomicInteger restartAttempts = new AtomicInteger();

  @Override
  public LlmService.Status getStatus() {
    return status.get();
  }

  @Override
  public String getStatusDetail() {
    return statusDetail;
  }

  @Override
  public void start() {
    if (!status.compareAndSet(LlmService.Status.NOT_LOADED, LlmService.Status.STARTING)
        && !status.compareAndSet(LlmService.Status.FAILED, LlmService.Status.STARTING)) {
      return; // already starting, loading, or ready
    }
    Thread starter = new Thread(this::spawnWorker, "villagelife-llm-launcher");
    starter.setDaemon(true);
    starter.start();
  }

  private void spawnWorker() {
    try {
      Path modelDir = FMLPaths.GAMEDIR.get().resolve("villagelife").resolve("models");
      Files.createDirectories(modelDir);

      ProcessBuilder builder = LlmWorkerLauncher.buildCommand(
          VillagelifeConfig.LlmModel,
          modelDir,
          VillagelifeConfig.LlmMaxNewTokens,
          VillagelifeConfig.LlmTemperature,
          VillagelifeConfig.LlmWorkerHeapMb);
      Villagelife.LOGGER.info("Starting LLM worker: {}", String.join(" ", builder.command()));

      Process process;
      synchronized (workerLock) {
        process = builder.start();
        this.worker = process;
        this.workerStdin = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
      }
      statusDetail = "worker starting";

      Thread stdout = new Thread(() -> readWorkerOutput(process), "villagelife-llm-worker-out");
      stdout.setDaemon(true);
      stdout.start();
      Thread stderr = new Thread(() -> readWorkerErrors(process), "villagelife-llm-worker-err");
      stderr.setDaemon(true);
      stderr.start();

      process.onExit().thenAccept(this::onWorkerExit);
    } catch (Throwable t) {
      statusDetail = "could not start the worker process (" + t.getMessage()
          + "). This host may not allow child processes; villagers will use built-in logic.";
      status.set(LlmService.Status.FAILED);
      Villagelife.LOGGER.error("Failed to start LLM worker", t);
    }
  }

  private void readWorkerOutput(Process process) {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        try {
          handleWorkerMessage(JsonParser.parseString(line).getAsJsonObject());
        } catch (Exception e) {
          Villagelife.LOGGER.debug("Unparseable LLM worker line: {}", line);
        }
      }
    } catch (Exception e) {
      Villagelife.LOGGER.debug("LLM worker stdout closed", e);
    }
  }

  private void readWorkerErrors(Process process) {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        Villagelife.LOGGER.debug("[llm-worker] {}", line);
      }
    } catch (Exception ignored) {
    }
  }

  private void handleWorkerMessage(JsonObject message) {
    if (message.has("event") && "status".equals(message.get("event").getAsString())) {
      String state = message.get("state").getAsString();
      statusDetail = message.has("detail") ? message.get("detail").getAsString() : "";
      switch (state) {
        case "DOWNLOADING" -> status.set(LlmService.Status.DOWNLOADING);
        case "LOADING" -> status.set(LlmService.Status.LOADING);
        case "READY" -> {
          restartAttempts.set(0);
          status.set(LlmService.Status.READY);
          Villagelife.LOGGER.info("LLM worker ready ({})", statusDetail);
          LlmService.get().onProviderReady();
        }
        case "FAILED" -> {
          status.set(LlmService.Status.FAILED);
          Villagelife.LOGGER.error("LLM worker failed: {}", statusDetail);
        }
        default -> {
        }
      }
      return;
    }

    if (message.has("id")) {
      CompletableFuture<Optional<String>> future = pending.remove(message.get("id").getAsLong());
      if (future == null) {
        return; // timed out earlier
      }
      boolean ok = message.has("ok") && message.get("ok").getAsBoolean();
      if (ok) {
        Villagelife.LOGGER.debug("LLM raw response ({} tokens, {} ms): {}",
            message.get("tokens").getAsInt(), message.get("ms").getAsLong(),
            message.get("raw").getAsString());
        future.complete(Optional.of(message.get("raw").getAsString()));
      } else {
        Villagelife.LOGGER.warn("LLM worker request error: {}",
            message.has("error") ? message.get("error").getAsString() : "unknown");
        future.complete(Optional.empty());
      }
    }
  }

  private void onWorkerExit(Process process) {
    synchronized (workerLock) {
      if (this.worker != process) {
        return; // a newer worker has replaced this one
      }
      this.worker = null;
      this.workerStdin = null;
    }
    if (status.get() != LlmService.Status.FAILED) {
      status.set(LlmService.Status.FAILED);
      int attempt = restartAttempts.incrementAndGet();
      if (attempt <= MAX_AUTO_RESTARTS) {
        long delaySeconds = attempt * 20L;
        statusDetail = "worker exited with code " + process.exitValue()
            + ", restarting in " + delaySeconds + "s (attempt " + attempt + "/" + MAX_AUTO_RESTARTS + ")";
        Villagelife.LOGGER.error("LLM worker exited unexpectedly: {}", statusDetail);
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(this::start);
      } else {
        // Backoff exhausted: stop respawning so a memory-capped host is not
        // thrashed. The LLM is required, so this leaves villages paused.
        statusDetail = "worker exited with code " + process.exitValue() + " repeatedly; giving up. "
            + "Out of memory on a small host? Try the 0.5B model or a smaller worker heap. /vlbrain load retries.";
        Villagelife.LOGGER.error("LLM worker exited unexpectedly: {}", statusDetail);
      }
    }
    pending.values().forEach(future -> future.complete(Optional.empty()));
    pending.clear();
  }

  @Override
  public void shutdown() {
    Process process;
    synchronized (workerLock) {
      process = this.worker;
      this.worker = null;
      this.workerStdin = null;
    }
    if (process != null) {
      process.destroy();
    }
  }

  @Override
  public CompletableFuture<Optional<String>> complete(CompletionRequest request) {
    long id = nextRequestId.getAndIncrement();
    JsonObject payload = new JsonObject();
    payload.addProperty("id", id);
    payload.addProperty("op", "generate");
    payload.addProperty("system", request.system());
    payload.addProperty("user", request.user());
    payload.addProperty("max_tokens", request.maxNewTokens());
    payload.addProperty("temperature", request.temperature());
    JsonArray examples = new JsonArray();
    for (LlmService.FewShotExample example : request.examples()) {
      JsonObject pair = new JsonObject();
      pair.addProperty("user", example.user());
      pair.addProperty("assistant", example.assistant());
      examples.add(pair);
    }
    payload.add("examples", examples);

    CompletableFuture<Optional<String>> future = new CompletableFuture<>();
    pending.put(id, future);

    try {
      synchronized (workerLock) {
        if (workerStdin == null) {
          pending.remove(id);
          return CompletableFuture.completedFuture(Optional.empty());
        }
        workerStdin.write(payload.toString());
        workerStdin.newLine();
        workerStdin.flush();
      }
    } catch (Exception e) {
      pending.remove(id);
      Villagelife.LOGGER.error("Failed to send request to LLM worker", e);
      return CompletableFuture.completedFuture(Optional.empty());
    }

    return future.completeOnTimeout(Optional.empty(), REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .whenComplete((result, error) -> pending.remove(id));
  }

}
