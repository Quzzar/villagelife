# The villager LLM brain

**This is the decided design for LLM-driven villager decisions.** A small language model
answers multiple-choice questions and holds short conversations; deterministic game logic
decides *what the options are*, and the model only picks among them and supplies an
in-character reason. It runs offline through llama.cpp (default) or against a cloud service.

**The rules decide what is legal; the model decides which** (revised 2026-08-26 after an
audit found the decision call had no production caller; revised 2026-08-30 to drop the
options cap). The first real consumer is the urban planner: `UrbanPlanner.affordableBuilds`
filters the whole catalogue down to buildings this village can legally and affordably start,
and `outOfReach` to those it could save toward. Neither is ranked: the rules do not score one
need above another, because that judgement is the model's. `decide()` offers the model the
WHOLE vetted field, every option labelled with what it would give (beds, jobs, stores, and
effects like "defends the village" or "cuts stone") and, crucially, the dependency facts that
let the model reason a step ahead: a producer's line notes it makes a material other buildings
need ("provides the oak log that other buildings are built from"), and a save-for goal names
what it is short of and which building would make it ("still needs 2 oak log (a lumberjack
would make oak log)"). So the model can see the chain for itself, that a farm needs oak logs
and oak logs need a lumberjack, and choose the lumberjack. The model picks one and says why; it
never sees an illegal, unaffordable, or unreachable option, so it cannot invent a nonsensical
project, but within that field the choice is entirely its own. The cap that once trimmed the
list was a crutch for a weak model; richer per-option context, including the dependency chain,
serves a small model better than a short list did.

One correction the rules make after the fact (2026-09-01, [#80](https://github.com/Quzzar/villagelife/issues/80)):
a save-for goal that runs its whole lifetime with its shortfall exactly as it was when named
has proved something `outOfReach` could not see, that nobody here can get what it needs
(`withinReach` lets a material through when some is already in store, and a fresh camp starts
with a few logs it has no way to add to). `VillageGoal` marks such a goal stalled and it sits out
two goal lifetimes; the brain is told so in its situation ("nothing came in for it the whole
time, so it is off the table for now") rather than quietly losing an option. Without this a camp
re-named its lumberjack lodge ten times over four hours, nine logs short throughout.

A second consumer places labor. `JobClaiming` fills an open post from the campfire pool,
and when two or more idle people are near-equally suited by aptitude (within a small
`PICK_DELTA`) it offers those near-equals to `decide()` to choose among on character, each
described by name and persona blurb. The principle is the planner's: the rules decide who is
eligible (here, only the near-equally suited), the model picks among them and gives its
reason, and the aptitude best stands in whenever the model is absent, slow, or unusable, so a
contested post is the only thing ever handed over and competence is never traded for
character. See
[population-and-labor.md](population-and-labor.md). The swap pass that reorganizes existing
workers stays purely rule-based.

Further consumers follow the same shape. `VillageTrading.consider` proposes every legal,
beneficial bank trade plus an explicit "trade nothing", and the best deal stands in for a
silent model. And the miner's bedtime torch press is the first *personal* decision: the
nightly restock (`RealPerson.goToBed`) tops a miner's pack up to sixteen torches from village
stores, and when the stores are out of torches but hold coal or charcoal, the rules size a
top-up (one lump makes four torches; sticks are deliberately waived so shaft lighting never
waits on the forest) and offer the miner, in their own persona, the press or leaving the coal.
An explicit refusal is honored and logged with its reason; silence crafts anyway, so the
shaft never goes dark over a mute model. The ask itself lives in the shared `CraftOffer`
helper (`entities/`): a job's trigger sizes a `Press` (spend items, product, yield per unit)
and writes the situation prose over `CraftOffer.identityLead`, and the helper carries the
options, the answer, and the hands, so any occupation can put its own press to its own brain
the same way. The farmer's bedtime bone grind is the second personal press: when the restock
leaves the pack short of its sixteen bone meal and the stores hold bones, the same helper asks
whether to grind them (one bone makes three), with the same semantics, and the meal joins the
farm's fertiliser shelf described in [worker-loops.md](worker-loops.md).

That makes the LLM **strongly wanted, not structurally required**: when it is absent,
slow, or gives an unusable answer, the rules' own top-scoring option stands in and the
village keeps building. What is lost is the character of the choice, not the ability to
choose. Villages never stall waiting for a model, and a decision already in flight is
never duplicated. State the system requirements on the mod page: roughly 3 GB of RAM beyond
the server's needs for the default offline model (Llama-3.2-3B), or none if you point it at
a cloud provider; internet on first boot for the model download.

## How a decision works

`LlmService.decide(purpose, situation, options)` returns `CompletableFuture<Optional<LlmDecision>>`:

- The prompt is a one-shot example plus the situation and a numbered option list; the model
  answers `{"reason": "...", "choice": <number>, "action": "<option text>"}`. Reason comes
  *before* choice on purpose, and `action` echoes the pick as text: small models sometimes
  reason toward one option and emit a different number, so an answer whose number and echoed
  text name different options is discarded (the caller defers and retries next cycle).
- The answer is parsed leniently (strict JSON, then regex, then plain-text match) and
  validated against the option list. Anything unusable → `Optional.empty()` → the caller's
  rule-based default. The model cannot pick an action that doesn't exist.
- Inference runs one request at a time, either against the offline llama.cpp subprocess
  (loaded at server start with a warm-up generation, so a broken backend fails at load, not
  mid-game) or as a cloud call. Requests time out after 60s and count as "no answer".
- `/vlbrain status | load` and the developer `ask` command exercise all of it in-game.

## Every call is on record

`logs/llm.log`, beside `latest.log` and `debug.log`, holds every model call in full: the
system prompt, the few-shot turns, the user message, and the reply, with the sampling settings
and the time it took. Each request is written when it goes out and its reply when it lands,
tied together by a running number (`#123 REQUEST` / `#123 REPLY`), so a call that times out
still leaves its input on record. Calls that never reach a provider (not ready, deferred
behind a player's conversation, background queue full, LLM disabled) are one `SKIPPED` line
each with the reason, because from the game "the village never decided" and "the village was
never asked" look the same.

Every entry carries a **lane** (which queue it rode: `chat`, `decide`, `background`,
`villager-chat`, `judge`) and a **purpose** the caller wrote in a few words: `Quzzar -> Jasper
Ferguson`, `what Mangrove's Edge builds next`, `who takes the miner post at Emberstead`, `a
persona for Birdie Hull`. Every public entry point of `LlmService` takes the purpose as its
first argument, and nothing reaches a provider except through `LlmService.callProvider`, which
is what makes the record complete (the persona judge, which builds its own cloud provider, goes
through it too).

The file is its own rolling appender (`LlmCallLog`), attached to the live log4j configuration
at startup because a mod cannot ship a log4j config: it rolls at 20 MB and keeps five
generations, and none of it repeats in the console. If the appender cannot be installed the
same text goes to the main log at debug level, so a logging failure never costs a call its
record. The main log keeps its one-line summaries (`[chat]`, `decided to build`); when one of
those looks wrong, the number in `llm.log` is where to read exactly what the model was told.

## Providers and models

The model backend is a config-selected **provider** behind `LlmService` (`llm/provider/`);
callers never learn which provider answered, and all of them receive the same prompts,
few-shot example turns, and parsing. There are exactly two offline models and three cloud
services, and nothing else:

- **`llamacpp`** (default): the offline path. `LlmService` provisions llama.cpp's
  `llama-server` binary and a GGUF model and runs them as a subprocess on this machine,
  then talks to it over the OpenAI protocol on localhost (`LocalRuntimeProvider` +
  `LlamaServerLauncher`). Nothing to install, no account. Two models, chosen by the
  `LLM local model` config:
  - **`llama-3b`** (default): Llama-3.2-3B-Instruct, Q4_K_M, ~2 GB. The clear best of the
    candidates at holding a conversation in character without looping the same line, which
    is what a player notices first.
  - **`gemma-2-2b`**: Gemma-2-2B-it, Q4_K_M, ~1.7 GB. Slightly smaller and faster, talks
    nearly as well.
- **`claude` / `openai` / `deepseek`**: cloud, with the operator's API key in the common
  config (never a SERVER-type config — those sync to clients; the key never reaches logs or
  status strings, masked last-4 at most). Cloud calls run in the game process over
  `java.net.http`. DeepSeek is the OpenAI client with a different base URL. API dialect
  gotchas live as comments in the provider classes (Anthropic: `x-api-key`, top-level
  `system`, never send temperature; OpenAI: `max_completion_tokens`, minimal reasoning effort).

The binary and the model download on first server start into `<game dir>/villagelife/`
(the model under `models/`); both are cached, so later boots are offline.

### Why Llama-3.2-3B, and why Gemma stays

A twelve-turn chat per candidate measured how many distinct openings each managed — the
thing a player feels first. Llama-3.2-3B kept all twelve distinct; Gemma-2-2B ten; Qwen2.5-3B
collapsed to two, opening "Ah, &lt;player&gt;!" on nearly every turn (the "talks in circles"
failure). The four job/persona benchmark tasks and the latencies were close across all three
(~2 GB each, ~0.6–1.3 s per reply on Apple Silicon), so conversation carried the choice:
Llama-3.2-3B is the default, Gemma-2-2B the one kept alternative, and Qwen was cut.

## The offline runtime — why no setup is needed

The offline model runs through **llama.cpp**, never in the game JVM. `LlamaServerLauncher`
downloads a small platform-specific `llama-server` binary (~18 MB) and the chosen GGUF model
on first start, caches both, and launches the server as a child process; `LocalRuntimeProvider`
then speaks the ordinary OpenAI HTTP protocol to it on localhost. Nothing is installed and no
JVM flags are needed, the model's memory lives outside the game heap, and if inference crashes
it takes the subprocess, not the server.

If the binary or model cannot be fetched (offline first boot, unsupported platform), the
status turns `FAILED` with a clear detail. Villagers always fall back to rule-based logic:
every caller of `decide()` supplies the option it would have taken on its own and uses it
when no answer arrives.

**Hosting guidance:** budget the model's resident size (~2.8 GB for Llama-3.2-3B, a little
less for Gemma-2-2B) on top of the server's memory, or use a cloud provider for no local cost.
