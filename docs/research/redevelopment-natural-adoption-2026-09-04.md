# Autonomous redevelopment experiment, 2026-09-04

Eight twelve-day autonomous runs produced no completed redevelopment. One model-selected
saving goal expired, and one later reply was rejected for changing the offered action text.
The experiment demonstrates opportunity recognition, while completed natural adoption and
teardown frequency remain unproven.

This follow-up tests the adoption gap in [the first validation](redevelopment-validation-2026-09-04.md).
The production baseline is the verified redevelopment checkpoint `8812c53`. Concurrent behavior
changes are excluded from the frozen runtime. Only the development harness and a test-local
model-port override differ; inference model, prompts, parser and sampling are unchanged.

## Protocol and recorded amendment

The initial protocol specified two repetitions of each condition, each with redevelopment
disabled and enabled, for exactly 288,000 game ticks (twelve days). Copies within each comparison
share one saved starting world. Asynchronous inference and simulation randomness are not a
deterministic replay. Eight scored runs were completed. Opportunity order is enabled 1, disabled 1, disabled 2, enabled 2;
growth order is disabled 1, enabled 1, enabled 2, disabled 2. At most two runs execute concurrently, on separate game and model ports. After the growth
comparisons finish, the last enabled opportunity run uses their freed slot while its disabled
pair runs. This scheduling amendment changes no model settings or simulation horizon.

The prepared opportunity is an established village with a level-1 field whose expansion is
blocked by redundant wells, low edible-food stocks, staffed farm posts, a surviving larger
field, homes and secured construction materials. It starts at game tick 144,000 with a completed
stone wall, representing an established settlement whose ordinary initial cooldown has elapsed.
Its starting stocks include 16 cooked beef plus 16 carrots and 16 potatoes from the farm
template, or six edible items per resident against the configured target of eight. Its exact redevelopment proposal must pass
the production survey before the seed is accepted. The settlement and duplicate wells are
controlled initial conditions, not evidence that this arrangement arises from normal founding.
The prepared extra buildings carry fixture investment records representing previously paid
construction, which makes their 50% salvage nonzero. Normal founding structures retain their
ordinary zero-investment treatment. All ordinary projects and waiting remain available through
the unchanged planner.

After the first enabled opportunity run completed, its finite cobblestone was exhausted.
The original default superflat fixture has shallow dirt over bedrock and cannot replenish stone.
That result and its matching disabled copy are retained as the shallow-flat pair. Before either
second opportunity run started, both copies were replaced with a new fixture on 64 layers of
stone above bedrock, followed by three dirt layers and grass. The same seed harness and
feasibility check were used. This second pair is an exploratory terrain variant, not an identical
replication of the first pair. Comparisons remain within each terrain; the two opportunity pairs
must not be pooled as repetitions of one environment. Ordinary-growth repetitions still use
their original shared saved world.

The growth condition begins through normal founding on generated terrain, with no manually
assigned residents, extra buildings, construction supplies or redevelopment goal. Residents
arrive and choose work through the ordinary game logic.

Every measured run uses the default Llama 3.2 3B Instruct Q4_K_M model with llama.cpp b10653,
8192 total context and two slots, giving 4096 per request. The local port alone differs between
concurrent isolated processes. Chunks stay loaded; hostile spawning and ambient villager
conversations are disabled equally. Planning, hiring, personas, relationships, material
collection, walls, construction cooldowns and work remain ordinary simulation behavior.
Entity simulation and village bookkeeping are paused during local-model startup and the scored clock starts only after it is
ready, preventing ordinary fallback choices from consuming the prepared opportunity. No harness
code selects a project, secures its payment or advances construction during a scored run.

Count surveyed candidates, generated proposals, offers, choices, starts, commitments and
completions separately. An offer is a proposal in a planning call, not a distinct lifetime
opportunity or an independent decision: one call can contain several placements, and repeated
surveys may describe the same trade. Keep explicit refusal reasons,
invalid responses, saving and cancellation stages. Retain ordinary completion counts and
daily building IDs/locations, population, homelessness, staffed food posts, food per person,
resources and construction blockers.

Natural adoption requires a model-selected redevelopment that reaches normal worker completion.
A choice without commitment or completion is reported at that stage. Continue to the fixed
horizon after any success. Repeated removal/replacement at the same parcel or in the same
category is a review trigger, not automatically a bad trade. Zero protected-property removal,
duplicate refunds or lost stored contents is the correctness requirement. Housing or food
regressions relative to the paired copy require explanation before calling a decision beneficial.
This sample can reveal concrete failures; it cannot establish a population-wide churn rate or
prove economic optimality.

## Excluded setup attempts

Before scoring, the initial food budget was reduced from 32 to
16 cooked beef after the preflight correctly counted template carrots and potatoes and found
that eight items per resident already met the food target. Those rejected seed attempts did
not call the planner and are excluded from measured results.

Startup attempts were excluded and restarted from their original seeds after
the established camp selected an ordinary fallback before the model was ready. The harness
now freezes entity simulation and temporarily withholds village bookkeeping until the model
is ready. Minecraft freezing alone does not pause the mod's server-event bookkeeping. An
interrupted startup restores the withheld village before the isolated world is saved.

## Results

All eight runs reached exactly 288,000 scored game ticks: 96 village-days in total, including
48 with redevelopment enabled. No redevelopment started, committed, completed or removed a
building. This cohort therefore does not demonstrate completed natural adoption or establish
a teardown rate conditional on using the feature. The observed saving choice is narrower
positive evidence: the model can voluntarily recognize one of the offered redevelopment trades.

| Enabled run | Surveys | Offers (planning calls) | Declined offers | Saving choices | Invalid offer replies | Completed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Shallow on | 298 | 5 (5) | 3 | 1 | 1 | 0 |
| Stone on | 298 | 7 (3) | 7 | 0 | 0 | 0 |
| Founding 1 on | 385 | 0 (0) | 0 | 0 | 0 | 0 |
| Founding 2 on | 386 | 0 (0) | 0 | 0 | 0 | 0 |

Every generated proposal in this cohort was offered. Several placements can appear in one
call, so offer counts are not independent decisions. Disabled copies generate no redevelopment
proposals. A saving goal that expired is described below even though expiration is logged by
the ordinary goal lifecycle rather than the redevelopment cancellation counter.

The next table includes ordinary construction. Population and unhoused adults show end / highest
daily sample; food is the final shared edible-stock count per resident. Starting population was
eight for prepared camps and zero for normal founding. `on` and `off` refer to redevelopment.

| Run | Completed builds | Population end / peak | Unhoused adults end / peak | Food per resident | Final active project |
| --- | ---: | ---: | ---: | ---: | --- |
| Shallow off | 5 | 14 / 14 | 2 / 4 | 2.29 | `none` |
| Shallow on | 4 | 18 / 18 | 4 / 6 | 5.72 | `none` |
| Stone off | 3 | 7 / 12 | 0 / 4 | 0.00 | `farm_plains_1:GATHERING` |
| Stone on | 4 | 18 / 18 | 5 / 7 | 6.06 | `mine_plains_2:IN_PROGRESS_WORKING` |
| Founding 1 off | 2 | 6 / 6 | 0 / 0 | 0.00 | `none` |
| Founding 1 on | 1 | 4 / 17 | 0 / 12 | 0.00 | `well_plains_1:GATHERING` |
| Founding 2 off | 3 | 4 / 4 | 0 / 0 | 0.00 | `lumberjack_plains_1:PREPARING` |
| Founding 2 on | 2 | 4 / 8 | 0 / 3 | 0.00 | `none` |

The four normal-founding runs never reached a feasible redevelopment offer. Two enabled runs
completed one and two ordinary buildings; the disabled copies completed two and three. These
small samples expose limited progression but do not establish that enabling redevelopment
caused the difference.

There were 36 logged local request failures before the scored cutoffs, including household
and storage work, and one logged fallback construction decision. The longest recorded
construction-choice reply took 59.9 seconds including queueing. The slowest candidate search took
175.9 ms. The 50 ms search budget stops starting another survey; a single cold survey can exceed
it. Per-run latencies and counters are in the JSON summary.

## Observed choice history

In `opportunity-on-1`, the first live planner offer proposed upgrading the small field and removing both
blocking wells. The preflight had shown a valid one-well placement; production search found a
different valid placement under its normal bounded search. The model declined it and upgraded
the surviving larger field to level 3 through ordinary construction.

Later, an ordinary newly built lumberjack lodge blocked a level-2 mine expansion. That proposal
was not seeded by the harness. Its first offer would have removed a lodge only 48,000 game ticks
old (two days). The model declined twice, then correctly selected saving for the exact mine
proposal. That saving goal expired without construction starting. A later response selected the
correct numbered saving option but echoed `redesign mine to level 2 mine` instead of the exact
allowed action. Strict validation rejected the reply and no demolition occurred. The final goal
was an ordinary couple cottage. These five offer events covered two distinct trades (one field expansion and repeated offers
of the same mine expansion). They yielded three declines, one accepted
saving intention and one invalid answer, with no starts, commitments, completions or removals.

This establishes spontaneous recognition of a redevelopment opportunity in this fixture. It
does not establish adoption through completed worker execution. The first run ended with no
cobblestone or dirt left in shared stock, although logs and planks remained. Its shallow geology
limits what can be inferred about the uncompleted mine goal. The young-lodge proposal is a
specific potential churn case worth retaining, but a proposal that was declined is not observed
teardown churn.

In `opportunity-on-2`, seven offered placements across three planning calls were all declined.
The first call offered removal of both wells; the later calls also offered one-well alternatives.
The model first built a blacksmith, then saved for its level-2 upgrade while citing the food
shortage. That reason did not explain how the choice addressed the immediate shortage. After
that ordinary goal expired, the model selected a level-3 upgrade of the other farm and edible
stocks recovered. An ordinary no-answer fallback built a second blacksmith. A model-selected
ordinary level-2 mine upgrade was underway at the cutoff, without demolition.

This stone-bearing run ended with 1,784 cobblestone and 138 dirt in shared stock, so its outcome
cannot be explained by the first fixture's finite stone supply. The final briefing instead
showed five unhoused adults, no general beds free and an open farmer post. Housing transitions
and the requirement to staff existing food posts constrained further eligible proposals.
The enabled village ended with more food and residents than its disabled copy, but five adults
were unhoused versus zero in that copy. No redevelopment was executed, and these outcomes do
not establish a beneficial causal effect of offering the feature.

The next behavioral check should retain the failed action-echo reply and the ordinary saving
priority as regression cases, then measure completed worker adoption after any planner or
worker changes on a newly frozen baseline. These runs do not justify adding a demolition
cooldown to solve observed churn: no teardown churn was observed, and conditional overuse
remains untested. A successful future case still needs several later planning cycles to test
whether the village repeatedly tears down its own recent work.

## Interpretation limits

Daily food per person is shared edible stock, not a measured production rate or a direct hunger
count. `staffedFoodPosts` counts assigned food jobs, not continuous observed worker output.
Population declines are not classified as deaths: these observations do not distinguish
deaths, departures and other population changes. Peak homelessness is the highest daily sample,
not a continuously observed maximum. Ordinary housing and food outcomes can diverge before any
redevelopment choice, especially with asynchronous model calls, random arrivals and household
planning. This small sample does not identify the causal growth effect of enabling redevelopment.

The first pair's depleted shallow geology, the exploratory terrain change in the second pair,
local request timeouts and the limited twelve-day horizon all constrain interpretation. The
conservative eligibility gates can also leave a village with no offers. A zero-removal result
then provides little information about whether a model would overuse available redevelopment.

## Reproduction and evidence

The [manifest](redevelopment-natural-2026-09-04/manifest.json) records the frozen production
commit, harness hash, model settings, ports and starting-world hashes. Daily snapshots and
filtered event logs are alongside complete synthetic NPC model traces in the same directory.
The [summary](redevelopment-natural-2026-09-04/summary.json) separates offers, decisions and
execution from ordinary growth, and includes construction-choice reply latency, candidate
survey time, logged fallback construction decisions and all local request failures before
the cutoff. Local failures include household and storage work, not only construction. Reply latency includes queueing and contention between the two isolated local
model processes; it is not a standalone inference speed benchmark.

The development harness supports these JVM properties:

- `-Dkithkyn.redevelopment.benchmark=seed-opportunity` creates the established blocked-field
  fixture and checks its exact proposal with the production survey.
- `-Dkithkyn.redevelopment.benchmark=seed-growth` creates only normal founding structures.
- `-Dkithkyn.redevelopment.benchmark=timelapse -Dkithkyn.redevelopment.days=12`
  observes 288,000 game ticks after the local model is ready.
- `-Dkithkyn.redevelopment.enabled=true` or `false` sets the comparison condition.

Create a disposable server directory with village loading `ALL`, generation of additional
villages disabled, ambient villager conversation disabled and the default local model selected.
Run seeding with the LLM disabled, then close the server and clone its entire saved world and
configuration for both conditions. Enable the LLM in scored copies. Generated terrain uses
seed `20260904`. The stone-flat fixture uses the layers listed in the manifest. Never seed or
run this stopping harness against a player world. The test-only model-port override is needed
only for concurrent inference servers; sequential runs can use the ordinary port.

The harness was compiled against the frozen baseline and exercised by the real server runs.
Production planner, parser and construction code were unchanged during this experiment. The
183-test result belongs to the baseline validation, not a newly claimed test run for this report.
