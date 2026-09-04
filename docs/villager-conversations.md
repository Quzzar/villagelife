# Villager conversations

Two villagers can walk up to each other and talk, through the same
conversation pipeline a player uses. That one reuse is the point: because a
turn is just "a name, a UUID, and a line" to the chat system, everything the
system already does per-interlocutor now happens between villagers with no
new mechanics. They remember each other's talks (transcripts and
close-of-session summaries), hand each other items (`give`), revise how they
feel about each other (`opinion`, landing on the relationship pair), open and
advance undertakings with each other, and talk each other into filing village
`request`s. New capabilities added to the chat reply contract are picked up
by villager talk for free.

> **Status (2026-08-31): built and compiling, not yet watched in live play.**
> The loop needs a dev-server session to confirm pacing, pause behaviour, and
> line quality before it counts as done.

## The loop

1. **Seek.** `SeekConversationGoal` (priority 5, registered before strolling)
   fires now and then on a villager with nothing better to do. Work always
   outranks it: only an idle-enough villager goes visiting, though anyone may
   be visited mid-task. It picks the best-liked free neighbour within range
   (`OpinionService.opinionOf`, ties random), walks over, and hands off.
2. **Drive.** `VillagerConversation.tryStart` marks both parties as in
   conversation (the same session store a player screen uses, so
   `PauseForConversationGoal` holds both still, facing each other) and hands
   the turn loop to the shared conversation engine (`Dialogue.run`, see
   [conversations.md](conversations.md)). Its `Exchange` protocol supplies each
   turn: the initiator opens off a greeting cue, then each reply becomes the line
   the other answers, via `PersonChatDispatcher.converse(..., background=true)`.
   This class keeps the lifecycle around the loop; the loop itself is the engine's.
3. **Overhear.** Each spoken line is shown to players within earshot
   (16 blocks) as a short-lived speech bubble above the speaker. A new line
   replaces the previous one, and disappears after six seconds, so ambient
   talk stays in the world instead of filling the player's chat transcript.
4. **Close.** When either side takes their leave (`"done": true` on the
   reply, the model's own call; see "Taking leave" below), or on any
   failure, both sides summarize the session into their memory of the other,
   exactly as a screen-close does. Next conversation on a later day starts
   fresh from that memory (`ChatHistoryData.staleFor`, shared with
   `RealPerson.openChat`).

## Priorities and budget

- **LLM lane.** Villager turns ride the background queue
  (`LlmService.submitBackgroundChat`), behind player chat and village
  decisions. Nobody is at a screen waiting, so villager talk must never
  delay someone who is.
- **Turn budget.** A turn the lane cannot serve within the session timeout
  (30s) lets both sessions expire and the pair walks away mid-thought; a
  late reply is still recorded but the talk ends. Frozen villagers are worse
  than a dropped conversation.
- **Pacing.** One conversation server-wide at a time
  (`VillagerConversation.MAX_ACTIVE`), a server-wide minimum gap between
  starts (`MIN_START_GAP_MS`; the first live run chained conversations back
  to back without it, and the gap is what bounds cloud spend as population
  grows), a 5-minute per-villager cooldown, and a random gate on the goal.
  The config toggle `Villagers talk to each other` (llm section) turns the
  whole thing off, which matters on cloud providers where every line is
  billed. The dev command skips the gap, never the capacity checks.
- **A player outranks the talk.** Opening the chat screen on a villager
  mid-conversation takes them over cleanly: their session now points at the
  player, the villager conversation notices and closes (with summaries), and
  the other villager is released.

## What passes between them

- **Items**: a `give` moves stacks from the speaker's slots straight into the
  listener's pockets (overflow dropped at their feet). A move, never a copy,
  per the held-item rule. The receiver logs it as a pickup from the giver, so
  reflection later decides what the gesture meant.
- **Opinion**: the mid-chat `opinion` field goes through `OpinionService`
  for players and villagers alike (the conversation budget still caps a
  single chat's swing). For a fellow resident it nudges their lean on the
  relationship pair, so who villagers like steers who they visit, and the
  visits move who they like.
- **Requests**: a villager urging "we need a wall" trips the same
  `proposesVillageChange` gate a player does, and the listener may file a
  `request` the brain weighs (docs/villager-requests.md). Villagers can
  recruit each other's voices.

## Verifying headlessly

`/vldev llm talk <first> <second>` forces a conversation between two
villagers standing near each other (same driver, no walk, no cooldown).
Follow the `[villager chat]` start/end lines and the per-turn `[chat]`
lines; gives log as `[chat give]`, summaries as `[chat summary]`.

## Code map

- `chat/VillagerConversation.java`: the lifecycle around a talk. Session
  marking, earshot broadcast, capacity and cooldown, close-with-summaries, and
  the `Exchange` protocol that supplies each turn. The turn loop itself is the
  shared engine's.
- `chat/Dialogue.java`: the shared conversation engine
  ([conversations.md](conversations.md)). Cycles the voices, grows the
  transcript, caps the rounds, and ends on leave, resolution, or abort. Villager
  talk, the quartermaster's shelving, and a couple's naming all run on it.
- `entities/ai/goals/SeekConversationGoal.java`: the approach. Partner
  choice by fondness, the walk, the handoff.
- `chat/PersonChatDispatcher.java`: `converse(..., background)`, the shared
  session store (`markTalking`/`conversingWith`), the villager `executeGive`
  overload, `isFallback`.
- `chat/VillagerText.java`: the final prose boundary. It replaces any em dash
  with a semicolon after generation, backing up the matching prompt rule.
- `networking/VillagerSpeechPacket.java` and
  `client/renderer/VillagerSpeechBubbles.java`: the earshot packet and the
  client's six-second bubble state.
- `chat/PersonChatContext.java`: the briefing, speaker-agnostic; standing
  feelings read through `OpinionService.opinionOf`. The villager's own work in
  hand comes from what their work loop last noted (`RealPerson.recentActivity`,
  written by `WorkLoopGoal` from each step's `activity()`), stated in the past
  tense because opening a chat pauses the loop; the village's building
  programme is always stated, even as "nothing at the moment", since a gap
  there is what a small model fills with an invented project. When the village
  has found no ground for something lately, the briefing carries the room
  sentence from `Village.describeRoom` (site-selection.md), so "no flat land,
  the slope east of the fire came closest" is an answer a builder can give.
- `llm/LlmService.java`: `submitBackgroundChat`, the background lane.
- `village/buildings/VillageContextSnapshot.java`: the authoritative village
  census, housing, buildings, open work, construction, and active-blocker facts
  rendered into both resident and collective briefings. Its housing census keeps
  adult homelessness separate from pre-adult family housing: a life stage alone
  never claims that a child has a usable resident parent's home.
- `chat/ConversationMemoryPrompt.java`: durable conversation memory sees the
  counterpart's words, not the model's own earlier answers. It stores social
  recollection rather than mutable village state, which is regenerated from the
  snapshot on every turn.
- `entities/ai/goals/PauseForConversationGoal.java`: faces players and
  villager partners alike.

## Taking leave (2026-09-02)

A villager ends a conversation when they mean to, not when a counter says so. The reply may
carry `"done": true` alongside `say`, `give`, `opinion` and `fight`; the rules tell the
villager what it means (you have said what you have to say, the farewell goes in `say` on that
same reply, most replies are not the last one) and the model decides. There is no budget of
lines and no clock on the talk: the first version closed every villager-to-villager talk after
four or six lines, and Aaron called a line budget dumb, let them talk until they want to stop,
and if they go on a tangent and yap, so be it.

Honoured for a player and for a fellow villager alike. With a player, the reply packet carries
the flag, the screen shows the farewell, and ten seconds later it closes itself
(`PersonChatScreen`), which sends the ordinary close packet, so the session is summarized into
memory exactly as if the player had closed it; a further reply without the flag in that window
cancels the close, and the player may always reopen. Between villagers the driver finishes the
talk on the spot, with the same summaries. The other ends still stand, all of them safety rather
than budget: the per-turn session timeout (a busy lane), drifting out of range, death, a fight
picked, or a reply the model could not give. One consequence to know about: conversations run
one at a time server-wide (`MAX_ACTIVE`), so a long talk holds the village's gossip lane for as
long as it lasts. The log lines are `takes their leave of` under `[chat]` for a player and
`[villager chat]` for a villager.

## Fights picked in conversation (2026-09-02)

A villager may answer words with blows. The reply may carry `"fight": true` alongside `say`,
`give` and `opinion`; the rules tell the villager what it means (a real fight, weapon or fists,
everyone remembers it) and that most replies have none, and the model decides when a
provocation has crossed the line. Aaron spent a morning shouting "FIGHT ME" at Wildflower Downs
and nobody could oblige: the schema had no such action, and the only paths to a villager's
blade were the numbers, a personal opinion at or below the grudge line or the whole village's
standing at hostile, both of which words move slowly.

Honoured for a player and for a fellow villager alike, since the two conversations are the
same conversation: `RealPerson.pickFightWith` opens a quarrel, a minute long and never saved,
sets the other party as the villager's target, and quarrel target goals above the grudge and
the village verdict keep it. Between villagers the talk ends there, and the one struck answers
in kind (`RealPerson.hurt`), so a fight takes two for its whole minute. Fighters draw what they carry; a
villager with no combat occupation gets a fists-only melee goal that engages solely against
a quarrel target, so monsters that hurt them are still answered with distance, as before.
`PauseForConversationGoal` and `PanicToBedGoal` both yield while a quarrel runs, so the
villager neither stands politely still for the chat nor flees the first blow of the fight it
picked. The exchange logs `[fight]`, and the villager logs "picks a fight with".
