# The villager LLM brain

**This is the decided design for LLM-driven villager decisions.** A small language model
runs locally in a worker process (Jlama, pure Java — no external install) and answers
multiple-choice questions; deterministic game logic decides *what the options are*, the
model only picks among them and supplies an in-character reason.

**The LLM is REQUIRED** (decided 2026-08-26): the mod does not maintain a parallel
rule-based decision system. If the worker is unavailable, villages stop progressing and
the player is told loudly why — the server itself must never crash over it. An individual
failed or timed-out decision means *defer*: the brain keeps its previous focus and asks
again next cycle, it never picks randomly. State the system requirements on the mod page:
~1.5 GB RAM beyond the server's needs (default 1B model; ~600 MB on the 0.5B), internet
on first boot for the model download.

## How a decision works

`LlmService.decide(situation, options)` returns `CompletableFuture<Optional<LlmDecision>>`:

- The prompt is a one-shot example plus the situation and a numbered option list; the model
  answers `{"reason": "...", "choice": <number>, "action": "<option text>"}`. Reason comes
  *before* choice on purpose, and `action` echoes the pick as text: small models sometimes
  reason toward one option and emit a different number, so an answer whose number and echoed
  text name different options is discarded (the caller defers and retries next cycle).
- The answer is parsed leniently (strict JSON, then regex, then plain-text match) and
  validated against the option list. Anything unusable → `Optional.empty()` → the caller's
  rule-based default. The model cannot pick an action that doesn't exist.
- Inference runs in the worker process (see below), one request at a time; the worker
  loads the model at server start with a warm-up generation so broken backends fail at
  load, not mid-game. Requests time out after 60s and count as "no answer".
- `/vlbrain status | load | ask <situation> | <opt> | <opt>` (permission level 2) exercises
  all of it in-game.

## Providers and models

The model backend is a config-selected **provider** behind `LlmService` (`llm/provider/`);
callers never learn which provider answered, and all of them receive the same prompts,
few-shot example turns, and parsing:

- **`jlama`** (default): the offline worker process below. Any Jlama-compatible JQ4
  HuggingFace id works. `tjake/Llama-3.2-1B-Instruct-JQ4` (~800 MB) is the default and
  the **only actively tested** offline model (Aaron's posture: a listed preset is an
  endorsement). `granite-3.0-2b` and `gemma-2-2b` are named in the config comment as
  untested alternates; Mistral-7B is deliberately delisted (measured degeneration,
  latency, and harmful give-polarity — it still runs if someone pastes the id). Models
  download on first server start into `<game dir>/villagelife/models`. Jlama cannot run
  Qwen3 or SmolLM3 (architectures newer than Jlama 0.8.4).
- **`claude` / `openai` / `deepseek`**: cloud, with the operator's API key in the common
  config (never a SERVER-type config — those sync to clients; the key never reaches logs
  or status strings, masked last-4 at most). Cloud calls run in the game process over
  `java.net.http` — the worker exists only for Jlama's JVM-flag needs. DeepSeek is the
  OpenAI client with a different base URL. API dialect gotchas live as comments in the
  provider classes (Anthropic: `x-api-key`, top-level `system`, never send temperature;
  OpenAI: `max_completion_tokens`, minimal reasoning effort).

**Chat-with-actions caveat:** action-fidelity failure *direction* differs by model, and it
matters more than rate. The 1B fails safe (promises an item without emitting the give —
nothing moves); granite-2b and mistral-7b were both measured failing harmful (verbally
refusing while emitting the give, so the item leaves their pockets against stated intent).
For servers with chat actions, use the 1B default or a cloud provider; treat the larger
offline presets as decision/persona models. Evidence: provider-map tickets #33/#36.

Original 1B-tier benchmark for the record (5-scenario decisions, Apple Silicon, native
path): 0.5B answered 3/5 at ~65 tok/s; 1B 4/5 at ~37 tok/s; Llama-3B 4/5 at ~17 tok/s —
the fuller model tasting that chose the presets is archived on provider-map ticket #33.

## The worker process — why no JVM setup is needed

Jlama 0.8.4 cannot construct a model without the Vector API module
(`AbstractModel.<init>` references `FloatVector` directly) and needs `--enable-preview`
on Java 21 for its fast native backend — flags a mod cannot add to its own JVM. So the
model does not run in the game JVM at all: `LlmService` spawns a **worker child JVM**
(same Java runtime the game is on, found via `ProcessHandle`) with both flags baked into
the launch command. `LlmWorker` (a plain-Java mod class, no Minecraft imports) owns Jlama
and the model there, speaking one JSON object per line over stdio. Verified end to end:
native SIMD loads in the worker from the shaded mod jar, decisions round-trip in ~2s.

Side benefits: the model's memory lives outside the game heap, and an inference crash
kills the worker, never the server. Worker classpath: production = the mod jar (Jlama is
shaded in) + libraries extracted once from `META-INF/jarjar/` and `META-INF/workerlibs/`
(a child process cannot read jar-in-jar); dev = the parent's own `java.class.path`.
`META-INF/workerlibs/` exists because the worker needs slf4j/guava/commons-lang3, which
Minecraft provides to the game JVM but a bare child lacks — they must NOT go through
jarJar or FML would load them next to Minecraft's copies.

Failure modes are deliberate: if the host forbids child processes the status turns
`FAILED` with a clear detail, and if the worker dies (e.g. OOM-killed on a memory-capped
shared host) it is **not** auto-restarted — no crash-looping; `/vlbrain load` retries
manually. Villagers always fall back to rule-based logic.

**Hosting guidance:** the worker adds its heap (`LLM worker heap MB`, default 1024) plus
the model's resident size on top of the server's memory. Budget ~1.5 GB extra for the
default 1B model; on small hosted plans use the 0.5B model or disable the LLM.

## Packaging (why build.gradle looks the way it does)

Jlama under FML needs the `shadeJlama` merge — jlama-core + platform natives as **one flat
jar** (shaded into the mod jar for production, `build/jlama/jlama-bundle-*.jar` on
`additionalRuntimeClasspath` for dev). Two stacked reasons, both verified the hard way:
the two jars split a Java package (illegal across FML modules), and FML ignores
multi-release `META-INF/versions/*`, where all of jlama-native's FFM classes live
(`versions/21` is flattened to the jar root; 20 can never load on Java 21, 22 only on 22+).
Never put two same-OS jlama-native jars on any classpath — they store their library at the
same resource path and the first one wins (this is why osx-x86_64 is excluded). Plain
libraries (Jackson, jinjava, …) stay as ordinary jarJar + additionalRuntimeClasspath
entries; they have no split packages.
