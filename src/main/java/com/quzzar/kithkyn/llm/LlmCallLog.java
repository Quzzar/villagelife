package com.quzzar.kithkyn.llm;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.llm.LlmService.FewShotExample;
import com.quzzar.kithkyn.llm.provider.LlmProvider.CompletionRequest;

/**
 * The complete record of every model call: what was sent, what came back, and
 * how long it took, written to {@code logs/llm.log} beside the game's own logs.
 *
 * <p>The main log keeps its one-line summaries (a chat reply, a build choice);
 * this file holds the whole exchange, because a villager who invents a hut for
 * a travelling merchant can only be understood by reading exactly what the
 * model was told. Every request is written when it goes out and its reply when
 * it lands, tied together by a running number, so a call that times out still
 * leaves its input on record. Calls that never reach a provider (not ready,
 * deferred behind a conversation, queue full) are written as one line each,
 * since "the village never decided" and "the village was never asked" look the
 * same from the game.
 *
 * <p>The file is its own rolling appender, added to the running log4j
 * configuration at startup rather than through a config file a mod cannot
 * ship. It rolls by size and keeps a few generations; nothing else goes in it
 * and none of it repeats in the console. If the appender cannot be installed,
 * the same text goes to the main log at debug level instead, so a logging
 * failure never costs a call its record.
 */
public final class LlmCallLog {

  private static final String LOGGER_NAME = "kithkyn.llm";
  private static final String APPENDER_NAME = "KithkynLlmCalls";
  private static final String FILE = "logs/llm.log";
  private static final String FILE_PATTERN = "logs/llm-%i.log.gz";
  private static final String ROLL_AT = "20MB";
  private static final String GENERATIONS_KEPT = "5";

  private static final Logger LOG = LogManager.getLogger(LOGGER_NAME);
  private static final AtomicLong SEQUENCE = new AtomicLong();
  private static volatile boolean installed;

  private LlmCallLog() {
  }

  /**
   * Attaches the rolling file to the live log4j configuration. Safe to call
   * more than once; only the first call does anything.
   */
  public static synchronized void install() {
    if (installed) {
      return;
    }
    try {
      LoggerContext context = (LoggerContext) LogManager.getContext(false);
      Configuration config = context.getConfiguration();
      PatternLayout layout = PatternLayout.newBuilder()
          .withPattern("[%d{HH:mm:ss.SSS}] %m%n")
          .withConfiguration(config)
          .build();
      RollingFileAppender appender = RollingFileAppender.newBuilder()
          .setName(APPENDER_NAME)
          .withFileName(FILE)
          .withFilePattern(FILE_PATTERN)
          .withPolicy(SizeBasedTriggeringPolicy.createPolicy(ROLL_AT))
          .withStrategy(DefaultRolloverStrategy.newBuilder().withMax(GENERATIONS_KEPT).withConfig(config).build())
          .setLayout(layout)
          .setConfiguration(config)
          .build();
      appender.start();
      config.addAppender(appender);
      LoggerConfig loggerConfig = LoggerConfig.newBuilder()
          .withLoggerName(LOGGER_NAME)
          .withLevel(Level.INFO)
          .withAdditivity(false)
          .withRefs(new AppenderRef[] { AppenderRef.createAppenderRef(APPENDER_NAME, null, null) })
          .withConfig(config)
          .build();
      loggerConfig.addAppender(appender, Level.INFO, null);
      config.addLogger(LOGGER_NAME, loggerConfig);
      context.updateLoggers();
      installed = true;
      Kithkyn.LOGGER.info("LLM calls are recorded in full in {}", FILE);
    } catch (Throwable failure) {
      // Throwable, not Exception: a log4j-core class missing from this
      // classpath surfaces as an Error, and a logging problem must never take
      // the server with it.
      Kithkyn.LOGGER.warn("Could not open {}; LLM calls will be recorded at debug level in the main log instead",
          FILE, failure);
    }
  }

  /**
   * Writes the outgoing request in full. The returned number identifies the
   * call; pass it to {@link #reply} so the answer can be matched to its prompt.
   */
  public static long request(String lane, String purpose, CompletionRequest request) {
    long id = SEQUENCE.incrementAndGet();
    StringBuilder text = new StringBuilder(256 + request.system().length() + request.user().length());
    text.append("==== #").append(id).append(" REQUEST ").append(lane).append(" | ").append(purpose)
        .append(" | temperature ").append(request.temperature())
        .append(", up to ").append(request.maxNewTokens()).append(" tokens");
    if (request.frequencyPenalty() != 0.0D) {
      text.append(", frequency penalty ").append(request.frequencyPenalty());
    }
    text.append('\n');
    section(text, "system", request.system());
    for (FewShotExample example : request.examples()) {
      section(text, "example user", example.user());
      section(text, "example assistant", example.assistant());
    }
    section(text, "user", request.user());
    write(text.toString());
    return id;
  }

  /** Writes what came back for the call numbered {@code id}, or why nothing did. */
  public static void reply(long id, String lane, String purpose, Optional<String> reply, Throwable error,
      long elapsedMs) {
    StringBuilder text = new StringBuilder();
    text.append("==== #").append(id).append(" REPLY ").append(lane).append(" | ").append(purpose)
        .append(" | ").append(elapsedMs).append(" ms\n");
    if (error != null) {
      text.append("(failed: ").append(error).append(')');
    } else if (reply == null || reply.isEmpty()) {
      text.append("(no reply: the provider timed out, failed, or returned nothing)");
    } else {
      text.append(reply.get());
    }
    text.append('\n');
    write(text.toString());
  }

  /** Writes one line for a call that never reached a provider, and why. */
  public static void skipped(String lane, String purpose, String reason) {
    write("==== #" + SEQUENCE.incrementAndGet() + " SKIPPED " + lane + " | " + purpose + " | " + reason + "\n");
  }

  private static void section(StringBuilder text, String title, String body) {
    text.append("--- ").append(title).append('\n').append(body).append('\n');
  }

  private static void write(String block) {
    if (installed) {
      LOG.info(block);
    } else {
      Kithkyn.LOGGER.debug("[llm]\n{}", block);
    }
  }

}
