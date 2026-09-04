# Redevelopment validation, 2026-09-04

Implementation details and invariants are in [redevelopment.md](../redevelopment.md).
These experiments distinguish model comprehension, rule enforcement, construction execution,
and autonomous village outcomes. A passing lifecycle is not evidence of natural adoption.

## Runtime and fixtures

- Minecraft 1.21.1 / NeoForge 21.1.72, Java 21, local development runtime.
- Default Llama 3.2 3B Instruct, Q4_K_M, cached llama.cpp b10653.
- Total context 8192, two inference slots (4096 per request); decision temperature 0.1,
  maximum response 128 tokens. Production `LlmService` prompt and parser paths were used.
- Housing and storage proposals came from real templates in the isolated world, including
  exact victim locations, net recipe, lost capacity and salvage. Scenario needs for prompt
  trials were controlled inputs; those trials did not advance a village.
- Timelapses start from copies of one saved world: six residents, eight buildings, three
  farms, 384 each of logs/cobblestone/dirt/cooked beef, plus farm seed contents. Five workers
  have assigned posts and fixed fixture personas. There is a house upgrade blocked by farms.
- Each paired run advances exactly 144,000 real Minecraft ticks (six game days), using
  normal work loops, construction cooldowns, walls, population and relationship logic.
  Chunk loading is ALL; hostile spawning and village-to-village chatter are disabled in
  every copy. The model still handles planning, hiring, personas and household decisions.
- One paired repetition runs disabled then enabled; the second repeats that order. Seeds
  and initial persisted identities match, but asynchronous inference and simulation random
  state do not make outcomes deterministic. This is a small controlled experiment.

## What the small model did

Three repetitions of each case varied all option positions. Expected choices were set in the
harness before each trial set. Short action labels were copied exactly; long facts stayed in
the situation text. Full JSON trial records include chosen action, reason, latency and validity.

| Case | Initial sensible choices | With explicit guidance |
| --- | ---: | ---: |
| Useful blocked housing | 6/6 | 6/6 |
| Cheaper ordinary housing on open land | 5/9 | 6/9 |
| No need for more housing | 0/6 | 6/6 |
| Salvage makes the needed upgrade affordable | 6/6 | 6/6 |
| Held-out blocked storage shortage | Not included | 5/6 |
| Held-out cheaper ordinary storage | Not included | 9/9 |
| Held-out storage with no shortage | Not included | 0/6 |

The guided set also clarified an ambiguous housing action label (upgrade level 1 to level 2),
so its improvement cannot be attributed to prompt guidance alone.

The initial set had 27/27 valid responses and 17/27 expected choices. Explicit current-need
and net-cost guidance improved the repeated housing cases, but did not generalize to storage
with no shortage. The expanded set had 47/48 valid responses and 38/48 expected choices.
Median inference latency was 2,908 ms initially and 2,541 ms with the revised guidance.

**The final implementation therefore does not rely on the prompt to establish demand.**
`RedevelopmentDemand` requires an observed shortage, an unfilled employment need, or a missing
implemented capability. Higher levels, an existing STORAGE label and speculative future
benefits do not establish demand. The same check runs before commitment. Regression tests
exercise both no-need failures and beneficial housing/storage/crop-expansion cases.

Cost ranking remains imperfect: the model preferred the more expensive housing redevelopment
in 3/9 guided comparisons against a cheaper ordinary house. These were real housing needs,
but suboptimal choices. The game proves admissibility and reports the costs; it does not
prove that every admitted trade is economically optimal. The held-out model failures are
retained in the records rather than removed from the evaluation.

Records: [initial model trials](redevelopment-2026-09-04/model-initial.json),
[guided and held-out trials](redevelopment-2026-09-04/model-with-guidance.json). Exact synthetic
requests and raw replies are preserved in the [initial trace](redevelopment-2026-09-04/model-initial-trace.txt)
and [guided trace](redevelopment-2026-09-04/model-with-guidance-trace.txt).

## Deterministic and world checks

The final combined test suite passes 183 tests. New focused coverage includes actual
investment and substituted materials, per-building rounding, no invented legacy/free refunds,
large saved material quantities, storage conservation under partial insertion, combined
inventory capacity, and strict malformed/contradictory JSON rejection. Demand tests ensure
larger same-worker farms remain eligible when extra crop plots address low food.

The isolated lifecycle harness passed blocked-upgrade discovery, separate replacement,
last-staffed-food protection, no-current-need rejection, damaged-building accounting,
player edits, its own site reservation, material commitment, halfway save/reload, demolition,
construction, claim release and single refund. A post-commit player break with ordinary drops
pauses at the missing structural block; neither reload nor another step advances its cursor
or pays the promised surplus. Stored contents are distinct from salvage.

The two-farm housing fixture removed 182 authored/plant blocks and restored 112 ground blocks.
Its net payment was 20 logs, 25 cobblestone and 112 dirt, with 2 logs and 4 cobblestone recovered.
Landscaping dirt is excluded from the new house's refundable investment.

A separate execution fixture then loaded real residents from the saved world, added spare
relocation housing and residents needing homes, and explicitly selected and paid for the exact
blocked house upgrade. From commitment onward, a real builder performed every demolition,
ground preparation and construction step through its ordinary AI work loop. It completed in
11,559 game ticks, removed the two blocking farms, retained the third farm and preserved the
house identity. The resulting house investment was 36 logs and 48 cobblestone, excluding the
112 dirt spent on ground restoration. This proves actual worker execution; selection and
initial material gathering were fixture-controlled and are not autonomous adoption evidence.

The [execution result](redevelopment-2026-09-04/execution-result.txt),
[phase trace](redevelopment-2026-09-04/execution-progress.log) and
[external probe source](redevelopment-2026-09-04/RedevelopmentExecutionProbe.java) are retained.
The probe was compiled only into a copy of the frozen test runtime, with local model inference
disabled, and ran on its own port and world directory.

Warm five-survey searches in the small fixture took roughly 18–27 ms. Cold/JIT runs took
142–349 ms. The search stops starting another survey after 50 ms, with a 96-survey cap;
one survey and catalogue overhead can exceed the time budget. This is not a strict per-tick
latency guarantee or a large-village performance benchmark.

Cold-loading the baseline also exposed an existing nullable-Long ternary unboxing crash in
`Village.desiredLoadedChunks`; that one-line fix is included and the real cold-load run passes.

## Autonomous timelapses

All four copies completed exactly six game days (144,000 ticks), with one ordinary building
completed in each and no construction project remaining at the final checkpoint.

| Run | Ordinary building completed | Residents | Beds | Unhoused adults | Stored food per person | Buildings removed |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Disabled 1 | House | 14 | 10 | 4 | 25.3 | 0 |
| Enabled 1 | Couple cottage | 12 | 8 | 4 | 30.6 | 0 |
| Disabled 2 | Market | 11 | 6 | 5 | 34.4 | 0 |
| Enabled 2 | Well | 10 | 6 | 4 | 37.8 | 0 |

Both enabled copies withheld every surveyed redevelopment proposal because the occupied
source house had no spare general beds for relocation:

| Enabled run | Candidates examined | Feasible proposals | Offered | Chosen | Started | Completed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 5 | 0 | 0 | 0 | 0 | 0 |
| 2 | 1 | 0 | 0 | 0 | 0 | 0 |

The first search took 165.5 ms. The second stopped after its first survey at 59.8 ms because
its soft time budget was exhausted. These timings reinforce the cold-search latency caveat.
The planner emits no model-choice stage when the eligibility gate generates no proposal;
the empty persisted metrics strings in these records therefore correspond to zero offers.

**Zero removals here establishes neither model reluctance nor successful adoption: zero
proposals were offered.** Relocation protection worked, but this fixture did not test naturally
accepted redevelopment or subsequent demolition/rebuilding cycles. Removed-building age,
resource loss from repeated removal, interruption to production and cancellation rates cannot
be evaluated from these runs. No churn was observed because redevelopment never began.

Population, housing and ordinary building outcomes varied through normal model and household
decisions. Their differences cannot be attributed to redevelopment. The preserved daily
checkpoints also show that villagers remained housed unevenly despite substantial food stocks;
this feature is not a general fix for ordinary planner priorities or housing growth.

Records: [baseline 1](redevelopment-2026-09-04/baseline-1.json),
[enabled 1](redevelopment-2026-09-04/enabled-1.json),
[baseline 2](redevelopment-2026-09-04/baseline-2.json),
[enabled 2](redevelopment-2026-09-04/enabled-2.json).
Search and completion traces are retained alongside them as `*-funnel.log`.

The release checks pass for accounting, protected property, model-response validation and
real-worker completion. Natural adoption, long-run churn and economic optimality remain
unproven. A future behavioral fixture should start with a useful blocked upgrade and surplus
relocation housing, then retain several subsequent planning cycles; that is distinct from
relaxing the current housing safeguard to make this fixture use the feature.

## Reproduction and limits

`RedevelopmentVerification` is enabled only by `-Dkithkyn.redevelopment.verify=true` in an
isolated game directory and stops the server after reporting PASS or FAIL.
`RedevelopmentBenchmark` uses `-Dkithkyn.redevelopment.benchmark=seed|model|timelapse`, with
`-Dkithkyn.redevelopment.enabled=true|false` and `-Dkithkyn.redevelopment.days=6` for timelapses.
Save the seed world once, then copy it for each run. Never point these flags at a player's world.
The harness writes JSON alongside the isolated world's logs; model raw requests/replies use
the normal `logs/llm.log` trace.

Only level-1 village-center definitions/templates exist. The new placement path cannot upgrade
a center to assets that have not been authored. Legacy and free founding buildings have no
recorded investment and therefore no salvage credit. The first implementation also requires
spare housing for any affected occupants, including an upgrade source; it does not yet build
replacement accommodation as a prerequisite subproject.
