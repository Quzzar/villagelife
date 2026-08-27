package com.quzzar.villagelife.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.quzzar.villagelife.Villagelife;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Provisions and launches llama.cpp's server, so the fast path needs nothing
 * installed by hand.
 *
 * This is the same bargain the mod already makes for models: Jlama downloads
 * hundreds of megabytes of weights on first run, and a llama.cpp build is 11 to
 * 18 MB beside them. Fetching it is cheaper than the model it will load, and
 * llama.cpp is MIT licensed, so shipping or fetching it is allowed.
 *
 * Deliberately a SUBPROCESS rather than a JNI binding. A binding would mean
 * native libraries for four platforms inside the mod jar, loaded through a
 * modded classloader — and build.gradle already carries a jlamaBundle task
 * flattening two jars because they share a split package. That is the failure
 * this avoids: a process boundary cannot have a classloader conflict.
 */
public final class LlamaServerLauncher {

  /**
   * Pinned. llama.cpp publishes many builds a day and renames assets between
   * them, so tracking "latest" would break without anyone touching this repo.
   */
  private static final String BUILD = "b10653";
  private static final String RELEASE =
      "https://github.com/ggml-org/llama.cpp/releases/download/" + BUILD + "/";

  /** Where a model lives on HuggingFace, and what it costs to fetch. */
  public record Model(String repo, String file, String label) {
    String url() {
      return "https://huggingface.co/" + repo + "/resolve/main/" + file + "?download=true";
    }
  }

  public static final Model QWEN_3B = new Model(
      "Qwen/Qwen2.5-3B-Instruct-GGUF", "qwen2.5-3b-instruct-q4_k_m.gguf", "Qwen2.5-3B-Instruct");
  public static final Model QWEN_1_5B = new Model(
      "Qwen/Qwen2.5-1.5B-Instruct-GGUF", "qwen2.5-1.5b-instruct-q4_k_m.gguf", "Qwen2.5-1.5B-Instruct");

  private static final HttpClient HTTP = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(30))
      .build();

  private LlamaServerLauncher() {
  }

  /** The release asset for this machine, or null where llama.cpp ships none. */
  static String assetFor(String osName, String arch) {
    String os = osName.toLowerCase(Locale.ROOT);
    boolean arm = arch.contains("aarch64") || arch.contains("arm");
    if (os.contains("mac") || os.contains("darwin")) {
      return "llama-" + BUILD + "-bin-macos-" + (arm ? "arm64" : "x64") + ".tar.gz";
    }
    if (os.contains("win")) {
      return "llama-" + BUILD + "-bin-win-cpu-" + (arm ? "arm64" : "x64") + ".zip";
    }
    if (os.contains("linux")) {
      return "llama-" + BUILD + "-bin-ubuntu-" + (arm ? "arm64" : "x64") + ".tar.gz";
    }
    return null;
  }

  private static Path runtimeDir() {
    return FMLPaths.GAMEDIR.get().resolve("villagelife").resolve("runtime");
  }

  private static Path modelDir() {
    return FMLPaths.GAMEDIR.get().resolve("villagelife").resolve("models");
  }

  /**
   * The llama-server executable, fetched and unpacked if this is the first run.
   * Returns null when no build exists for this platform, which is a reason to
   * fall back rather than an error: the offline Java model still works.
   */
  public static Path ensureServer() throws IOException, InterruptedException {
    String asset = assetFor(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    if (asset == null) {
      Villagelife.LOGGER.warn("llama.cpp publishes no build for {} {}; staying on the offline model",
          System.getProperty("os.name"), System.getProperty("os.arch"));
      return null;
    }

    Path dir = runtimeDir().resolve(BUILD);
    Path existing = findServer(dir);
    if (existing != null) {
      return existing;
    }

    Files.createDirectories(dir);
    Path archive = dir.resolve(asset);
    Villagelife.LOGGER.info("Fetching llama.cpp {} for the local runtime ({})", BUILD, asset);
    download(RELEASE + asset, archive);
    unpack(archive, dir);
    Files.deleteIfExists(archive);

    Path server = findServer(dir);
    if (server == null) {
      throw new IOException("llama-server not found inside " + asset);
    }
    makeRunnable(server);
    Villagelife.LOGGER.info("Local runtime ready at {}", server);
    return server;
  }

  /** The model weights, fetched if absent. Far the larger of the two downloads. */
  public static Path ensureModel(Model model) throws IOException, InterruptedException {
    Path target = modelDir().resolve(model.file());
    if (Files.exists(target) && Files.size(target) > 0) {
      return target;
    }
    Files.createDirectories(modelDir());
    Villagelife.LOGGER.info("Downloading {} — this happens once and is a large file", model.label());
    download(model.url(), target);
    Villagelife.LOGGER.info("{} ready ({} MB)", model.label(), Files.size(target) / 1_000_000);
    return target;
  }

  public static ProcessBuilder buildCommand(Path server, Path model, int port, int contextSize) {
    List<String> command = new ArrayList<>();
    command.add(server.toAbsolutePath().toString());
    command.add("-m");
    command.add(model.toAbsolutePath().toString());
    command.add("--host");
    // Loopback only. This server answers without authentication, so it must not
    // be reachable from anywhere but this machine.
    command.add("127.0.0.1");
    command.add("--port");
    command.add(Integer.toString(port));
    command.add("-c");
    command.add(Integer.toString(contextSize));
    // Villager prompts are short and many; a small batch keeps latency down.
    command.add("-np");
    command.add("2");

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(server.getParent().toFile());
    return builder;
  }

  private static void download(String url, Path target) throws IOException, InterruptedException {
    Path partial = target.resolveSibling(target.getFileName() + ".partial");
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(30))
        .GET()
        .build();
    HttpResponse<InputStream> response =
        HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      throw new IOException("download failed (" + response.statusCode() + "): " + url);
    }
    try (InputStream in = response.body()) {
      Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
    }
    // Only ever move a complete file into place, so an interrupted download
    // cannot leave something that looks cached and is truncated.
    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void unpack(Path archive, Path into) throws IOException, InterruptedException {
    String name = archive.getFileName().toString();
    List<String> command = name.endsWith(".zip")
        ? List.of("unzip", "-o", "-q", archive.toString(), "-d", into.toString())
        : List.of("tar", "-xzf", archive.toString(), "-C", into.toString());
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    if (!process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES) || process.exitValue() != 0) {
      throw new IOException("could not unpack " + name);
    }
  }

  private static Path findServer(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return null;
    }
    try (var walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> {
            String n = p.getFileName().toString();
            return n.equals("llama-server") || n.equals("llama-server.exe");
          })
          .findFirst().orElse(null);
    }
  }

  /**
   * Unix needs the execute bit, and macOS quarantines anything downloaded:
   * Gatekeeper refuses to run it until the attribute is cleared, which is the
   * single most likely reason a first launch fails.
   */
  private static void makeRunnable(Path server) {
    server.toFile().setExecutable(true, false);
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
      return;
    }
    try {
      new ProcessBuilder("xattr", "-dr", "com.apple.quarantine",
          server.getParent().toAbsolutePath().toString())
          .redirectErrorStream(true).start().waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      Villagelife.LOGGER.warn("Could not clear the quarantine flag; macOS may refuse to start the "
          + "local runtime. Offline model still available.", e);
    }
  }
}
