# Conversations

A village holds several kinds of conversation, and they are all the same shape:
a set of voices take turns, a transcript grows, and the talk ends when someone
takes their leave, when a result is reached, or when the turns run out. That
shape is written once, in `chat/Dialogue.java`, and every conversation the
village drives itself runs on it. The pattern is the one Aaron keeps reaching
for: put a villager (or two, or the brain and a villager) in a situation, let
them talk, and let the way out be a valid answer.

## The conversations

| Conversation | Voices | Who drives the turns | Ends on |
| --- | --- | --- | --- |
| Player and villager | a villager, a player | the player types each turn | screen close, or `"done"` |
| Villager and villager | two villagers | the model | `"done"`, a fight, drift, or a busy lane |
| Quartermaster and brain | the quartermaster, the brain | the model | a partition that validates, or the round cap |
| A couple naming their household | the two betrothed | the model | a valid household name, or the turn cap |
| A villager naming a new pet | a villager | the model | a valid name, collar, and coat, or the turn cap |

The first is **reactive**: a human supplies each turn, so there is no loop to
run. It is not driven by the engine. It shares the single-turn core instead
(`PersonChatDispatcher.converse`: assemble the briefing, ask the model, read the
structured reply), which is the seam that already existed between player chat and
villager chat. The other four are **autonomous**: the village drives every turn
itself, and those are what the engine unifies.

## The engine

`Dialogue.run(protocol)` runs a conversation to its end and completes with its
result, if it reached one. The engine owns only the loop: it cycles the voices,
grows a `Transcript`, caps the round count, chains the turns on their futures,
and returns. It never reaches a model or touches the world.

What a voice says, and what a reply means, is the caller's, expressed as a
`Dialogue.Protocol<R>`:

- `voices()` and `maxTurns()`: how many take part, and the hard ceiling that
  stops a conversation the model never ends.
- `takeTurn(speaker, transcript, lastChance)`: produce the next turn. This is
  where the protocol builds the prompt, calls the model on whatever lane it
  chooses, reads the answer, runs any effects, and says how the turn ended. The
  future may complete on any thread; a protocol hops to the server thread inside
  for anything that changes the world, and the engine's loop hops with it.

A turn ends in one of four ways (`Dialogue.Turn`), and the engine does the
corresponding thing:

- **CONTINUE**: the speaker said their piece; record it and pass to the next voice.
- **LEAVE**: the speaker took their leave; record the farewell and end with no result.
- **RESOLVED**: the conversation reached the result it was for; end with it.
- **ABORT**: the turn gave nothing usable (a dead model, a pair drifted apart); end with no result.

`run` never completes exceptionally. A turn that throws or fails ends the
conversation with no result, exactly like a model that had nothing to say, so a
caller's own fallback always stands. This is the LLM-required design applied to
a whole conversation: nothing is forced, and a quiet model costs a deferral, not
a crash.

`R` is the result type: a `ShelvingPlan.Outcome` for the quartermaster, a chosen
surname (a `String`) for a marriage naming, a pet's chosen look for a pet naming,
and `Void` for a social talk that only ever ends, never resolves.

## The callers

- **`chat/VillagerConversation.java`** (villager and villager). The lifecycle
  around a talk stays here: choosing partners, keeping both standing, speaking
  each line aloud in earshot, the pacing budget, and closing with a summary each.
  The turn loop is now the engine's; the `Exchange` protocol supplies each turn
  by calling `converse` and reading its reply (`give`, `opinion`, `fight`,
  `done`). See [villager-conversations.md](villager-conversations.md).
- **`village/QuartermasterPlanner.java`** (quartermaster and brain). The
  specialized work is unchanged: propose a slot-by-slot partition, validate it,
  and lay the shelves out. Its `Shelving` protocol expresses the correcting
  rounds as a conversation, so a reply is a turn and a settled plan is its
  resolution. The last permitted turn lays out whatever grouping stands rather
  than sending it back again.
- **`relationships/MarriageNaming.java`** (a couple naming their household). The
  caller that earned the engine its keep: a genuinely new kind of conversation
  dropped onto it with no change to the engine. The brain convenes the two
  betrothed, they talk it over as themselves, and the way to end is a valid choice
  of surname. See [marriage.md](marriage.md).
- **`village/PetNaming.java`** (a villager naming a new pet). A single-voice
  caller: one villager, just given a companion dog or cat, decides its name,
  collar, and coat, and the first valid choice ends the talk. The look is
  constrained to the sixteen dye colours and the species' coat variants and
  validated against them, the same guardrail the marriage name uses. See
  [companions.md](companions.md).

Player-to-villager chat is not a caller: it runs its own reactive packet loop
(`PersonChatDispatcher`) and shares only the single-turn `converse` core.

A player handoff goes straight from the villager's slots into the player's
inventory. Only the part that does not fit becomes a targeted item at the
player's feet, so the giver cannot immediately collect the gift again. Physical
pickups observed by a villager remain in `PersonalLogData`, but the chat prompt
now interleaves those events with the timestamped session transcript. The model
sees “I said I handed over dirt,” then “I picked up dirt,” in the order those
events occurred instead of receiving pickups as a detached system-summary fact.

## The one-shot siblings, left alone

Not every question the village asks the model is a conversation. A one-shot
structured decision (`LlmService.decide` and `choose`: pick one of these
options, or every one that fits, and say why) is a single call with no transcript
and no turns, and it is already one shared primitive, used by the build planner,
job assignment, the bank, the craft and stash offers, a pet owner's sit-or-recall
call, and the marriage blessing itself ([llm-brain.md](llm-brain.md)). Folding that into the conversation engine
would help neither; a conversation is the turn-taking kind, and the decision is
the single-shot kind. They stay separate on purpose.

## Adding a conversation

A new autonomous conversation is a new `Dialogue.Protocol`:

1. Decide the voices and the result type `R`.
2. In `takeTurn`, build the prompt (read fixed facts once, when the talk is
   convened, so a turn reads no live entity state), call the model on the right
   lane, and read the reply.
3. Return a `Turn`: `spoke` to continue, `leave` to end open, `resolved` to end
   with the result, `abort` when the turn gave nothing.
4. Give the caller a fallback for the empty result, so a quiet model never
   blocks the thing the conversation was deciding.
