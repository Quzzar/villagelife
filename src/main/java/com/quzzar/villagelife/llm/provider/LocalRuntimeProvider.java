package com.quzzar.villagelife.llm.provider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.llm.LlamaServerLauncher;
import com.quzzar.villagelife.llm.LlmService;

/**
 * The fast offline path: llama.cpp's server, fetched and run by the mod.
 *
 * Nobody installs anything. The binary is 11 to 18 MB, which is small beside the
 * weights the mod already downloads, and the server runs as a subprocess exactly
 * as the Jlama worker does — a process boundary rather than a JNI binding, so
 * there is no native library inside a modded classloader to go wrong.
 *
 * Measured against the Java model it replaces, on the same machine and the same
 * quantisation: generation goes from 20 to 34 tokens a second on a model with
 * three times the parameters, and prefill from 115 to 700. The gap is mostly
 * that llama.cpp uses the GPU and Jlama cannot.
 *
 * Failure is never fatal. Any step that does not work leaves this provider
 * FAILED, and the game already treats an unready provider as a reason to defer:
 * the planner falls back to the rules' choice and villagers keep working.
 */
public final class LocalRuntimeProvider implements LlmProvider {

  private static final int PORT = 8127;
  private static final int CONTEXT = 4096;
  private static final Duration STARTUP_BUDGET = Duration.ofMinutes(45);

  private final AtomicReference<LlmService.Status> status =
      new AtomicReference<>(LlmService.Status.NOT_LOADED);
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private volatile String statusDetail = "not started";
  private volatile Process process;
  private volatile OpenAiCompatibleProvider http_provider;

  @Override
  public void start() {
    if (status.get() == LlmService.Status.READY) {
      return;
    }
    Thread starter = new Thread(this::provisionAndSpawn, "villagelife-llama-launcher");
    starter.setDaemon(true);
    starter.start();
  }

  private void provisionAndSpawn() {
    try {
      status.set(LlmService.Status.DOWNLOADING);
      statusDetail = "fetching the local runtime";
      Path server = LlamaServerLauncher.ensureServer();
      if (server == null) {
        fail("no llama.cpp build for this platform");
        return;
      }

      LlamaServerLauncher.Model model = chosenModel();
      statusDetail = "downloading " + model.label();
      Path weights = LlamaServerLauncher.ensureModel(model);

      status.set(LlmService.Status.LOADING);
      statusDetail = "starting " + model.label();
      ProcessBuilder builder = LlamaServerLauncher.buildCommand(server, weights, PORT, CONTEXT);
      builder.redirectErrorStream(true);
      process = builder.start();
      drainOutput(process);

      if (!awaitHealthy()) {
        fail("the local runtime did not become healthy");
        return;
      }

      http_provider = new OpenAiCompatibleProvider(
          OpenAiCompatibleProvider.local("http://127.0.0.1:" + PORT, model.file()),
          () -> "", model::file);
      statusDetail = model.label() + " on llama.cpp";
      status.set(LlmService.Status.READY);
      Villagelife.LOGGER.info("Local runtime ready: {}", statusDetail);
      LlmService.get().onProviderReady();

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("interrupted while starting");
    } catch (Exception e) {
      Villagelife.LOGGER.error("Could not start the local runtime", e);
      fail(e.getMessage() == null ? e.toString() : e.getMessage());
    }
  }

  private static LlamaServerLauncher.Model chosenModel() {
    return LlamaServerLauncher.byName(VillagelifeConfig.LlmLocalModel);
  }

  /**
   * The first run downloads gigabytes, so this waits generously — but it waits
   * on the server answering, not on a timer, so an ordinary start is quick.
   */
  private boolean awaitHealthy() throws InterruptedException {
    long deadline = System.nanoTime() + STARTUP_BUDGET.toNanos();
    HttpRequest probe = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/health"))
        .timeout(Duration.ofSeconds(5)).GET().build();
    while (System.nanoTime() < deadline) {
      if (process != null && !process.isAlive()) {
        return false;
      }
      try {
        HttpResponse<String> response = http.send(probe, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          return true;
        }
      } catch (Exception ignored) {
        // not listening yet
      }
      Thread.sleep(1000);
    }
    return false;
  }

  /** Without draining, a full pipe buffer would freeze the server mid-answer. */
  private static void drainOutput(Process process) {
    Thread pump = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          Villagelife.LOGGER.debug("[llama-server] {}", line);
        }
      } catch (Exception ignored) {
        // the process died; awaitHealthy and complete() both handle that
      }
    }, "villagelife-llama-out");
    pump.setDaemon(true);
    pump.start();
  }

  private void fail(String why) {
    statusDetail = why + ". The offline model remains available.";
    status.set(LlmService.Status.FAILED);
    Villagelife.LOGGER.warn("Local runtime unavailable: {}", why);
  }

  @Override
  public void shutdown() {
    Process running = process;
    if (running == null) {
      return;
    }
    running.destroy();
    try {
      if (!running.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
        running.destroyForcibly();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      running.destroyForcibly();
    }
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
    OpenAiCompatibleProvider delegate = http_provider;
    if (delegate == null || status.get() != LlmService.Status.READY) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    return delegate.complete(request);
  }
}
