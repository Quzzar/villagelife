# Villager requests

A villager can put a request to the village brain: "we should build a wall",
"let us save for a farm", and the reason why. The brain weighs it and still
decides. This is how one villager's local knowledge, a raid on the road or a
hungry winter, reaches the collective judgement that chooses what the village
builds next.

> **Status (2026-08-29): code complete, not yet verified in live play.** All
> four pieces compile and are wired end to end, but the loop has not been watched
> run in-world, which needs a dev-server restart. Treat the behaviour below as
> intended, not confirmed. One known rough edge is noted under "The loop".

## The invariant: propose, do not dispose

A villager is never the brain. A request does exactly two things to the brain's
existing decision, and nothing more:

1. It puts its subject on the **options** the brain is offered, even when the
   ranking would have left it off.
2. It argues its case in the **situation** text the brain reads.

The same `LlmService.decide(situation, options)` call then makes the pick, just
as it does without any request. So a villager literally cannot choose: it can
only widen the menu and state a reason. This is enforced structurally, not by
prompt wording, which is what keeps "the villager is not the brain" true even
when the model misbehaves.

## The loop

1. **File.** When the player urges a village direction in chat ("you should
   build a wall"), the villager is offered a gated `request` tool and may emit
   `{"request": {"subject": "walls", "reason": "wolves at the fields"}}`. The
   tool is mutually exclusive with the undertaking tool: a small model
   over-reaches when shown two optional fields at once (see the undertaking
   audit), so a village-direction turn shows `request` and stands the
   undertaking tool down. The gate (`proposesVillageChange`) is deliberately
   narrow, looking for building and priority language, so ordinary talk does not
   open it.
2. **Queue.** `PersonChatDispatcher.applyRequest` records it through
   `VillageRequests.add`, a persisted queue living in the brain `strategy` tag
   beside the goal (`VillageGoal`).
3. **Weigh.** At the brain's next decision, `UrbanPlanner` folds the pending
   requests in: `addRequestedOptions` puts each asked-for building on the menu
   (an affordable match joins the build options, an out-of-reach one joins the
   save-for goals), and `appendRequests` argues them in the situation. Many
   villagers asking for one thing collapse to a single weighted line, so
   consensus reads as a stronger signal rather than as repetition. A request
   that names nothing buildable (walls are a separate system, see
   [walls.md](walls.md)) still argues its case but adds no option.
4. **Settle.** On a genuine build or save-for choice, `pick` marks the matching
   requests accepted and the rest rejected. They stay in the queue, unseen, so
   the requester can be told later. A plain wait leaves them pending to be
   raised again, until they age out (`REQUEST_LIFETIME_SECONDS`).
5. **Hear back.** The requester's next chat briefing tells them once whether the
   village took the idea up (`VillageRequests.takeUnheard`), closing the loop the
   player opened. **Known edge:** the outcome is consumed while the briefing is
   built, so if that turn falls back to a canned line instead of a model reply,
   the villager will not actually voice it. Acceptable for now; revisit if it
   reads as dropped feedback.

## Guardrails

- One structured tool per turn (above), so the request field is never dangled on
  a turn that does not warrant it.
- An illegal or unaffordable building can be requested but never chosen: it only
  reaches the options through `addRequestedOptions`, which draws exactly from the
  affordable and reachable pools the planner already vets. This mirrors the
  brain never being shown an unaffordable building ([llm-brain.md](llm-brain.md)).
- The queue is capped and pruned, and a villager cannot stack the same pending
  ask twice.

## Scope

The first version is **chat-driven** origination and **build/priority requests
only**, per the decisions taken with Aaron. Deferred: villagers filing requests
autonomously from their own situation (a raid, a hunger), and placement requests
("move the gates here"), which would first need the brain to expose placement as
a decision it records rather than the deterministic wall/gate system it is today.

## Code map

- `village/VillageRequests.java`: the record, the persisted queue, consensus
  grouping, resolution, and the once-only feedback read.
- `village/buildings/UrbanPlanner.java`: `addRequestedOptions` (menu),
  `appendRequests` (situation), and the `resolvePending` calls in `pick`.
- `chat/PersonChatContext.java`: `RULES_REQUEST` and its few-shots, the
  `proposesVillageChange` gate, and the feedback line in the village briefing.
- `chat/PersonChatDispatcher.java`: the `request` field on `Reply` and the
  `applyRequest` write path.
