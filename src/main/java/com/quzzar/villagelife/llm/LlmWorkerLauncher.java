package com.quzzar.villagelife.llm;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.quzzar.villagelife.Villagelife;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Assembles the command line for the LLM worker process.
 *
 * The worker is a plain JVM: same Java runtime the game is on, with the flags
 * Jlama needs baked in (players never configure them). Its classpath:
 *
 * - production: the mod jar itself (Jlama is shaded into it, and the worker
 *   main class is a mod class) plus Jlama's library dependencies, which ship
 *   nested under META-INF/jarjar/ and are extracted to disk once — a child
 *   process cannot read jar-in-jar entries.
 * - dev: the parent's own java.class.path, which already carries the classes
 *   dirs, the jlama-bundle jar, and the libraries. The worker only ever loads
 *   its own slice of it.
 */
public final class LlmWorkerLauncher {

  private static final String WORKER_MAIN = "com.quzzar.villagelife.llm.worker.LlmWorker";

  private LlmWorkerLauncher() {
  }

  public static ProcessBuilder buildCommand(String modelName, Path modelDir, int maxNewTokens,
      double temperature, int heapMb) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-Xmx" + heapMb + "m");
    // Die cleanly if a memory-capped host squeezes us instead of limping.
    command.add("-XX:+ExitOnOutOfMemoryError");
    // The whole reason this worker exists: Jlama's requirements, baked in.
    command.add("--add-modules=jdk.incubator.vector");
    command.add("--enable-preview");
    command.add("-cp");
    command.add(buildClasspath());
    command.add(WORKER_MAIN);
    command.add(modelName);
    command.add(modelDir.toAbsolutePath().toString());
    command.add(Integer.toString(maxNewTokens));
    command.add(Double.toString(temperature));

    return new ProcessBuilder(command);
  }

  private static String javaExecutable() {
    return ProcessHandle.current().info().command().orElseGet(() -> {
      String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
      return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    });
  }

  private static String buildClasspath() throws Exception {
    Path modFile = ModList.get().getModFileById(Villagelife.MODID).getFile().getFilePath();

    if (!Files.isRegularFile(modFile) || !modFile.toString().endsWith(".jar")) {
      // Dev environment: the worker class comes from the classes dirs and the
      // libraries from java.class.path (via localRuntime). The bundle jar must
      // go FIRST so its shaded Jlama (with natives) shadows the plain
      // jlama-core jar that is also on the path.
      String parentClasspath = System.getProperty("java.class.path");
      Path bundleJar = null;
      for (String entry : parentClasspath.split(File.pathSeparator)) {
        String fileName = Path.of(entry).getFileName().toString();
        if (fileName.startsWith("jlama-bundle") && fileName.endsWith(".jar")) {
          bundleJar = Path.of(entry);
          break;
        }
      }
      if (bundleJar == null) {
        throw new IllegalStateException(
            "dev classpath has no jlama-bundle jar; run the jlamaBundleJar Gradle task and restart");
      }
      return bundleJar.toAbsolutePath() + File.pathSeparator + parentClasspath;
    }

    List<Path> classpath = new ArrayList<>();
    classpath.add(modFile);
    classpath.addAll(extractNestedLibraries(modFile));
    StringBuilder joined = new StringBuilder();
    for (Path entry : classpath) {
      if (joined.length() > 0) {
        joined.append(File.pathSeparatorChar);
      }
      joined.append(entry.toAbsolutePath());
    }
    return joined.toString();
  }

  /** Extracts META-INF/jarjar/*.jar from the mod jar to real files, once. */
  private static List<Path> extractNestedLibraries(Path modJar) throws Exception {
    Path libDir = FMLPaths.GAMEDIR.get().resolve("villagelife").resolve("worker-libs");
    Files.createDirectories(libDir);

    List<Path> extracted = new ArrayList<>();
    try (ZipFile zip = new ZipFile(modJar.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        boolean isNestedLib = (name.startsWith("META-INF/jarjar/") || name.startsWith("META-INF/workerlibs/"))
            && name.endsWith(".jar");
        if (entry.isDirectory() || !isNestedLib) {
          continue;
        }
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        if (fileName.contains("..")) {
          continue;
        }
        Path target = libDir.resolve(fileName);
        if (!Files.exists(target) || Files.size(target) != entry.getSize()) {
          try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
          }
          Villagelife.LOGGER.debug("Extracted worker library {}", fileName);
        }
        extracted.add(target);
      }
    }
    return extracted;
  }

}
