# Villager requests

A villager can put a request to the village brain: "we should build a wall",
"let us save for a farm", and the reason why. The brain weighs it and still
decides. This is how one villager's local knowledge, a raid on the road or a
hungry winter, reaches the collective judgement that chooses what the village
builds next.

> **Status (2026-08-30): simplified to pure context, not yet verified in live
> play.** The system was cut down to "a request is raw context the brain reads"
> (see below), compiles, and is wired end to end, but the loop has not been
> watched run in-world, which needs a dev-server restart. Treat the behaviour
> below as intended, not confirmed.

## The invariant: propose, do not dispose

A villager is never the brain. A request does exactly one thing: it argues its
case, in the villager's own words, in the **situation** text the brain reads.
The same `LlmService.decide(situation, options)` call then makes the pick from
the menu the planner always offers, exactly as it does with no request present.
A villager literally cannot choose. It states a reason the brain may weigh, and
nothing more. This is enforced structurally, not by prompt wording, which keeps
"the villager is not the brain" true even when the model misbehaves.

A request is plain free text. It need not name a building at all: the brain
reads it and maps it to its own options itself, so there is no code that parses
a subject, matches it to a building, or ranks it. Many villagers asking for one
thing simply reads as many lines in the situation, and that volume is the
emphasis.

### Consequence: a request is context, and the menu is the whole field

Because a request no longer adds anything to the menu, the brain can act on one
only when the building it names is among the options it is offered. As of the
menu change on 2026-08-30 that is the WHOLE vetted field: every legal, affordable
build and every reachable save-for goal, each labelled with what it would give
(see [llm-brain.md](llm-brain.md)). So any request that names something the
village could actually build or work toward is on the table for the brain to
weigh. Only things that are illegal, unaffordable for good, or have nowhere to go
are absent, and those could not be acted on anyway.

## The loop

1. **File.** When the player urges a village direction in chat ("you should
   build a wall"), the villager is offered a gated `request` tool and may emit
   `{"request": {"subject": "walls", "reason": "wolves at the fields"}}`. The
   tool is mutually exclusive with the undertaking tool: a small model
   over-reaches when shown two optional fields at once (see the undertaking
   audit), so a village-direction turn shows `request` and stands the
   undertaking tool down. Questions about housing or construction receive the
   village's current planning facts without opening the tool. A direct urging
   (`VillageChangeIntent.proposes`) opens it, including plural language such as
   "we need houses" or "we could use more housing". Ordinary talk does not.
2. **Queue.** `PersonChatDispatcher.applyRequest` records it through
   `VillageRequests.add`, a persisted queue living in the brain `strategy` tag
   beside the goal (`VillageGoal`). The same villager cannot stack the same
   subject twice; different villagers asking the same thing are all kept, because
   that repetition is the signal.
3. **Weigh.** At the brain's next decision, `UrbanPlanner.appendRequests` lists
   every standing request raw in the situation text, exactly as its villager put
   it, and the brain weighs them against the options it is already offered.
   Nothing is grouped, matched, or added to the menu.
4. **Hear back, implicitly.** There is no accept/reject bookkeeping. A request
   simply stands until it ages out (`REQUEST_LIFETIME_SECONDS`) or the queue
   fills. The loop the player opened still closes on its own: the villager's chat
   briefing already states what the village is building or saving toward, so if
   the brain acted on the idea, the requester naturally sees it and can speak to
   it.

## Guardrails

- One structured tool per turn (above), so the request field is never dangled on
  a turn that does not warrant it.
- An illegal or unaffordable building can be requested but never chosen: the
  brain only ever picks from the affordable and reachable pools the planner
  already vets, and a request cannot add to them. This mirrors the brain never
  being shown an unaffordable building ([llm-brain.md](llm-brain.md)).
- The queue is capped (`MAX_REQUESTS`) and pruned by age, and a villager cannot
  stack the same pending ask twice.

## Scope

The first version is **chat-driven** origination and **build/priority requests
only**, per the decisions taken with Aaron. Deferred: villagers filing requests
autonomously from their own situation (a raid, a hunger), and placement requests
("move the gates here"), which would first need the brain to expose placement as
a decision it records rather than the deterministic wall/gate system it is today.

## Code map

- `village/VillageRequests.java`: the free-text `Request` record, the persisted
  queue, age-based pruning, and the per-requester duplicate guard.
- `village/buildings/UrbanPlanner.java`: `appendRequests`, which lists the
  standing requests raw in the situation the brain reads.
- `chat/VillageChangeIntent.java`: separate topic and action gates for planning talk.
- `chat/PersonChatContext.java`: `RULES_REQUEST`, its few-shots, and the current
  per-village option catalogue shown during planning talk.
- `chat/PersonChatDispatcher.java`: the `request` field on `Reply` and the
  `applyRequest` write path.
