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
   `PauseForConversationGoal` holds both still, facing each other) and runs
   the turn loop: the initiator opens off a greeting cue, then each reply
   becomes the line the other answers, via
   `PersonChatDispatcher.converse(..., background=true)`.
3. **Overhear.** Each spoken line is shown to players within earshot
   (16 blocks) as a short-lived speech bubble above the speaker. A new line
   replaces the previous one, and disappears after six seconds, so ambient
   talk stays in the world instead of filling the player's chat transcript.
4. **Close.** After 4 or 6 lines (always ending on an answer), or on any
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

- `chat/VillagerConversation.java`: the driver. Turn alternation, session
  marking, earshot broadcast, capacity and cooldown, close-with-summaries.
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
- `entities/ai/goals/PauseForConversationGoal.java`: faces players and
  villager partners alike.

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

