# Village redevelopment

Implemented on 2026-09-04; behavioral validation is recorded below.
The village can make room for a new building or an upgrade by dismantling
specific buildings it owns. A blocked footprint can become a choice with consequences instead
of disappearing from the planner's options. The initial salvage value is 50% of recorded construction investment.

The default small local model is the design target. Passing a benchmark with a larger cloud
model does not establish that this works for the default player experience.

## What exists today

The planner can expand a building around its existing footprint, or construct most higher
levels on a separate site at full cost. Another building's claim prevents an expansion.
Blocked upgrades can now produce exact redevelopment proposals. Fresh redevelopment is
searched after an ordinary site search has recently failed for that footprint. Each proposal
names its target, placement and buildings to remove, and the planner offers it alongside ordinary
construction, saving and waiting. A failed or malformed model reply can only fall back to
ordinary construction.

The advanced labor setting `Allow village redevelopment` enables this path (default true).
It controls new proposals and saved goals; turning it off does not abandon a committed project.
The existing build cooldown applies. There is no autonomous standalone demolition action.

Village centers are a special case: a village cannot build a second center elsewhere, and
only level-1 center assets currently exist. Authoring the higher centers is a separate task.
Existing house, farm, and storehouse upgrades can exercise redevelopment before halls exist.

## Give the model a complete choice

The game determines the target, exact site, source building if upgrading, and every blocking
building that would be removed. It calculates the material refund, remaining material cost,
lost and gained beds, workplaces and storage, and what capacity remains during construction.
The model chooses among those complete proposals, ordinary construction, saving, and waiting.
It does not invent the demolition list, do subtraction, or promise a future replacement that
the game has not planned.

Use a short action label and facts in the same order for every proposal:

- **Build:** target and whether it is an upgrade or a separate building.
- **Remove:** named buildings, with counts and distinguishable locations when needed.
- **Gain:** additional beds, workplaces, storage, and implemented capabilities.
- **Lose and retain:** displaced residents and workers; capacity before, during, and after.
- **Recover and pay:** material quantities recovered, net material cost, and current shortfall.
- **Sequence:** any replacement work required before removal and the construction interruption.

For example, an illustrative farm-removal proposal might state "four farms become two;
two farmer posts disappear". That does not claim the surviving farms produce enough food:
staffing, operating blockers, and observed production matter too. If production or completion
time has not been measured, report the unknown instead of presenting a precise forecast.

Keep the numbered-choice contract. Validate the selected proposal against the exact options
offered, and revalidate the world before beginning removal because it can change while the
model answers. A missing, malformed, contradictory, or timed-out answer must never trigger
demolition through a first-option fallback. The existing planner fallback needs an explicit
audit when redevelopment enters the option list.

Summaries should make the consequences easy to compare without telling the model that
redevelopment is inherently better. Preserve ordinary alternatives and waiting. Equivalent
placements may share one proposal, but do not quietly trim economically different choices
just because the model is small. Compare prompt length and option-order sensitivity in tests.

## Execution must be one construction project

Removal is a prerequisite of a named construction project. The village does not independently
demolish something and then ask the model what to do with the empty land. Extend the existing
construction lifecycle, including its save/resume behavior and material commitment.

Before removal starts, the game must establish a feasible sequence with surviving builders,
a place for displaced contents, and secured construction materials. If anticipated salvage
helps pay the recipe, it must be accounted for as part of that sequence; it is not spendable
stock while the old building still stands. Removing a producer must not strand the project
waiting for materials that only that producer can make.

The conservative first implementation should withhold proposals that cannot maintain food
production or accommodate displaced residents during the work. A proposed replacement only
counts once the plan establishes how it is built first. These are provisional eligibility
rules to validate in the benchmark, not evidence that every allowed trade is worthwhile.

Only the named village buildings belong to the removal plan. Player changes, other villages,
protected world structures, unknown ownership, and terrain that remains unsuitable after
removal still prevent that proposal. Demolition does not relax ordinary terrain limits.

Stored contents are preserved in full and are separate from salvage. Test a 50% recovery of
construction materials, rounded down per material, issued once as dismantling completes.
The salvage basis must be explicit and stable across save/reload and definition changes;
current template block loot is not interchangeable with materials paid to build it. Free founding/developer buildings and older saves without payment history have no refundable
investment. Paid upgrades retain the prior investment and add their actual payment, including
accepted substitute materials. Ground restoration expenses do not become investment in the
replacement building. A structurally damaged victim yields no salvage; harvestable plants and
ordinary state changes are excluded from that intactness check. Refunded materials must not also appear as
ordinary block drops.

Removing a building must update its claim, beds, workplaces, storage registrations, ownership
records, capability cache, and site-search memory. Geometry such as a mine's shaft needs an
explicit removal policy; deleting only its surface template would leave an incomplete result.
Loss of a job or home must reconcile for residents in unloaded chunks as well as loaded ones.

## Benchmark both use and harm

Use the shipped default Llama 3.2 3B model with its actual quantization, context limit, prompt,
parser, and sampling settings recorded. Count fallback decisions separately from model choices.
Keep inference latency and invalid-response rates alongside the gameplay results.

First log real candidate proposals without executing them. This verifies that blocked sites
produce correct, understandable choices. Prompt-only trials can expose comprehension errors,
but they cannot establish the effect on a village over time.

Then run repeated timelapses on isolated copies of the same initial world state with
redevelopment disabled and enabled. Use the same game-time horizon, not only a target number
of completed builds: more demolition must not shorten a run and hide its later consequences.
Keep model settings and content fixed. Repeat each case because matching world seeds alone
does not make asynchronous simulation and model decisions identical.

Include these scenarios:

- Open land and a cheap ordinary build: redevelopment is usually unnecessary.
- A useful upgrade blocked by redundant buildings, with enough resources to finish.
- A useful upgrade blocked by essential food production, occupied housing, or the only
  workshop supplying its materials.
- A low-stock village where salvage makes a feasible project affordable.
- Full storage, a flooded or steep candidate site, and player modifications in a blocker.
- Several successive planning cycles after redevelopment, exposing repeated rebuilding.
- Save/reload during removal, a changed site while inference is pending, and a model failure.

Track the whole funnel: blocked candidates examined, feasible proposals generated, proposals
actually offered, proposals chosen, projects started, and projects completed. Record concrete
reasons for ineligibility, rejection, or cancellation. Low usage means different things at
each stage; zero choices from zero offers says nothing about the model's willingness.

For excessive use, measure removals per completed project and per village game day, the age of
removed buildings, repeated removal and replacement of the same category or parcel, material
losses, time without productive capacity, hunger, homelessness, and stalled construction.
Record net capacity and population outcomes rather than treating more upgrades as success.
Measure candidate-search tick cost separately from inference latency.

Adoption in clearly useful cases and restraint in clearly wasteful cases are both necessary.
Reject accounting errors, duplicated refunds, lost contents, or protected-block removal even
if average village growth improves. Establish behavioral acceptance thresholds from the
baseline before tuning on the same cases, and retain separate cases for the final check.

The existing construction cooldown is part of the baseline. A demolition-specific cooldown,
minimum building age, or extra penalty may help if churn appears, but none has been selected
yet. Re-run the beneficial blocked cases after tightening a rule so curing overuse does not
quietly make redevelopment unavailable everywhere.

## Implemented safeguards and persistence

`RedevelopmentPlanner` generates and revalidates proposals. The target template fingerprint,
recipe, salvage, exact block queue and ground work are saved with the choice. Any changed
victim list, price, target definition, protected edit or transition constraint cancels an
uncommitted choice. The existing source footprint and rotation remain valid for an upgrade.

A proposal also requires an observed current need: missing general beds after allowing for
existing vacancies; storage that has actually overflowed; low food with additional crop capacity
or food jobs and no unfilled food posts; idle residents needing a kind of job with no vacancy;
or a missing implemented capability. Existing `STORAGE` and food capability labels cannot
manufacture demand. This check runs again before commitment. It was added after the default
model repeatedly chose larger houses or stores even when the prompt explicitly said none were
needed. Ordinary construction remains available under its existing rules.

A proposal requires a staffed builder outside the affected buildings, enough surviving general
beds for displaced residents, and a surviving staffed food building if food production is
affected. Staffing is a minimum eligibility condition, not a guarantee of adequate output.
Village centers and mines cannot be demolition victims; mine upgrades retain their shaft origin.
Surveyed blocks and containers must be loaded. Player changes, another village's claims and
unknown structural ownership prevent removal.

Builders gather the net recipe before commitment. Contents from all affected buildings,
including the upgrade source and personal containers, must fit in surviving village storage;
contents move in full, independently of salvage. Residents are rehoused and affected services
are removed at commitment. A persisted `DEMOLISHING` phase advances one block per builder
swing, then enters ordinary preparation and construction. Removed parcels are restored with
paid dirt. Claims remain during removal and are rebuilt afterward. Ownership, jobs, beds,
containers, food records, capabilities and site memory are reconciled.

Salvage used by the recipe stays within the committed project. Surplus becomes a persisted
refund only after demolition finishes, and waits for real storage capacity. Blocks are removed
without ordinary loot. Save/reload retains the removal cursor and unpaid surplus. Later player
blocks and refilled containers pause work during demolition, ground work and construction.
A missing pending structural block also pauses removal without advancing its cursor or paying
salvage: harvesting it after commitment cannot produce both normal drops and promised credit.

`[redevelopment-search]` logs survey counts, refusal reasons, generated proposals, budget
exhaustion and CPU time. A search has a 96-survey limit and stops starting another
survey after 50 ms; an individual survey is indivisible, so cold/JIT work can exceed that budget. `[redevelopment]` logs the proposal ID, exact victims, age in game ticks
(or -1 for unknown), net cost, salvage, and offered/declined/chosen/saving/started/committed/
completed/cancelled/no-answer stages. Persisted village strategy counters preserve the funnel
across saves. These logs support investigation; a count of removals alone is not a measure of harm.

## Current validation status

The focused automated checks cover fractional recovery, actual substitute investment, free and
legacy buildings, saved amounts larger than one stack, salvage credit allocation, storage
conservation and strict model-response validation. The isolated-world lifecycle harness checks
blocked-upgrade discovery, fresh replacement, damaged-building accounting, player protection,
commitment, halfway save/reload, demolition, construction, claim cleanup and single payment.

The JVM-only `RedevelopmentBenchmark` provides model trials, a saved starting-world fixture,
and real Minecraft tick sprints with a fixed game-time horizon. It records the same default
model's decisions through `LlmService.decideStrict`, varies option order, and writes JSON results.
Prompt trials and lifecycle checks do not substitute for the enabled/disabled timelapses.
The [measured validation report](research/redevelopment-validation-2026-09-04.md) records
183 passing tests, 75 small-model trials, a successful real-builder execution and two six-day
disabled/enabled pairs. The model trials exposed wasteful choices and led to the deterministic
current-need gate. The timelapses offered no redevelopment proposals because relocation
housing was unavailable; natural adoption and repeated teardown behavior remain unproven.


The [autonomous follow-up](research/redevelopment-natural-adoption-2026-09-04.md) completed
eight twelve-day runs with the default 3B model. Two prepared enabled villages received twelve
proposal offers across eight planning calls: ten declined proposals, one saving choice that
expired, and one invalid offer reply. Neither enabled normal-founding run generated an offer.
No redevelopment started or completed, and no building was demolished. Opportunity recognition
is demonstrated; completed natural adoption and teardown frequency remain unproven. The report
records the paired outcomes, a terrain-fixture amendment, model failures and full decision traces.
