# Undertakings

A villager's tracked matters: things they are seeing through, good or bad, with
a beginning, some progress, and an end. One generic system used for everything
from "make it up to me by bringing wheat" to "help me clear the lava in my mine"
to "I am saving toward a bigger house."

> **Status: PARKED (2026-08-27).** The machinery below is built and unit-tested;
> the feature does NOT work in live play yet, and the reason is not fixable by
> tweaking the prompt. Parked pending a stronger local model or the redesign in
> the next paragraph. Do not describe this as shipped.
>
> What works: the record, persistence, `UndertakingService.apply` (open/advance/
> resolve, the open→advance coercion), the standing bump on a resolved negative,
> and the dispatcher write path are all built and pass `/vldev undertaking
> selftest` (10/10). Inspect live state with `/vldev undertaking show <target>`
> and raw model behavior with `/vldev undertaking probe <target> <line>`.
>
> What does NOT work, measured in-world (not just the isolated audit): a villager
> almost never records its own matters. Two blockers, both structural:
> 1. **The trigger is debt-shaped.** `assemble` offers the record-new tool only
>    when `opensACommitment(playerLine)` fires, i.e. the PLAYER says a commitment
>    word (sorry/promise/owe/...). A villager's own goal or problem surfacing in
>    normal talk is never offered the tool. The whole trigger was built for the
>    make-amends "first slice," not the general self-tracking that is the actual
>    purpose (the villager remembering and tracking ITS own matters).
> 2. **The 3B can't drive the decision on the full prompt.** Even when the tool is
>    forced open, on the long persona/situation prompt the model emits only by
>    mirroring a close few-shot (debt line → ~33%/wrong content; self-matter with
>    self-matter examples → 0/6). It copies, it does not judge "worth recording."
>
> The likely redesign when this is picked back up: stop asking the chat model to
> DECIDE to record. Open matters from real game events (the doc's EVENT origin —
> the villager hits lava, runs out of materials), deterministically, and let the
> model only narrate and discuss what is already tracked (the read side, which
> works). That anchors tracking in what actually happens instead of the small
> model's unreliable judgment.

This is the design. The record, its persistence, the validation-and-apply, and a
measuring harness live in `entities/UndertakingData`, `UndertakingService`, and
`UndertakingCommands`; the production chat wiring lives in `PersonChatDispatcher`.
In a conversation the model emits an `undertaking` field (gated, with the few-shot
examples in `PersonChatContext`), the dispatcher parses it, applies it, and
persists it, which closes the loop the briefing's read side (`openWith` for the
gate and the "Matters between you" section) depends on. The schema below is
locked against the code.

## Why, and what is already here

A villager can already **log** that something happened. `PersonalLogData` holds
`KIND_PICKUP` entries (an item the player threw them) and `KIND_ISSUE` entries
(legacy event memories, now written as `KIND_MEMORY`, such as "I was attacked by Edmund").
Current operational impediments are separate `KIND_BLOCKER` entries and are not undertakings.
But an event-memory log entry is inert. It
records that a thing occurred and nothing more: no state, no progress, no way
for the villager or the player to move it forward, no resolution.

What is missing is the lifecycle. When a villager the player wronged says "bring
me ten wheat and we are square," that is not a fact that happened. It is a
matter now **open** between them, that the player can **advance** by delivering
wheat, and that **resolves** when the debt is paid, changing how the villager
feels. `KIND_ISSUE` is the flat seed of this; an Undertaking is the seed grown
into something with a life.

"Issue" is the wrong word because it reads negative-only, and half of these are
positive. **Undertaking** covers both: a matter being seen through.

## The one generic model

Every Undertaking is the same record, whatever it is about. The kind of matter
is carried in fields, never in a subtype, so the system stays one thing.

```
Undertaking(
  UUID id,                 // stable handle for advancing/resolving it
  Valence valence,         // POSITIVE or NEGATIVE, how the villager feels about it
  State state,             // OPEN, ACTIVE, RESOLVED, ABANDONED
  Origin origin,           // SELF, EVENT, or PLAYER — who raised it
  String summary,          // one sentence, the villager's own words
  Optional<UUID> withWhom, // the player or villager it concerns, if any
  List<Milestone> steps,   // ordered milestones the model marks; may be empty
  Optional<String> progressNote, // words, for a stepless matter the game can't count
  Optional<String> resolution,   // one sentence, filled when it ends
  long openedDay,          // level day-time when it began
  long updatedDay          // last time it moved
)

Milestone(String text, boolean reached)
```

- **Valence** is not the same as state. A NEGATIVE undertaking that RESOLVES is
  a wrong made right; a POSITIVE one that ABANDONS is a hope let go. The two
  axes are independent and both feed mood and conversation.
- **Milestones** are optional and model-marked. "Build the new house" has
  ordered steps the model ticks off as it makes headway. "Bring ten wheat" is
  stepless: its progress is a `progressNote` in the villager's own words, which
  the model updates. One record serves both; a stepless undertaking just has an
  empty `steps` list and leans on the note. Neither is a number the game keeps.
- **Origin** records who raised it, which the prompt and the UI both use:
  SELF (the villager set it themselves), EVENT (the world created it, e.g. a
  cave-in), PLAYER (it arose from something the player did).

### State lifecycle

```
        OPEN ──────► ACTIVE ──────► RESOLVED
          │            │
          └────────────┴──────────► ABANDONED
```

- **OPEN**: named, nothing done yet. "You owe me for the theft."
- **ACTIVE**: at least one step of progress. "You have brought four of the ten."
- **RESOLVED**: reached its end. Applies its effect (see below) once, then
  becomes read-only history.
- **ABANDONED**: given up or expired. Also terminal, also historical. An
  undertaking with no movement for N in-game days abandons itself, so a debt
  the player never pays does not hang forever.

## Where they come from

Three origins, one record:

1. **The villager, via a chat tool** (Origin SELF or PLAYER). This is the
   primary path and the reason the LLM contract grows. See below.
2. **A world event** (Origin EVENT). The same events that already feed
   bookkeeping can also open an undertaking on the villager who experienced it:
   a miner trapped by lava, a builder blocked with no materials. These reuse the
   existing `bookkeeping/` event classes as their trigger.
3. **The player's actions** are what the model observes and reports on — the
   player's part is to do the thing, and the villager (via the model) notices
   and updates. The player never edits an undertaking directly.

A specific undertaking could later be wired to a game event that auto-advances
it — a "clear the lava" matter resolving when the lava is gone. That is an
optional hook for one case, layered on top, NOT how the generic system works.
The core is model-driven, and stays that way.

## The LLM tool contract

Today a chat reply is a JSON object `{"say": ...}` with optional `"give"` and
`"opinion"` (see `PersonChatContext.RULES`). Undertakings add one optional
field on the same object, so the model's contract grows by one tool, not one
schema:

```json
{ "say": "...", "undertaking": { "op": "open" | "advance" | "resolve",
                                 "summary": "...", "valence": "positive" | "negative",
                                 "note": "...", "step": "..." } }
```

This is the locked schema, and it is exactly what `UndertakingService.Op`
parses. Fields by op:

| op        | uses                | meaning                                                        |
|-----------|---------------------|----------------------------------------------------------------|
| `open`    | `summary`, `valence`| begin a matter. Both required; a blank `summary` is dropped.    |
| `advance` | `note` or `step`    | move it on. `note` is stepless progress in words; `step` marks or adds a milestone. One of the two, else dropped. |
| `resolve` | `note`              | end it. `note` becomes the resolution; a default is used if blank. |

- Which matter `advance`/`resolve` acts on is the SERVER's choice, not the
  model's: the model names no id, and the server takes the most recently touched
  open matter with this player, or a self-goal otherwise. The model only says
  *that* a thing moved, never *which record* moved.
- `open`'s origin is PLAYER when the exchange is with the player, SELF when the
  villager raised it for themselves.
- **`open` is coerced to `advance` when a matter with this player already
  stands.** Which of open/advance/resolve applies is the one call a small model
  cannot make (the audit below), and half of it is server-known: whether a matter
  stands is `openWith`, not the model's to judge. So a stray `open` while one
  stands folds into it as an `advance` (`UndertakingService.open`). The measured
  consequence, accepted for v1: at most one open matter per player-pair, a second
  promise folding into the standing one until it resolves. `MAX_OPEN` supports
  more, but the 3B cannot tell a new matter from the same one, so one clean matter
  beats three garbled. The prompt reinforces it by never offering `open` while a
  matter stands (below), so the coercion is a backstop, not the common path.

The same guard discipline as `give` and `opinion` applies: validated
server-side, never trusted raw. An `op` the model invents, an `open` with no
summary, an `advance` with nothing to record, a `resolve` with no matching open
matter — all dropped the way an out-of-range opinion is clamped. The model
proposes; the server decides.

The model drives every op, because the system is generic and the game cannot
count an arbitrary matter. The small-model risk is therefore NOT that it fails
to keep a tally — it is that it OVER-emits: opening or resolving an undertaking
on a mundane turn where nothing was undertaken, the same way the give tool once
handed out diamonds unprompted. Two guards answer that. First, the count lives
in the persisted `progressNote`, not the model's memory, so a missed turn does
not lose progress. Second, the undertaking field is only offered in the prompt
when one is plausible — there is an open matter to advance or resolve, or the
turn is visibly about a commitment — so a prompt that always dangles
"open|advance|resolve" does not invite the model to reach for it. The measuring
harness counts false positives, not just hits, because over-emission is the
failure that actually bites.

The audit (Llama-3.2-3B, `/vldev undertaking audit`) drove three findings in
sequence, each fixing the last one's residue:

1. **Over-emission, solved by gating.** UNGATED the model over-emitted
   (precision 33%); GATED it never false-fired (precision 100%, zero false
   fires). The gating line justified outright.
2. **Under-noticing, solved by few-shots.** GATED recall was only 40%: the model
   under-noticed the moments to act, for the same reason the give tool needed its
   torch example. Adding worked open/advance/resolve few-shots fixed the
   triggering: the model now reaches for the tool on nearly every amends,
   delivery, and completion turn.
3. **Op-confusion, split between server and prompt.** With the tool firing, the
   3B could not tell the three ops apart: shown all three, it defaulted almost
   everything to `open`, opening a second matter when one plainly stood. Two
   changes answer it. The server COERCES `open`→`advance` when a matter stands
   (above), removing that call from the model entirely. The prompt then never
   offers `open` while a matter stands, collapsing the model's real decision to
   the one thing the server cannot derive: has this SETTLED the matter (resolve)
   or merely moved it (advance). The advance/resolve few-shots carry that
   distinction in completion language ("four toward" vs "the last of").

The audit scores the EFFECTIVE (post-coercion) op and mirrors the live two-state
gate, so its number is what ships. Measured after tuning: precision 60-100%,
recall 60-80%, with open-firing and false-fires both fixed. Resolve is the one op
the 3B cannot reliably separate from advance (a near-verbatim resolve few-shot
still reads as advance): it sits at the model's ceiling and is shipped
**best-effort, by decision**. The failure is safe by construction. A completion
the model reads as advance leaves the matter's `progressNote` showing it all but
done while the matter stays open, so no data is lost and a later turn can still
resolve it. The one genuinely wrong outcome, false-closing a still-partial matter,
is the direction the tuning deliberately does NOT push toward, which is why more
resolve examples were not added.

Closing a matter from the player's chat text was tried and pulled. Pattern-matching
prose to confirm an event is brittle: players say all kinds of things, and a word
list both misses real completions and risks false-closing on an incidental phrase.
The reliable signal is the thing itself changing hands, not the words for it, so a
future robust close is mechanical: a deliverable matter carries what it is owed and
resolves when the villager has actually received it (the pickup log already records
what the player threw and to whom). Until then resolution is the model's own op,
best-effort, and a matter it never resolves simply lingers, which is harmless.

## How they surface

**In conversation.** `PersonChatContext.assemble` already builds the villager's
briefing. It gains a short "Matters between you" section listing this player's
open undertakings with the villager, so the villager remembers the debt without
the player re-explaining it. This is what makes "bring me ten wheat" feel real:
the next time the player walks up, the villager brings it up.

**To the player.** A villager's open undertakings that concern the player are
theirs to see. The minimal surface is the chat tab: the villager states them.
A richer surface — a small "Matters" list, perhaps a third tab on the villager
screen next to Chat and Trade — is a later slice, not the first one. The
container screen already supports tabs, so it is cheap when wanted.

**In mood and standing.** A RESOLVED negative undertaking (a wrong righted)
raises standing, and this is wired: `UndertakingService.Result.resolvedNegative()`
flags the transition, and `PersonChatDispatcher.applyUndertaking` bumps the
player's opinion by `RIGHTED_WRONG_BONUS` through the same capped `applyOpinion`
channel a chat opinion delta uses, so it routes through the existing
opinion/standing system rather than inventing a second currency, and one
conversation cannot swing standing wildly. The mirror case, an ABANDONED positive
undertaking (a promise broken) lowering standing, waits on the self-abandon path
(there is no ABANDONED transition wired yet), so it is not hooked up.

## Persistence

An attachment on the person, exactly like `PERSONAL_LOG`, `SOCIAL`, and
`CHAT_HISTORY`: `villagelife:undertakings`, a codec/NBT list capped like the
others (16 open feels right; resolved ones trim oldest-first). `KIND_ISSUE` in
`PersonalLogData` is **absorbed** by this and removed — an issue was always a
degenerate undertaking with no lifecycle, and keeping both would be two systems
for one idea, against core guideline 1.

## The first slice: making amends

The anchoring case, built end to end before anything else:

1. The player has wronged a villager (theft or violence already lowers standing
   via `wrongdoing/`). The player asks, in chat, how to make it right.
2. The villager, via the `undertaking` tool, opens a NEGATIVE, stepless
   undertaking: summary "Bring me ten wheat for the grain you took," withWhom
   the player, Origin PLAYER.
3. It persists, and the next time the player opens chat, the villager's briefing
   carries it: they mention the outstanding debt unprompted.
4. The player hands over wheat and says so in the conversation. The model,
   seeing this, emits `advance` and updates the note ("four of the ten"). The
   note persists, so next visit the villager knows where things stand.
5. When the debt is met, the model emits `resolve`, which fills `resolution`
   and raises the player's standing through the opinion system. The villager,
   next chat, is square with them and says so.

This slice exercises every part of the generic model once — open, advance,
resolve, valence, origin, the tool, the chat surface, the standing effect — all
driven through the model, so the thing the whole feature rests on (does the
model reliably emit the op at the right moment, and NOT at the wrong one) is
measured on the simplest case before the rescue and self-goal cases are added.

## Out of scope for the first build

- The rescue case (Origin EVENT) and the self-goal case (Origin SELF, no
  player), which prove the other two origins. Built after the slice validates
  the model.
- The "Matters" tab. Chat-surfacing is enough to prove the loop.
- Villager-to-villager undertakings. The model allows `withWhom` to be another
  villager, but nothing authors those yet.

## Related

- `entities/PersonalLogData.java` — the flat log this grows out of and absorbs.
- `chat/PersonChatContext.java` — the tool contract and briefing this extends.
- `wrongdoing/Standing.java` — where a resolved amends-undertaking lands.
- `village/bookkeeping/` — the event classes the EVENT origin reuses.
- [economy.md](economy.md) — the give/opinion tool pattern this mirrors.
