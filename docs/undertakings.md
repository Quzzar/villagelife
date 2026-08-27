# Undertakings

A villager's tracked matters: things they are seeing through, good or bad, with
a beginning, some progress, and an end. One generic system used for everything
from "make it up to me by bringing wheat" to "help me clear the lava in my mine"
to "I am saving toward a bigger house."

This is the design. The code does not exist yet; this doc is the shape to build
to, and the first slice to build first.

## Why, and what is already here

A villager can already **log** that something happened. `PersonalLogData` holds
`KIND_PICKUP` entries (an item the player threw them) and `KIND_ISSUE` entries
(one plain sentence: "I was attacked by Edmund"). But a log entry is inert. It
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
  String summary,          // one sentence, the villager's own words
  Optional<UUID> withWhom, // the player or villager it concerns, if any
  List<Milestone> steps,   // ordered progress markers; may be empty
  int progress,            // index into steps, or a 0..100 count when stepless
  Origin origin,           // SELF, EVENT, or PLAYER — who raised it
  long openedDayTime,      // level.getDayTime() when it began
  long updatedDayTime,     // last time it moved
  Optional<String> resolution // one sentence, filled when it ends
)

Milestone(String text, boolean reached)
```

- **Valence** is not the same as state. A NEGATIVE undertaking that RESOLVES is
  a wrong made right; a POSITIVE one that ABANDONS is a hope let go. The two
  axes are independent and both feed mood and conversation.
- **Milestones** are optional. "Bring ten wheat" is stepless: `progress` is a
  0..100 measure (0, then 100 on delivery). "Build the new house" has ordered
  steps and `progress` is the index of the last reached one. One record serves
  both; a stepless undertaking just has an empty `steps` list.
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
3. **The player's actions** advance and resolve, but do not directly create —
   the player's part is to move a matter a villager or an event raised.

## The LLM tool contract

Today a chat reply is a JSON object `{"say": ...}` with optional `"give"` and
`"opinion"` (see `PersonChatContext.RULES`). Undertakings add one optional
field on the same object, so the model's contract grows by one tool, not one
schema:

```json
{ "say": "...", "undertaking": { "op": "open|advance|resolve",
                                 "summary": "...", "valence": "positive|negative" } }
```

- `op: "open"` starts a new undertaking with the villager as author (Origin
  SELF, or PLAYER when it plainly arose from the player's request). `summary`
  and `valence` are required; `steps` may be given or left for later.
- `op: "advance"` moves the villager's current open undertaking with this player
  one step, or sets a milestone reached.
- `op: "resolve"` ends it.

The same guard discipline as `give` and `opinion` applies: validated
server-side, never trusted raw. An `op` the model invents, or a resolve with no
matching open undertaking, is dropped the way an out-of-range opinion is
clamped. The model proposes; the server decides.

Crucially, the model does not track progress itself — that is the bug the
chat-repetition work already taught us about small models. The **server** owns
the count. "Bring ten wheat" advances when the player hands over wheat, detected
by the game, not when the model claims it did.

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
raises standing; an ABANDONED positive one (a promise broken) lowers it. This
routes through the existing opinion/standing system rather than inventing a
second currency.

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
4. The player hands over wheat (the existing give-detection, in reverse — the
   server notices the item arriving). The server advances `progress` toward 100.
5. At ten wheat, the server resolves it, fills `resolution`, and raises the
   player's standing through the opinion system. The villager, next chat, is
   square with them and says so.

This slice exercises every part of the generic model once — open, advance,
resolve, valence, origin, the tool, the chat surface, the standing effect — so
the shape is proven before it is generalised to the rescue and self-goal cases.

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
