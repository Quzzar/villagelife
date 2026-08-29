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
 * Provisioned the same way as the model itself: the GGUF weights are a couple
 * of gigabytes downloaded on first run, and a llama.cpp build is 11 to 18 MB
 * beside them. Fetching it is cheaper than the model it will load, and
 * llama.cpp is MIT licensed, so shipping or fetching it is allowed.
 *
 * Deliberately a SUBPROCESS rather than a JNI binding. A binding would mean
 * native libraries for four platforms inside the mod jar, loaded through a
 * modded classloader, with all the split-package and multi-release-jar hazards
 * that native inference-in-JVM runs into under FML. A process boundary cannot
 * have a classloader conflict, so this avoids the whole class of them.
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

  /**
   * Benchmarked on the four jobs the mod actually gives a model, using the real
   * system prompts, few-shot turns and temperatures from each call site. Ten
   * runs per task, scored on whether the CONTENT was usable rather than on
   * whether it parsed: every model parses nearly everything, so parse rate
   * cannot separate them.
   *
   *                  decide  chat  relation | persona
   *   Qwen2.5-3B     10/10  9/10    10/10   |  6/10
   *   Gemma-2-2B     10/10 10/10    10/10   |  6/10
   *   Llama-3.2-3B    8/10 10/10    10/10   |  1/10
   *   Qwen2.5-1.5B    8/10  9/10    10/10   |  3/10
   *
   * Median latency, measured alone on an M-series Mac with nothing else running:
   *
   *                  decide  chat  persona  relation   size
   *   Qwen2.5-1.5B    454ms  299ms   597ms    561ms    1.1G
   *   Gemma-2-2B      642ms  742ms  1215ms   1442ms    1.7G
   *   Qwen2.5-3B      743ms  611ms  1142ms   1022ms    2.1G
   *   Llama-3.2-3B    766ms  580ms  1261ms   1020ms    2.0G
   *
   * The table above scores whether a single reply is USABLE, and by that
   * measure the models are close. It misses the thing a player feels most,
   * because it looks at one turn at a time: whether a villager can hold a
   * CONVERSATION. A follow-up ran a twelve-turn chat per model with history
   * accumulating as the mod builds it, and scored repetition, echoing the
   * player back, and how many distinct openings the model managed:
   *
   *                  distinct openings / 12   reused own opening   echoed player
   *   Llama-3.2-3B          12                     0                   0
   *   Gemma-2-2B            10                     2                   0
   *   Qwen2.5-3B             2                     0                   4
   *   Qwen2.5-1.5B           8                     4                   0
   *
   * Qwen2.5-3B opened "Ah, Quzzar!" on all twelve turns - the "talks in
   * circles" failure a player hits within a minute. That is why the Qwen
   * options were removed and Llama-3.2-3B is the default: it was the clear best
   * at conversation, at 580ms a reply, and a near-tie on the build decision it
   * is a hair behind on. Gemma is the one kept alternative.
   *
   * The persona column is NOT trustworthy and is kept only to show that the
   * task is the weak one for every model. It asks whether the blurb used each
   * listed trait, scored by keyword, and cannot recognise "a mountain of a man"
   * as "a true giant"; hand reading shows real defects underneath but the
   * number overstates them. Scoring persona properly needs a judge model.
   *
   * Three call sites are still unmeasured: relationship SELECTION, reflection,
   * and village naming.
   */
  public static final Model GEMMA_2B = new Model(
      "bartowski/gemma-2-2b-it-GGUF", "gemma-2-2b-it-Q4_K_M.gguf", "Gemma-2-2B-it");
  public static final Model LLAMA_3B = new Model(
      "bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_K_M.gguf", "Llama-3.2-3B-Instruct");

  /**
   * What the config's model name may say, and what it gets. Llama-3.2-3B is the
   * default and the only unqualified answer: it was the clear best at holding a
   * conversation when the four were run head to head, and a villager that talks
   * in circles is the thing players notice first. Gemma stays as the one
   * alternative. The Qwen options were removed - both looped their own opening
   * line back at the player turn after turn (Aaron, in play).
   */
  public static Model byName(String name) {
    String key = name == null ? "" : name.toLowerCase(Locale.ROOT).replace(" ", "");
    if (key.contains("gemma")) {
      return GEMMA_2B;
    }
    return LLAMA_3B;
  }

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
