package com.quzzar.villagelife.llm;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.llm.provider.ClaudeProvider;
import com.quzzar.villagelife.llm.provider.JlamaWorkerProvider;
import com.quzzar.villagelife.llm.provider.LocalRuntimeProvider;
import com.quzzar.villagelife.llm.provider.LlmProvider;
import com.quzzar.villagelife.llm.provider.LlmProvider.CompletionRequest;
import com.quzzar.villagelife.llm.provider.OpenAiCompatibleProvider;

/**
 * Villager LLM requests, served by a config-selected {@link LlmProvider}:
 * the offline Jlama worker (default), a faster local runtime speaking the
 * OpenAI protocol, or Claude / OpenAI / DeepSeek in the cloud. Callers never
 * learn which provider answered.
 *
 * Queue order is chat > decide > persona: chats and decides dispatch
 * immediately (both foreground), and personas wait until nothing foreground
 * is in flight. Callers get futures that complete with
 * {@link Optional#empty()} whenever the provider is unavailable, times out,
 * or produces an unusable answer — game logic defers and retries next cycle,
 * per the LLM-required design.
 */
public final class LlmService {

  public enum Status {
    NOT_LOADED, STARTING, DOWNLOADING, LOADING, READY, FAILED
  }

  /** A true few-shot example turn, sent to every provider as real messages. */
  public record FewShotExample(String user, String assistant) {
  }

  private static final LlmService INSTANCE = new LlmService();

  // The decide() prompt: one-shot example + numeric choice + reason-first +
  // action echo (small models follow example turns, choose numbers more
  // reliably than copied text, and sometimes emit a number that contradicts
  // their own reasoning — the echoed action text catches that).
  private static final String DECIDE_SYSTEM = """
      You are the mind of a villager in a village simulation. \
      Choose the single most sensible action for the situation. \
      Answer with ONLY a JSON object: {"reason": "<one short sentence weighing the situation>", "choice": <option number>, "action": "<the chosen option, copied exactly>"}""";

  private static final List<FewShotExample> DECIDE_EXAMPLES = List.of(new FewShotExample(
      """
          Situation: The well has collapsed and there is no clean water. The sun is shining.

          Options:
          1. go swimming
          2. rebuild the well
          3. paint the fence

          Answer with ONLY the JSON object.""",
      "{\"reason\": \"We cannot live without clean water, so the well matters more than fun or chores.\", \"choice\": 2, \"action\": \"rebuild the well\"}"));

  private final Object providerLock = new Object();
  private volatile LlmProvider provider;

  /** Foreground (chat + decide) requests in flight; personas wait on zero. */
  private final AtomicInteger activeForeground = new AtomicInteger();

  /** Persona/flavor requests wait here so foreground work always jumps the line. */
  private record QueuedPersona(String system, String user, List<FewShotExample> examples,
      int maxNewTokens, double temperature, CompletableFuture<Optional<String>> future) {
  }

  private static final int PERSONA_QUEUE_LIMIT = 64;
  private final java.util.ArrayDeque<QueuedPersona> personaQueue = new java.util.ArrayDeque<>();
  private boolean personaInFlight = false; // guarded by personaQueue

  private LlmService() {
    // Backstop only, for paths that never reach ServerStoppingEvent (a crash,
    // or an integrated client closing). The server lifecycle is the real
    // trigger: a hook cannot run while the worker is what keeps the JVM alive.
    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "villagelife-llm-shutdown"));
  }

  public static LlmService get() {
    return INSTANCE;
  }

  public Status getStatus() {
    LlmProvider active = provider;
    return active == null ? Status.NOT_LOADED : active.getStatus();
  }

  public String getStatusDetail() {
    LlmProvider active = provider;
    return active == null ? "" : active.getStatusDetail();
  }

  public boolean isReady() {
    return getStatus() == Status.READY;
  }

  /**
   * Starts (or retries) the configured provider. The provider choice is read
   * once per game process; changing it in the config needs a restart.
   */
  public void startLoading() {
    if (!VillagelifeConfig.LlmEnabled) {
      return;
    }
    synchronized (providerLock) {
      if (provider == null) {
        provider = createProvider(VillagelifeConfig.LlmProviderName);
        Villagelife.LOGGER.info("LLM provider: {}", VillagelifeConfig.LlmProviderName);
      }
    }
    provider.start();
  }

  private static LlmProvider createProvider(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "claude" -> new ClaudeProvider(() -> VillagelifeConfig.LlmApiKey, () -> VillagelifeConfig.LlmCloudModel);
      case "openai" -> new OpenAiCompatibleProvider(OpenAiCompatibleProvider.OPENAI,
          () -> VillagelifeConfig.LlmApiKey, () -> VillagelifeConfig.LlmCloudModel);
      case "deepseek" -> new OpenAiCompatibleProvider(OpenAiCompatibleProvider.DEEPSEEK,
          () -> VillagelifeConfig.LlmApiKey, () -> VillagelifeConfig.LlmCloudModel);
      // Fetches llama.cpp and runs it itself: nothing to install, and several
      // times faster than the in-process Java model.
      case "llamacpp" -> new LocalRuntimeProvider();
      // Someone else's already-running server on this machine.
      case "local" -> new OpenAiCompatibleProvider(
          OpenAiCompatibleProvider.local(VillagelifeConfig.LlmLocalUrl, VillagelifeConfig.LlmCloudModel),
          () -> "", () -> VillagelifeConfig.LlmCloudModel);
      case "jlama" -> new JlamaWorkerProvider();
      default -> {
        Villagelife.LOGGER.error("Unknown LLM provider '{}', using jlama", name);
        yield new JlamaWorkerProvider();
      }
    };
  }

  /** Called by providers when they reach READY; flushes waiting personas. */
  public void onProviderReady() {
    tryDispatchPersona();
  }

  /** Stops the provider; called when the JVM shuts down. */
  public void shutdown() {
    LlmProvider active = provider;
    if (active != null) {
      active.shutdown();
    }
  }

  private CompletableFuture<Optional<String>> foregroundComplete(CompletionRequest request) {
    LlmProvider active = provider;
    if (active == null || active.getStatus() != Status.READY) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    activeForeground.incrementAndGet();
    return active.complete(request).whenComplete((result, error) -> {
      activeForeground.decrementAndGet();
      tryDispatchPersona();
    });
  }

  /**
   * Asks the LLM to pick one of the given options for the given situation.
   * Completes with empty if unavailable, timed out, or unmatchable — callers
   * defer to their previous state and ask again next cycle.
   */
  public CompletableFuture<Optional<LlmDecision>> decide(String situation, List<String> options) {
    if (!isReady() || options.isEmpty()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    StringBuilder user = new StringBuilder();
    user.append("Situation: ").append(situation).append("\n\nOptions:\n");
    for (int i = 0; i < options.size(); i++) {
      user.append(i + 1).append(". ").append(options.get(i)).append("\n");
    }
    user.append("\nAnswer with ONLY the JSON object.");

    List<String> optionsCopy = List.copyOf(options);
    return foregroundComplete(new CompletionRequest(DECIDE_SYSTEM, user.toString(), DECIDE_EXAMPLES,
        VillagelifeConfig.LlmMaxNewTokens, VillagelifeConfig.LlmTemperature))
        .thenApply(raw -> raw.flatMap(text -> parseDecision(text, optionsCopy)));
  }

  /**
   * Player-facing conversation request — the top of the queue order. See the
   * chat contract (conversation map #24).
   */
  public CompletableFuture<Optional<String>> submitChat(String system, String user,
      List<FewShotExample> examples, int maxNewTokens, double temperature) {
    return submitChat(system, user, examples, maxNewTokens, temperature, 0.0D);
  }

  /** As above, pushing the model off tokens it has just used (see PersonChatDispatcher). */
  public CompletableFuture<Optional<String>> submitChat(String system, String user,
      List<FewShotExample> examples, int maxNewTokens, double temperature, double frequencyPenalty) {
    return foregroundComplete(
        new CompletionRequest(system, user, examples, maxNewTokens, temperature, frequencyPenalty));
  }

  /**
   * Free-text generation with no queue priority — for callers that manage
   * their own scheduling. Most background work wants {@link #submitPersona}.
   */
  public CompletableFuture<Optional<String>> generate(String system, String user, int maxNewTokens,
      double temperature) {
    LlmProvider active = provider;
    if (active == null || active.getStatus() != Status.READY) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    return active.complete(new CompletionRequest(system, user, List.of(), maxNewTokens, temperature));
  }

  /**
   * Low-priority free-text generation for personas, relationship flavor, and
   * similar background work. Requests queue locally and dispatch only when
   * nothing foreground (chat or decide) is in flight and no other persona is
   * running. Same contract: empty on unavailability, error, or timeout.
   * Requests submitted while the provider is still loading wait in the queue.
   */
  public CompletableFuture<Optional<String>> submitPersona(String system, String user, int maxNewTokens,
      double temperature) {
    return submitPersona(system, user, List.of(), maxNewTokens, temperature);
  }

  /**
   * As {@link #submitPersona(String, String, int, double)}, with true few-shot
   * example turns — NEVER concatenate examples into the user message instead;
   * small models conflate the example with the request (a prototype-verified
   * failure: the example character bleeds into real output).
   */
  public CompletableFuture<Optional<String>> submitPersona(String system, String user,
      List<FewShotExample> examples, int maxNewTokens, double temperature) {
    if (!VillagelifeConfig.LlmEnabled) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    CompletableFuture<Optional<String>> future = new CompletableFuture<>();
    synchronized (personaQueue) {
      if (personaQueue.size() >= PERSONA_QUEUE_LIMIT) {
        Villagelife.LOGGER.warn("Persona queue full ({}), rejecting request", PERSONA_QUEUE_LIMIT);
        return CompletableFuture.completedFuture(Optional.empty());
      }
      personaQueue.add(new QueuedPersona(system, user, List.copyOf(examples), maxNewTokens, temperature, future));
    }
    tryDispatchPersona();
    return future;
  }

  private void tryDispatchPersona() {
    QueuedPersona next;
    synchronized (personaQueue) {
      if (personaInFlight || !isReady() || activeForeground.get() > 0 || personaQueue.isEmpty()) {
        return;
      }
      next = personaQueue.poll();
      personaInFlight = true;
    }
    // NOTE: this re-wraps the request rather than carrying one through, so any
    // field added to CompletionRequest has to be added HERE too or it silently
    // reverts to its default on the persona path only. Nothing fails loudly:
    // the knob works in chat, does nothing in personas, and looks like a model
    // problem. Chat reaches the provider via foregroundComplete and is not
    // affected.
    provider.complete(new CompletionRequest(next.system(), next.user(), next.examples(),
        next.maxNewTokens(), next.temperature()))
        .whenComplete((result, error) -> {
          synchronized (personaQueue) {
            personaInFlight = false;
          }
          next.future().complete(result != null ? result : Optional.empty());
          tryDispatchPersona();
        });
  }

  private static final Pattern CHOICE_PATTERN = Pattern.compile("\"choice\"\\s*:\\s*\"?(\\d+)");
  private static final Pattern REASON_PATTERN = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]*)\"");

  private static Optional<LlmDecision> parseDecision(String raw, List<String> options) {
    String choice = null;
    String reason = "";
    String action = null;

    // Strict pass first: the models sometimes wrap the answer in junk (extra
    // braces, several JSON objects, leading noise), so try first-'{' to
    // first-'}' and then to last-'}'.
    int jsonStart = raw.indexOf('{');
    for (int jsonEnd : new int[] { raw.indexOf('}', jsonStart), raw.lastIndexOf('}') }) {
      if (jsonStart >= 0 && jsonEnd > jsonStart) {
        try {
          JsonObject node = JsonParser.parseString(raw.substring(jsonStart, jsonEnd + 1)).getAsJsonObject();
          if (node.has("choice")) {
            choice = node.get("choice").getAsString();
            if (node.has("reason")) {
              reason = node.get("reason").getAsString();
            }
            if (node.has("action")) {
              action = node.get("action").getAsString();
            }
            break;
          }
        } catch (Exception ignored) {
          // try the next extraction strategy
        }
      }
    }

    // Lenient pass: pull the fields out with regexes even from malformed JSON.
    if (choice == null) {
      Matcher choiceMatcher = CHOICE_PATTERN.matcher(raw);
      if (choiceMatcher.find()) {
        choice = choiceMatcher.group(1);
        Matcher reasonMatcher = REASON_PATTERN.matcher(raw);
        if (reasonMatcher.find()) {
          reason = reasonMatcher.group(1);
        }
      }
    }
    if (choice == null) {
      choice = raw;
    }

    int index = matchOption(choice, options);
    if (index < 0) {
      Villagelife.LOGGER.warn("LLM chose '{}' which matches none of the options {}", choice, options);
      return Optional.empty();
    }

    // Consistency check: the model echoes its pick as text in "action". Small
    // models sometimes reason toward one option and emit a different number —
    // if the echoed text clearly names a DIFFERENT option, the answer is
    // untrustworthy and the caller should defer rather than gamble.
    if (action != null) {
      int actionIndex = matchOption(action, options);
      if (actionIndex >= 0 && actionIndex != index) {
        Villagelife.LOGGER.warn("LLM answer is inconsistent: choice {} but action '{}' (option {}), discarding",
            index + 1, action, actionIndex + 1);
        return Optional.empty();
      }
    }

    return Optional.of(new LlmDecision(options.get(index), index, reason));
  }

  /** Matches the model's answer to an option: exact, then number, then fuzzy. */
  private static int matchOption(String choice, List<String> options) {
    String normalized = choice.trim();

    for (int i = 0; i < options.size(); i++) {
      if (options.get(i).equalsIgnoreCase(normalized)) {
        return i;
      }
    }

    // The model usually answers with the option number (1-based)
    try {
      int number = Integer.parseInt(normalized.replaceAll("[^0-9]", ""));
      if (number >= 1 && number <= options.size() && normalized.length() <= 4) {
        return number - 1;
      }
    } catch (NumberFormatException ignored) {
    }

    String lower = normalized.toLowerCase(Locale.ROOT);
    for (int i = 0; i < options.size(); i++) {
      String option = options.get(i).toLowerCase(Locale.ROOT);
      if (lower.contains(option) || option.contains(lower)) {
        return i;
      }
    }
    return -1;
  }

}
