package com.quzzar.kithkyn.llm;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.configuration.KithkynConfig;
import com.quzzar.kithkyn.llm.provider.ClaudeProvider;
import com.quzzar.kithkyn.llm.provider.FailedProvider;
import com.quzzar.kithkyn.llm.provider.LocalRuntimeProvider;
import com.quzzar.kithkyn.llm.provider.LlmProvider;
import com.quzzar.kithkyn.llm.provider.LlmProvider.CompletionRequest;
import com.quzzar.kithkyn.llm.provider.OpenAiCompatibleProvider;

/**
 * Villager LLM requests, served by a config-selected {@link LlmProvider}:
 * the offline llama.cpp runtime (default), or Claude / OpenAI / DeepSeek in
 * the cloud. Callers never learn which provider answered.
 *
 * Queue order is chat > decide > background: chats and decides dispatch
 * immediately (both foreground), and the background queue (personas,
 * relationship flavor, villager-to-villager chatter) waits until nothing
 * foreground is in flight. Callers get futures that complete with
 * {@link Optional#empty()} whenever the provider is unavailable, times out,
 * or produces an unusable answer — game logic defers and retries next cycle,
 * per the LLM-required design.
 *
 * Every request names its purpose in a few words ("Quzzar -> Jasper Ferguson",
 * "what Mangrove's Edge builds next"), and every call, served or skipped, is
 * written in full to the call log ({@link LlmCallLog}). Nothing reaches a
 * provider except through {@link #callProvider}, which is what makes that
 * record complete.
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

  private static final String REDEVELOPMENT_SYSTEM = DECIDE_SYSTEM + """

      Redevelopment permanently removes the listed buildings and loses unrecovered materials.
      First identify a concrete current need in the situation. If no additional capacity or capability is needed, choose waiting.
      A higher building level is not itself a need. Do not invent future demand, extra benefits or savings.
      Compare NET capacity changes and total remaining costs. If ordinary construction meets the same need with less cost and no removal, choose it.
      Choose redevelopment when it meets a current need and the offered ordinary alternatives cannot meet that need as well.
      """;

  private static final List<FewShotExample> REDEVELOPMENT_EXAMPLES = List.of(
      new FewShotExample(
          "Situation: All storage is sufficient. There are eight empty beds and no building requests.\nOptions:\n"
              + "1. replace two fields with a larger house\n2. wait and keep resources",
          "{\"reason\":\"No more housing is needed, so losing fields and materials buys nothing useful.\",\"choice\":2,\"action\":\"wait and keep resources\"}"),
      new FewShotExample(
          "Situation: One more bed is needed. A new cottage costs 12 logs and removes nothing. An expansion adds the same one bed, costs 18 logs and removes a field.\nOptions:\n"
              + "1. expand the house by removing a field\n2. build the cottage\n3. wait",
          "{\"reason\":\"The cottage meets the same need for fewer logs while keeping the field.\",\"choice\":2,\"action\":\"build the cottage\"}"),
      new FewShotExample(
          "Situation: Storage is full. No separate storehouse site fits. Expanding the storehouse requires removing an unused shed. Contents can be moved, all materials are secured, and essential services remain.\nOptions:\n"
              + "1. wait\n2. expand the storehouse by removing the unused shed",
          "{\"reason\":\"We need storage and the expansion is the only feasible way to add it.\",\"choice\":2,\"action\":\"expand the storehouse by removing the unused shed\"}"));

  private static final List<FewShotExample> DECIDE_EXAMPLES = List.of(new FewShotExample(
      """
          Situation: The well has collapsed and there is no clean water. The sun is shining.

          Options:
          1. go swimming
          2. rebuild the well
          3. paint the fence

          Answer with ONLY the JSON object.""",
      "{\"reason\": \"We cannot live without clean water, so the well matters more than fun or chores.\", \"choice\": 2, \"action\": \"rebuild the well\"}"));

  /**
   * The multi-pick sibling of DECIDE_SYSTEM: every option that fits, or none.
   * The example keeps some and leaves some, so a small model sees that both
   * halves of the answer are ordinary.
   */
  private static final String CHOOSE_SYSTEM = """
      You are the mind of a villager in a village simulation. \
      Choose every option that makes sense for the situation, which may be several or none at all. \
      Answer with ONLY a JSON object: {"reason": "<one short sentence weighing the situation>", "choices": [<the option numbers you choose, or an empty list>]}""";

  private static final List<FewShotExample> CHOOSE_EXAMPLES = List.of(new FewShotExample(
      """
          Situation: You are packing for a week of work at the quarry. On the table are these things.

          Options:
          1. Take the pickaxe
          2. Take the fishing rod
          3. Take the bread
          4. Take the wedding ring

          Answer with ONLY the JSON object.""",
      "{\"reason\": \"The quarry wants a pick and food; there is no water to fish and the ring is safer at home.\", \"choices\": [1, 3]}"));

  private final Object providerLock = new Object();
  private volatile LlmProvider provider;

  /** Foreground (chat + decide) requests in flight; personas wait on zero. */
  private final AtomicInteger activeForeground = new AtomicInteger();

  // A player talking to a villager outranks everything the villages are
  // thinking about ([#65] latency). The villagers share one model with the
  // player, so a village deciding what to build or writing someone's life
  // story lands on the same server a chat reply needs - and a player feels
  // every millisecond of a reply while nobody waits on a village's private
  // deliberation. So while a conversation is live, the village work holds.
  private final AtomicInteger activeChat = new AtomicInteger();
  private volatile long chatWindowUntilMs = 0L;

  /**
   * Village seconds of quiet a chat buys. Covers the gaps BETWEEN a player's
   * turns, not just the in-flight reply: without it a village decision started
   * in the pause after "hello" would collide with the answer to their next
   * line. Refreshed on every chat, so a conversation holds the villages off
   * for its whole length and lets them resume a few seconds after it ends.
   */
  private static final long CHAT_PRIORITY_WINDOW_MS = 6000L;

  /** Whether a player is mid-conversation right now, or was moments ago. */
  private boolean playerTalking() {
    return activeChat.get() > 0 || System.currentTimeMillis() < chatWindowUntilMs;
  }

  /** Background requests wait here so foreground work always jumps the line. */
  private record QueuedPersona(String lane, String purpose, String system, String user,
      List<FewShotExample> examples, int maxNewTokens, double temperature, double frequencyPenalty,
      CompletableFuture<Optional<String>> future) {
  }

  /** The lane names in the call log: which queue a request rode. */
  private static final String LANE_CHAT = "chat";
  private static final String LANE_DECIDE = "decide";
  private static final String LANE_BACKGROUND = "background";
  private static final String LANE_VILLAGER_CHAT = "villager-chat";

  private static final int PERSONA_QUEUE_LIMIT = 64;
  private final java.util.ArrayDeque<QueuedPersona> personaQueue = new java.util.ArrayDeque<>();
  private boolean personaInFlight = false; // guarded by personaQueue

  private LlmService() {
    // Backstop only, for paths that never reach ServerStoppingEvent (a crash,
    // or an integrated client closing). The server lifecycle is the real
    // trigger: a hook cannot run while the worker is what keeps the JVM alive.
    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "kithkyn-llm-shutdown"));
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
    if (!KithkynConfig.LlmEnabled) {
      return;
    }
    LlmCallLog.install();
    synchronized (providerLock) {
      if (provider == null) {
        provider = createProvider(KithkynConfig.LlmProviderName);
        Kithkyn.LOGGER.info("LLM provider: {}", KithkynConfig.LlmProviderName);
      }
    }
    provider.start();
  }

  static LlmProvider createProvider(String name) {
    String normalized = name == null ? "" : name.trim();
    Optional<LlmProvider> cloud = cloudProvider(normalized,
        () -> KithkynConfig.LlmApiKey, () -> KithkynConfig.LlmCloudModel);
    if (cloud.isPresent()) {
      return cloud.get();
    }
    if ("local".equalsIgnoreCase(normalized)) {
      // The only offline provider: fetches llama.cpp and a model and runs them
      // itself, nothing to install. Cloud (claude/openai/deepseek) returned above.
      return new LocalRuntimeProvider();
    }
    String configuredName = normalized.isEmpty() ? "<blank>" : normalized;
    String detail = "Unknown LLM provider '" + configuredName
        + "'; expected local, claude, openai, or deepseek";
    Kithkyn.LOGGER.error("{}. The local runtime will not start.", detail);
    return new FailedProvider(detail);
  }

  /**
   * Builds a CLOUD provider ("claude"/"openai"/"deepseek") from the given key and
   * model suppliers, or empty for any non-cloud name. The single cloud-construction
   * site. {@code complete()} on a cloud provider needs no {@link #startLoading()}, so
   * a caller can use the returned provider directly.
   */
  private static Optional<LlmProvider> cloudProvider(String name,
      java.util.function.Supplier<String> apiKey, java.util.function.Supplier<String> model) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "claude" -> Optional.of(new ClaudeProvider(apiKey, model));
      case "openai" -> Optional.of(new OpenAiCompatibleProvider(OpenAiCompatibleProvider.OPENAI, apiKey, model));
      case "deepseek" -> Optional.of(new OpenAiCompatibleProvider(OpenAiCompatibleProvider.DEEPSEEK, apiKey, model));
      default -> Optional.empty();
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

  /**
   * The one road to a provider. Records the request, sends it, records the
   * reply (or the failure) against the same call number. Every public entry
   * point ends here, so the call log is the whole story of what the model was
   * asked and said.
   */
  public static CompletableFuture<Optional<String>> callProvider(LlmProvider active, String lane,
      String purpose, CompletionRequest request) {
    long id = LlmCallLog.request(lane, purpose, request);
    long startedMs = System.currentTimeMillis();
    return active.complete(request).whenComplete((result, error) ->
        LlmCallLog.reply(id, lane, purpose, result, error, System.currentTimeMillis() - startedMs));
  }

  private CompletableFuture<Optional<String>> foregroundComplete(String lane, String purpose,
      CompletionRequest request) {
    LlmProvider active = provider;
    if (active == null || active.getStatus() != Status.READY) {
      LlmCallLog.skipped(lane, purpose, "provider not ready (" + getStatus() + ")");
      return CompletableFuture.completedFuture(Optional.empty());
    }
    activeForeground.incrementAndGet();
    return callProvider(active, lane, purpose, request).whenComplete((result, error) -> {
      activeForeground.decrementAndGet();
      tryDispatchPersona();
    });
  }

  /**
   * Asks the LLM to pick one of the given options for the given situation.
   * Completes with empty if unavailable, timed out, or unmatchable — callers
   * defer to their previous state and ask again next cycle. {@code purpose}
   * names the decision for the call log, in a few words.
   */
  public CompletableFuture<Optional<LlmDecision>> decide(String purpose, String situation, List<String> options) {
    return decide(purpose, situation, options, false);
  }

  /** Destructive construction requires an exact number and action match in a complete JSON answer. */
  public CompletableFuture<Optional<LlmDecision>> decideStrict(String purpose, String situation, List<String> options) {
    return decide(purpose, situation, options, true);
  }

  private CompletableFuture<Optional<LlmDecision>> decide(String purpose, String situation,
      List<String> options, boolean strict) {
    if (options.isEmpty() || !decisionCanRun(purpose)) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    List<String> optionsCopy = List.copyOf(options);
    return foregroundComplete(LANE_DECIDE, purpose, new CompletionRequest(strict ? REDEVELOPMENT_SYSTEM : DECIDE_SYSTEM,
        optionsPrompt(situation, options), strict ? REDEVELOPMENT_EXAMPLES : DECIDE_EXAMPLES, KithkynConfig.LlmDecisionMaxNewTokens,
        KithkynConfig.LlmDecisionTemperature))
        .thenApply(raw -> raw.flatMap(text -> strict ? parseStrictDecision(text, optionsCopy) : parseDecision(text, optionsCopy)));
  }

  /** The strict path never accepts a bare number, fuzzy action, missing echo, or truncated response. */
  static Optional<LlmDecision> parseStrictDecision(String raw, List<String> options) {
    try (var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(raw))) {
      if (!hasStrictJsonStrings(raw)) {
        return Optional.empty();
      }
      // JsonParser.parseString temporarily enables leniency and silently overwrites duplicate keys.
      reader.setLenient(false);
      java.util.Set<String> fields = new java.util.HashSet<>();
      Integer number = null;
      String action = null;
      String reason = null;
      reader.beginObject();
      while (reader.hasNext()) {
        String field = reader.nextName();
        if (!fields.add(field)) {
          return Optional.empty();
        }
        switch (field) {
          case "choice" -> {
            if (reader.peek() != com.google.gson.stream.JsonToken.NUMBER) {
              return Optional.empty();
            }
            number = new java.math.BigDecimal(reader.nextString()).intValueExact();
          }
          case "action", "reason" -> {
            if (reader.peek() != com.google.gson.stream.JsonToken.STRING) {
              return Optional.empty();
            }
            String value = reader.nextString();
            if (field.equals("action")) {
              action = value;
            } else {
              reason = value;
            }
          }
          default -> {
            return Optional.empty();
          }
        }
      }
      reader.endObject();
      if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT
          || number == null || reason == null || action == null || number < 1 || number > options.size()
          || !options.get(number - 1).equals(action)) {
        return Optional.empty();
      }
      return Optional.of(new LlmDecision(action, number - 1, reason));
    } catch (RuntimeException | java.io.IOException invalid) {
      return Optional.empty();
    }
  }

  /** Gson 2.10's legacy strict reader still tolerates raw control characters and escaped apostrophes. */
  private static boolean hasStrictJsonStrings(String raw) {
    boolean quoted = false;
    for (int index = 0; index < raw.length(); index++) {
      char character = raw.charAt(index);
      if (!quoted) {
        quoted = character == '"';
      } else if (character == '"') {
        quoted = false;
      } else if (character < 0x20) {
        return false;
      } else if (character == '\\') {
        if (++index >= raw.length()) {
          return false;
        }
        char escape = raw.charAt(index);
        if (escape == 'u') {
          for (int digit = 0; digit < 4; digit++) {
            if (++index >= raw.length() || Character.digit(raw.charAt(index), 16) < 0) {
              return false;
            }
          }
        } else if ("\"\\/bfnrt".indexOf(escape) < 0) {
          return false;
        }
      }
    }
    return !quoted;
  }

  /**
   * Asks the LLM which of the given options apply to the situation: any
   * number of them, or none. The multi-pick sibling of {@link #decide}, on the
   * same lane with the same gates and the same failure semantics: empty when
   * unavailable, timed out, or unparseable, and the caller's own default
   * stands in. An answer that names no option at all is a valid answer (an
   * empty selection), distinct from no answer.
   */
  public CompletableFuture<Optional<LlmSelection>> choose(String purpose, String situation, List<String> options) {
    if (options.isEmpty() || !decisionCanRun(purpose)) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    int count = options.size();
    return foregroundComplete(LANE_DECIDE, purpose, new CompletionRequest(CHOOSE_SYSTEM,
        optionsPrompt(situation, options), CHOOSE_EXAMPLES, KithkynConfig.LlmDecisionMaxNewTokens,
        KithkynConfig.LlmDecisionTemperature))
        .thenApply(raw -> raw.flatMap(text -> parseSelection(text, count)));
  }

  /**
   * The gates a decision passes before it is sent: a provider that is ready,
   * and no player mid-conversation. A build choice waits behind a live
   * conversation: decide already treats an empty answer as "keep the current
   * plan and ask again next cycle", so a village simply re-decides once the
   * player has stopped talking; no work is lost, and a player's reply is not
   * slowed by a village thinking. Logs the skip when it says no.
   */
  private boolean decisionCanRun(String purpose) {
    if (!isReady()) {
      LlmCallLog.skipped(LANE_DECIDE, purpose, "provider not ready (" + getStatus() + ")");
      return false;
    }
    if (playerTalking()) {
      LlmCallLog.skipped(LANE_DECIDE, purpose, "a player is mid-conversation; asked again next cycle");
      return false;
    }
    return true;
  }

  /** The situation and the numbered options, as both decide and choose put them. */
  private static String optionsPrompt(String situation, List<String> options) {
    StringBuilder user = new StringBuilder();
    user.append("Situation: ").append(situation).append("\n\nOptions:\n");
    for (int i = 0; i < options.size(); i++) {
      user.append(i + 1).append(". ").append(options.get(i)).append("\n");
    }
    user.append("\nAnswer with ONLY the JSON object.");
    return user.toString();
  }

  /**
   * Player-facing conversation request — the top of the queue order. See the
   * chat contract (conversation map #24). The frequency penalty pushes the
   * model off tokens it has just used (see PersonChatDispatcher). {@code
   * purpose} names the exchange for the call log: who is talking to whom.
   */
  public CompletableFuture<Optional<String>> submitChat(String purpose, String system, String user,
      List<FewShotExample> examples, int maxNewTokens, double temperature, double frequencyPenalty) {
    // Mark a conversation live so village work yields, and keep the window open
    // a few seconds past this reply for the player's next line.
    activeChat.incrementAndGet();
    chatWindowUntilMs = System.currentTimeMillis() + CHAT_PRIORITY_WINDOW_MS;
    return foregroundComplete(LANE_CHAT, purpose,
        new CompletionRequest(system, user, examples, maxNewTokens, temperature, frequencyPenalty))
        .whenComplete((result, error) -> {
          activeChat.decrementAndGet();
          chatWindowUntilMs = System.currentTimeMillis() + CHAT_PRIORITY_WINDOW_MS;
        });
  }

  /**
   * Low-priority free-text generation for personas, relationship flavor, and
   * similar background work. Requests queue locally and dispatch only when
   * nothing foreground (chat or decide) is in flight and no other persona is
   * running. Same contract: empty on unavailability, error, or timeout.
   * Requests submitted while the provider is still loading wait in the queue.
   * {@code purpose} names the work for the call log.
   */
  public CompletableFuture<Optional<String>> submitPersona(String purpose, String system, String user,
      int maxNewTokens, double temperature) {
    return submitPersona(purpose, system, user, List.of(), maxNewTokens, temperature);
  }

  /**
   * As {@link #submitPersona(String, String, String, int, double)}, with true
   * few-shot example turns — NEVER concatenate examples into the user message
   * instead; small models conflate the example with the request (a
   * prototype-verified failure: the example character bleeds into real output).
   */
  public CompletableFuture<Optional<String>> submitPersona(String purpose, String system, String user,
      List<FewShotExample> examples, int maxNewTokens, double temperature) {
    return submitBackground(LANE_BACKGROUND, purpose, system, user, examples, maxNewTokens, temperature, 0.0D);
  }

  /**
   * A conversation line between two VILLAGERS. Same prompt contract as
   * {@link #submitChat}, but on the background queue: nobody is standing at a
   * screen waiting for it, so it must never delay a player's reply or a
   * village decision, and it holds no priority window of its own.
   */
  public CompletableFuture<Optional<String>> submitBackgroundChat(String purpose, String system, String user,
      List<FewShotExample> examples, int maxNewTokens, double temperature, double frequencyPenalty) {
    return submitBackground(LANE_VILLAGER_CHAT, purpose, system, user, examples, maxNewTokens, temperature,
        frequencyPenalty);
  }

  private CompletableFuture<Optional<String>> submitBackground(String lane, String purpose, String system,
      String user, List<FewShotExample> examples, int maxNewTokens, double temperature,
      double frequencyPenalty) {
    if (!KithkynConfig.LlmEnabled) {
      LlmCallLog.skipped(lane, purpose, "the LLM is disabled in the config");
      return CompletableFuture.completedFuture(Optional.empty());
    }
    CompletableFuture<Optional<String>> future = new CompletableFuture<>();
    synchronized (personaQueue) {
      if (personaQueue.size() >= PERSONA_QUEUE_LIMIT) {
        Kithkyn.LOGGER.warn("Background LLM queue full ({}), rejecting request", PERSONA_QUEUE_LIMIT);
        LlmCallLog.skipped(lane, purpose, "background queue full (" + PERSONA_QUEUE_LIMIT + ")");
        return CompletableFuture.completedFuture(Optional.empty());
      }
      personaQueue.add(new QueuedPersona(lane, purpose, system, user, List.copyOf(examples), maxNewTokens,
          temperature, frequencyPenalty, future));
    }
    tryDispatchPersona();
    return future;
  }

  private void tryDispatchPersona() {
    QueuedPersona next;
    synchronized (personaQueue) {
      if (personaInFlight || !isReady() || activeForeground.get() > 0
          || playerTalking() || personaQueue.isEmpty()) {
        return;
      }
      next = personaQueue.poll();
      personaInFlight = true;
    }
    // NOTE: this re-wraps the request rather than carrying one through, so any
    // field added to CompletionRequest has to be added HERE too (and to
    // QueuedPersona) or it silently reverts to its default on the background
    // path only. Nothing fails loudly: the knob works in chat, does nothing
    // here, and looks like a model problem. Chat reaches the provider via
    // foregroundComplete and is not affected.
    callProvider(provider, next.lane(), next.purpose(), new CompletionRequest(next.system(), next.user(),
        next.examples(), next.maxNewTokens(), next.temperature(), next.frequencyPenalty()))
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
  private static final Pattern CHOICES_PATTERN = Pattern.compile("\"choices\"\\s*:\\s*\\[([^\\]]*)\\]");
  private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

  /**
   * The multi-pick answer: option numbers out of "choices", validated against
   * the option count, deduplicated, in the order given. Strict JSON first,
   * then the same lenient regex pass as a single decision. A "choices" that
   * is present but empty is a real answer (nothing chosen); a reply with no
   * "choices" at all is no answer.
   */
  private static Optional<LlmSelection> parseSelection(String raw, int optionCount) {
    List<Integer> numbers = null;
    String reason = "";

    int jsonStart = raw.indexOf('{');
    for (int jsonEnd : new int[] { raw.indexOf('}', jsonStart), raw.lastIndexOf('}') }) {
      if (jsonStart >= 0 && jsonEnd > jsonStart) {
        try {
          JsonObject node = JsonParser.parseString(raw.substring(jsonStart, jsonEnd + 1)).getAsJsonObject();
          if (node.has("choices") && node.get("choices").isJsonArray()) {
            numbers = new java.util.ArrayList<>();
            JsonArray array = node.getAsJsonArray("choices");
            for (JsonElement element : array) {
              Matcher digits = NUMBER_PATTERN.matcher(element.isJsonPrimitive() ? element.getAsString() : "");
              if (digits.find()) {
                numbers.add(Integer.parseInt(digits.group()));
              }
            }
            if (node.has("reason")) {
              reason = node.get("reason").getAsString();
            }
            break;
          }
        } catch (Exception ignored) {
          // try the next extraction strategy
        }
      }
    }

    if (numbers == null) {
      Matcher choices = CHOICES_PATTERN.matcher(raw);
      if (choices.find()) {
        numbers = new java.util.ArrayList<>();
        Matcher digits = NUMBER_PATTERN.matcher(choices.group(1));
        while (digits.find()) {
          numbers.add(Integer.parseInt(digits.group()));
        }
        Matcher reasonMatcher = REASON_PATTERN.matcher(raw);
        if (reasonMatcher.find()) {
          reason = reasonMatcher.group(1);
        }
      }
    }
    if (numbers == null) {
      Kithkyn.LOGGER.warn("LLM gave no \"choices\" to pick from: {}", raw.trim());
      return Optional.empty();
    }

    List<Integer> indexes = new java.util.ArrayList<>();
    for (int number : numbers) {
      int index = number - 1;
      if (index < 0 || index >= optionCount) {
        Kithkyn.LOGGER.warn("LLM chose option {} of {}, which does not exist; ignoring it", number, optionCount);
        continue;
      }
      if (!indexes.contains(index)) {
        indexes.add(index);
      }
    }
    return Optional.of(new LlmSelection(List.copyOf(indexes), reason));
  }

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
      Kithkyn.LOGGER.warn("LLM chose '{}' which matches none of the options {}", choice, options);
      return Optional.empty();
    }

    // Consistency check: the model echoes its pick as text in "action". Small
    // models sometimes reason toward one option and emit a different number —
    // if the echoed text clearly names a DIFFERENT option, the answer is
    // untrustworthy and the caller should defer rather than gamble.
    if (action != null) {
      int actionIndex = matchOption(action, options);
      if (actionIndex >= 0 && actionIndex != index) {
        Kithkyn.LOGGER.warn("LLM answer is inconsistent: choice {} but action '{}' (option {}), discarding",
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
