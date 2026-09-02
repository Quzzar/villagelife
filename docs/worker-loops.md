# Worker loops: what a villager actually does

**Decided, and NOT implemented.** Nothing in this document exists in code: the tree still
branches per occupation into 28 hand-written goal classes, which is the arrangement this
design replaces. There is no verb enum, no job-definition datapack, no per-job tuning, and
the "nothing to work on" contract is not wired at any work goal (a lumberjack with no trees
simply wanders, silently). Treat every present-tense sentence below as intent.

Resolves [What a worker actually does](https://github.com/Quzzar/villagelife/issues/48)
on the [building and village progression map](https://github.com/Quzzar/villagelife/issues/47).
This is the layer everything in [building-spec.md](building-spec.md) sits on: the catalog says a
lumberjack gives `LOGS`, and this says how.

## Everything is physical

**A worker does real work on real blocks and carries real items.** A lumberjack fells a tree,
takes the logs, replants, and the sapling grows on Minecraft's own schedule. Nothing is granted
on a timer, and nothing is simulated abstractly.

The one exception, named and bounded: **a building's recipe and the blocks its structure contains
are different lists.** The recipe is a cost gate; the structure is placed as authored. A village
center's bell is not in its recipe but exists in the built result. That is the only place the mod
invents matter, and it exists so nobody has to author a recipe listing all 400 blocks of a town
hall.

### Nothing teleports

**An item moves between a chest and a worker only in that worker's own hands** (decided
2026-09-01). For a long time this held only for the honest few - the builder gathering a
recipe (`GatherStep`), the gatherers hauling their take home (`HaulStep`), the quartermaster
shelving (`ConsolidateStep`) - while every consuming loop pulled its materials out of village
storage from wherever its worker stood: the mason's cobblestone, the cook's raw meat, the
herder's wheat, the wall-builder's timber all crossed the village instantly. The economy was
honest (items existed, were debited once, and conserved), but the fetch trip was theater the
simulation skipped.

Now every consuming loop walks the same walk the builder always did, on one shared set of
mechanics (`PackLogistics`): find the nearest chest holding what the pack still lacks, carry
it in the pack, do the work, and carry the product back to a chest with room. A chest visit
serves both directions at once - finished goods are set down first, which is what frees the
space the next load comes out of. Loops that spend supplies away from any chest (the herder's
breeding wheat) run a `FetchStep` ahead of the work: a provisioning trip that fills the pack
to a working level so one walk covers many spends.

What this buys is the same thing every other physical rule buys: the walk is visible, the
worker can be met on the road, killed and robbed on it (materials in a pack are dropped on
death, exactly like the builder's recipe window), and a distant chest is genuinely worth less
than a near one, which makes storage placement matter. What it costs is pathing: a fetch trip
can fail the way any walk can, and a loop that cannot reach a chest waits rather than
conjuring - which reads, correctly, as a village whose storage is badly placed.

Two knowing remainders, kept deliberately for now: **bedtime provisioning** (workers pulling
their role's gear, torches, and seeds at the end of the day, plus the silent bedtime crafts)
still draws from stores at a distance, pending a design for a morning provisioning round that
does not send villagers walking in the dark; and **village-scale acts** (site-preparation
fill, market trades) are the village acting as an institution rather than any one worker, and
carry no fetch trip to skip.

### A chest of their own

Built 2026-09-01. A house's chest is not village storage: a building definition lists it under
`personal_containers`, it never joins the village's container list, and it belongs to whoever
sleeps in that building, shared between them (`village/PersonalChest`; the rule, and which
workplaces get one, are in [building-spec.md](building-spec.md)). What goes in is decided at
bedtime, in character: a villager with a chest of their own is asked once a night which kinds
of what they are carrying home to keep, any number or none, rather than hand it all back to the
stores (`entities/StashOffer`, the third personal decision in [llm-brain.md](llm-brain.md), over
`LlmService.choose`, the multi-pick sibling of `decide()`). The briefing names the pack and what the
chest already holds, calls the chest a small one for keepsakes and says what a store is for: the
village lives on what its workers bring in, and anything held back at home is lost to its work. It
does not say who shares the chest (2026-09-02): introduced as "shared with X and Y" the chest read
as shared storage, and a whole flock's wool went home night after night as "personal supplies".
The job's kit (any tool, and what the restock hands out: torches, the bucket, the sponge) is never
on the list at all, since the miner once kept theirs and the shaft flooded while the bucket sat
in a barrel at home. Silence keeps nothing, so a mute model costs the village
no goods. Kept items stay in the pack, the bedtime stow skips them, and `StashAtHomeGoal` walks
them home and sets them down in the chest by hand ahead of sleep, so nothing teleports here
either. The camp circle's chest works the same way for the four who sleep there. A chest that cannot be
reached, is gone, or is full gives the trip up, and the next stow returns the goods as before.
The chat briefing tells a villager what their chest holds (when its chunk is in sight) and who
they share it with, so they can speak to it. Watch for `keeps the ... for their chest at home`
and `put N ... away in their chest at home` in the log.

## Three verbs

A job is an **ordered array** of three verbs, any length, any order.

| Verb | Does |
| --- | --- |
| `SELECT` | Produces a target: a block, an entity, a chest, or the worker's own station |
| `TRAVEL` | Consumes a target, walks to it |
| `ACT` | Does the thing: chop, break, kill, craft, place, take, store, heal |

Deposit and fetch are not phases. They are `SELECT` a chest, `TRAVEL` to it, `ACT store` or
`ACT take`. That collapse is the point: the difference between a lumberjack and a blacksmith
becomes the length of an array rather than a class hierarchy.

```
lumberjack   SELECT tree        TRAVEL  ACT chop
             SELECT chest       TRAVEL  ACT store
blacksmith   SELECT chest       TRAVEL  ACT take
             SELECT bench       TRAVEL  ACT craft
             SELECT chest       TRAVEL  ACT store
builder      SELECT chest       TRAVEL  ACT take
             SELECT site        TRAVEL  ACT place
cleric       SELECT hurt person TRAVEL  ACT heal
```

The blacksmith's walk to the mine's chest falls out of this for free rather than being bolted on.

**Derived from the code, 2026-08-27.** All 30 goal classes were read to check this design against
what actually exists ([the derivation](https://claude.ai/code/artifact/fd012989-aa95-4656-882d-24145f7a8aa7)).
The three verbs hold. Three corrections came out of it:

- **Scope is 11 classes, not 28.** Only 11 of the 30 goals are work at all. The rest are eating,
  sleeping, fleeing, strolling and fighting; they are not worker loops and stay as they are.
  (First counted as 13: `ArmorerRepairPersonArmorGoal` and `SearchForItemsGoal` are registered on
  EVERY villager rather than gated on an occupation, so getting your armour mended and picking up
  litter are personal needs rather than jobs.)
- **`ACT` has six kinds in the code, and one is missing above.** `BREAK` (destroy a block, take
  drops), `PLACE` (put a block down, paying stock), `APPLY` (consume an item to change a target that
  survives), `CONVERT` (items to items on a timer), `CARRY` (move items between inventories), and
  **`ADVANCE`** — attend a site and tick a named subsystem, which is what `WorkOnBuildingGoal` already
  does in 111 lines whose act is a single call into `StructureInProgress`. `ADVANCE` belongs in the
  verb list rather than being discovered later: unnamed, every awkward job gets forced into `BREAK`
  and `PLACE` until the framework is three verbs plus twelve special cases.
- **The value is in `SELECT` and `TRAVEL`, not in `ACT`.** Each act is ten to twenty lines and
  correct. The surround is duplicated thirteen times and is where every worker bug has lived: nine
  byte-identical copies of `shouldInterrupt()`, three goals with no `canContinueToUse` (the farmer
  freeze), five that call `stop()` from `tick()` where it cannot end a goal, two that never navigate
  at all, and only six carrying the stranded-worker recovery. Build the loop skeleton first; the
  verbs are the easy half.

**Built, 2026-08-27.** All eleven are now steps on a shared skeleton
(`entities/ai/goals/work/`). 1,492 lines of hand-written goals became 1,141 lines of steps plus a
137-line `WorkLoopGoal` written once.

`WorkLoopGoal` owns TRAVEL and the whole lifecycle, and its `canUse`, `canContinueToUse`, `start`,
`stop` and `tick` are **final** - a step cannot make the bugs the derivation found. It supplies only
`select` and `act`, plus tuning the job definitions are meant to carry as data: reach, act cadence,
scan interval, speed, and whether the work carries on after dark.

Two variants earned their place during the port rather than being designed in advance:

- **`WorkStep<T>` is generic over what a target is.** The cleric follows a patient who walks about
  while being treated, so the loop asks the step where its target IS every tick rather than being
  handed a position once. `BlockWorkStep` covers the nine steps whose target is a place.
- **`actWhileTravelling`**, because laying a path is the one act that belongs to the journey rather
  than the destination.

Four defects fell out of doing it, all of them the surround rather than any act: bone meal never
walked to its work and could not end; the lumberjack never removed the log it felled, printing
timber forever; the miner's cursor could loop unbounded over a cave and hang the server; and the
path-layer finished a route by calling `stop()` from inside `tick()`, which ends nothing, leaving
the builder holding the movement flag until nightfall.

**Prior art agrees, twice.** All four surveyed mods converge on acquire, travel, act, deposit
([research](https://github.com/Quzzar/villagelife/issues/52)). More usefully, Millenaire's own
9.0 rewrite collapsed 53 goal classes into 25 plus **one data-driven goal covering 405 work types
through 20 handlers**. Same author, same problem, a decade apart, moving toward data. Class per
job is the thing that loses.

## Wandering is the fallback, not a verb

A worker whose array has nothing to do right now wanders near its workplace. That covers the
cleric with nobody hurt, the innkeeper with no patrons, the merchant with no customer, the guard
on patrol, **and** the lumberjack whose forest is felled, with no special case for any of them.
It is the same behavior the idle camper already has.

## Not every role is a work loop

Of 29 roles in the catalog, **27 are jobs** and run on the three verbs. Three sit outside and
should stay outside:

- **`GUARD` and `SOLDIER`** are reactive, not cyclical. They already have their own goals: melee,
  ranged, shield, defend-others. Do not give them a work array.
- **`LEADER`** is not physical work. It marks that the brain has a voice.

**Built, 2026-09-01: guards do not sleep.** Whether a job beds down is a fact of the job
(`Occupation.sleepsAtNight()`), and the guard's answer is no: they stand watch through the
night. A sleepless job still keeps its bed and passes the housing gate in `JobClaiming`
unchanged; only the lying-down is skipped. Bedtime is also when the village hands out gear
and rations, so the watch runs the same stow-and-restock at their post instead
(`NightWatchRestockGoal`, sharing `goToBed`'s cadence), and a bell ring restocks a guard
without walking them to bed. The village center's guard slot is intended as the **guard
captain**, the founding twin of the barracks captain station above; the rename and any
captain-specific behavior are not built yet.

**Built, 2026-09-01: a villager who cannot get home is brought home.** Unslept nights are
counted at daybreak for every villager, whatever goal held them through the night; after three
the villager is set down at their bed, or beside the campfire when they have none, and the count
starts again so a villager who still cannot rest is brought back after three more. Three give-ups
in a row reaching their work, or real harm (suffocation, lava, drowning, the void), still bring
them to the village center at once. Before this the count lived inside the sleep goal and the
recovery required a bed, so the wedged villagers it was written for were exactly the ones it
never reached.

`DRILLMASTER` is cut. A **captain** replaces it: a station in the barracks that a guard occupies,
giving nearby guards equipment priority and a rally point. It needs no new verbs, and it explains
why a barracks beats scattered watchtowers.

## The builder builds, and between builds it makes the village walkable

The BUILDER has two duties, not one. The first is construction: preparing a site, then
placing the chosen structure block by block. The second runs whenever there is no project to
work on — the builder picks two of the village's buildings and walks between them, turning
the ground it crosses into dirt path, leaving crops and saplings alone. Paths are therefore
not planned or designed. They wear in along the routes a builder actually walks, which is
why they thicken between the buildings the village has most of and never appear at all in a
village that has only just been founded.

This is deliberately the low-priority half of the job: a village with something to build is
always building it, and a village with nothing to build is tidying itself. It also means
paths are the visible sign of a village that has caught up with its own plans.

**Built, 2026-09-01: the idle builder grades the ground first, and wears paths second.** A
village on a hillside gets level footprints and rough ground between them: two-block steps a
villager cannot climb, the notch where a site was cut in, the ledge where one was filled up.
So between projects the builder now grades the ground around the buildings toward a surface
with no step taller than one block (`GradeStep`, priority 7, above `PathStep` at 8). One
reading of the ground yields a plan, the height every column should stand at; the builder
works it nearest column first, digging high ground out a block at a time into the pack and
raising low ground with the same spoil, so the earth from one side of a slope is the fill on
the other: dirt where there is dirt, mud in a swamp (the first live village to grade sat in a
mangrove swamp and dug nothing but mud), sand in a desert. When only filling is left and the
pack holds nothing to fill with, the builder fetches dirt from a village chest by hand, and
shelves the spoil in one when it piles up; a village with no dirt anywhere raises the ordinary
shortage. When the plan is spent the ground is read
again, and a reading that finds nothing ends the job. Planning first matters: a plan is one
walkable surface, so working it makes one, whereas re-reading after every block (the first
cut of this) chased a target that moved with each block and left dips on steep ground. One job
strings many blocks together (the loop's target moves, like the cleric's patient), which is
what keeps path-laying from twitching the builder toward a random building between blocks.

Where each column should end up comes from one reading of the whole area (`GradingSurvey`):
the buildings' footprints plus an eight-block margin, capped at 96 blocks across. Ground the
village has claimed stands at its building's plane and is fixed; natural rock (the
`villagelife:firm_ground` tag: stone, terracotta, sandstone, ice) is fixed too, being both the
shape of the land and beyond a builder's hands; soft ground (the `villagelife:gradeable` tag:
dirt, grass, sand, gravel, clay) moves; everything else is not ground at all: water, trees,
anything a village or player built, and any block neither tag names. Rock used to be
"whatever is left", and the first live swamp village taught why that is wrong: a mangrove's
stilt roots read as a rock ledge four blocks up, and the builder set about raising the mud
around every tree toward them. Neighbours that differ by more
than four blocks are not connected: a cliff is a feature. Each movable column is aimed at the
middle of the one-step envelope over the ground, clamped to what the fixed columns allow, so a
slope is spread rather than pushed uphill or down and the graded ground actually meets a
building's floor. The rule that keeps this to the surface of the land and not its shape
([site-selection.md](site-selection.md)) is enforced against a per-level record of every graded
column's original height (`GradedColumnStore`): no column ever stands more than three blocks
from where it started, and none is moved back across its own starting height, so grading always
ends. Ownership is the felling rule's neighbour ([block-ownership.md](block-ownership.md)): a
village-placed block is never cut and nothing under one is; a player's placed dirt is graded
like any other, and its record is dropped when it is dug.

## The farmer's idle hands feed the composter

The farmer gets the same treatment: a field that is all still growing used to mean pure
wandering, and now the wait is a production chain, run as four separate steps so any link
also stands alone (`entities/ai/goals/work/`):

- **Clear brush** (`ClearBrushStep`): wild grass, ferns and flowers within twelve blocks of
  the station come out of the ground and into the pack. The worker pockets the plant itself,
  as shears would take it, because grass's own loot table mostly drops nothing and would
  starve the rest of the chain. Only compostable kinds are pulled, read from the compost
  data map so modded plants join in for free; crops, saplings and dead bushes are left alone.
- **Compost** (`CompostStep`): carried brush is fed to the composter every farm structure
  ships, one plant per fill at the block's own compost chance, vanilla's 7-to-8 cure and
  all. The one liberty is at the end: the bone meal goes straight into the pack rather than
  being tossed on the ground for anything passing to steal. The composter also eats surplus
  sowing seeds, anything past the eight per type kept for planting, which replaced the
  abstract seeds-to-bone-meal craft the farmer used to run: one composting system, on the
  real block.
- **Shelve and fetch** (`StashBonemealStep`, `FetchBonemealStep`): bone meal nothing in the
  field currently wants goes into the farm's own barrel; when something is growing again and
  the pack is empty, it comes back out. The barrel is the farm's fertiliser shelf, the same
  one the bedtime restock draws on, which is what makes the chain legible from outside and
  lets each half work alone with whatever else stocks that shelf.
- **Feeding itself** stays the existing `BonemealStep`.

The chain sits deliberately below every real farm task: harvest, till and the crafts all
outrank it, and within the chain conversion outranks gathering, so a farmer finishes what
they carry before pulling more. A fourth source feeds the same shelf at night: when the
bedtime restock leaves the pack short and the village stores hold bones, the farmer's own
brain is offered a grind through the shared `CraftOffer` press
([llm-brain.md](llm-brain.md)), so skeleton drops end up as crops too.

## The idle camper tends the fire

Idle residents get the same treatment as the farmer's idle hands, on the campfire model rather
than a workplace. An idle person who finds raw food in the village stores cooks it at the town's
own gathering-point campfire and returns it (`CookStep`, a `BlockWorkStep`): the raw item really
roasts on the fire via `CampfireBlockEntity.placeFood`, and the step owns the timing so the
cooked food is lifted straight into storage rather than dropped on the ground when the block's
own cook tick would finish it. What counts as cookable is read from the vanilla
`CampfireCookingRecipe` set, so it is broader than the butcher's six hand-listed meats and
modded food comes along for free.

This is deliberately scoped to idle campers as an early-camp bridge. A young camp has no
butchery, so raw meat a hunter brings home would sit uncooked; once a butchery exists its
`BUTCHER` cooks the same meats at its station and simply drains the shared stores first, so the
fireside quietly matters less with no explicit hand-off. It sits at the bottom of the idle
priority order, below defence, eating and sleep, and its `select` returns nothing when the
stores hold no raw food, so an idle camper with nothing to cook just wanders as before.

## Roaming, fixed, and the shape in between

**Roaming by default, fixed where a job is simpler that way.** Per job, not global.

The miner is neither: it sweeps a pattern outward and downward from its work station, digging a
real shaft, treating lava and water and bedrock and wrong-tool as obstacles. A miner holding a
bucket keeps the shaft dry instead of abandoning it at a leak. A flooded stretch of the ramp is
work she walks down to, and standing at it she bails the whole connected pocket at once: every
flooded ramp cell is cleared and every liquid source off the ramp, wall, floor or ceiling, is
plugged with cobblestone from her own mined stock, so an aquifer becomes a cobbled tube. (Clearing
one cell at a time from wherever she happened to stand, which is what this did first, lost the race
to the water flowing back between picks and read as a miner ignoring her bucket.) Water and lava
stop the shaft only when the miner has no bucket. The bucket is a tool, never filled or
consumed; bedrock and wrong-tool still stop it outright. When the shaft opens into a cave the miner
does not stand down at the mouth: it completes the shaft's missing floor, one cobblestone (from its
own mined stock) under each ramp cell that opens into void, laid standing on the floor already
there, edge by edge, and drives the shaft on into the stone beyond. The pack is the budget: a miner
out of cobblestone logs it and waits at the mouth until restocked, so a cavern too large to floor
ends the shaft. Torches are hung the way a player hangs them: at head height on the shaft wall, one
wherever the light at the miner's feet has fallen to dim (about every twelve blocks of ramp),
and only once the shaft is deep enough to be dark. Every pick re-walks the shaft from the mouth, an audit that costs a block read per
open cell, so a cell an interruption skipped or gravel refilled behind the miner is dug on the next
pass. Ore is taken from the shaft's own walls, floor and ceiling around the miner, the corridor's
full width from either side, and followed into the rock only through the holes she opened, never
out into a cave. And ore that a shaft or cave wall exposes is not left in the rock: the miner pulls the vein,
capped so a rich seam is a detour and not a second career, and plugs the holes back up with
cobblestone so the wall ends solid. **That pattern with deviation is probably what roaming really
is** for most jobs, and the model has to be able to express it. Keep the excavation as it stands;
the miner floors a cave rather than exploring it and works the veins its own shaft exposes, while a
prospector that roams to find caves and hunt veins is a better story still deliberately left as fog.

**The hunter is bounded roaming, not a chase.** It works a hunting ground rather than pursuing
animals wherever they wander. Pure roaming would walk a hunter arbitrarily far into danger, and
passive mobs barely respawn, so it would strip its region permanently. A hunting ground that runs
dry is the correct pressure: the village's answer is a pasture, which breeds and is genuinely
renewable.

**Built, 2026-08-31: the hunter shoots, and the pen is not game.** Hunting is done with a bow
at range, walking closer only when something blocks the shot. The baseline bow arrives with the
job, like the guard's stone sword, and plain arrows are never counted: that is the second place
the mod invents matter, bounded the same way the structure-block exception is. Special arrows
stay physical and finite: tipped or spectral arrows in the hunter's hands or pack are loosed
first and genuinely consumed, and a better bow in the stores is picked up by the ordinary
bedtime gear pass. Village stock is marked farmed (`FarmedStock`) and a hunter does not see it
as game: the tag is set on the animals a structure spawns with, inherited at birth when either
parent carries it, and applied by the herder to whatever it tends, so a butchery pen standing
inside a hunting ground is safe, and a wild cow the herder adopts stops being quarry. Hunters
also fight now: the occupation weighs into both combat checks, so they defend themselves and
take on nearby monsters with the same bow, where before a hunter fled like a baker.

**Built, 2026-09-02: the pen has a size, and the butcher keeps it.** Nobody used to thin a
butchery's pen: the herder is non-lethal by design and the hunter ignores farmed stock, so a
butchery with wheat in store bred until the animals stood wall to wall and the herder and
butcher, whose stations are both inside the fence, could not reach the door. The herd is now
two numbers (`FarmedStock`): the pen keeps six of each kind, and the BUTCHER slaughters
whatever stands above that, nearest grown animal first, one blow each so nothing bolts
wounded through a crowd (`CullStep`); the herder keeps breeding below its own ceiling of
twelve, which is only ever reached by a pen with no butcher. So every calf the herder's wheat
buys is, once grown, meat and hide, and the pen holds a steady six of each while the wheat
lasts. (A first cut thinned only a kind that had reached twelve, in cycles; that would have
bred a pen that already trapped its people up to twenty-four before the first slaughter, so
the butcher works from six up.) The butcher steps onto the
drops to gather them, cooks the raw meat from the pack (the cook loop takes what is in hand
before it fetches), and carries the leather, feathers and wool to a chest once the round is
done. Villagers still cannot open fence gates; the pen's people leave through the building's
own door, which the cull keeps clear.

**Built, 2026-08-31: lumberjacks and guards clear nearby woodland; whole trees, and never the
village's own.** The lumberjack's planted stand remains its reliable, renewable source of work,
but an idle lumberjack also ranges out to fell natural trees, and a quiet guard does the same
only rarely. The scan follows the worker: it sweeps a radius around wherever they
currently stand, not a fixed post, so as a guard patrols or a lumberjack roams they clear the
trees ringing the village rather than only those at one spot. Both reach twelve blocks: the
guard's chop is a fresh camp's only wood until it can afford a lodge, and at six blocks a
camp's guard found no tree at all and the village sat on its first lodge goal for four hours.
The lumberjack scans more often and accepts the work more readily (twenty blocks and a
two-in-five roll every five seconds, against the guard's twelve blocks and one in four every ten
seconds; the guard's roll was one in twenty until 2026-09-01, which left a fresh camp waiting a
quarter of an hour per tree for the lodge it could not yet afford); combat goals outrank the
guard's chopping.

**Reach is measured from the eyes, not the feet (2026-09-01).** A tree is cut from beside its
trunk or from beneath it: the worker stands within arm's length of the trunk horizontally, and
the lowest log has to be within an axe's reach of their eyes, a player's block reach of four
and a half. Mangrove trunks stand on stilts three to seven blocks over the mud, and with the
loop's flat arrival test a fresh camp's guard at Mangrove's Edge walked under every tree it
picked, gave up, stood down and was eventually sent home: not one log in an hour. Two more
things a mangrove taught the scan. The base is the lowest log of the whole connected tree
(`TreeFelling.treeLogs`), not the first log below the one scanned: a mangrove is all branches,
and walking straight down stops at a log in the canopy with leaves beneath it. And before a
tree is offered, the scan asks the navigator for a path to a standing spot beside its base
from which the axe reaches; the worker then walks to that spot, not to the log, and a tree
with no such spot (the tallest stilts, a spot only on top of the leaves) is left rather than
walked to and given up on. `WorkStep.inReach` is the seam, taking the target so a step can
look past the position it walks to: the loop's default is still the flat distance, and the
chop is the one step that answers differently.

**What the felled wood pays for.** A recipe names oak because the catalogue does, but the
village pays in kind: a cost naming a log is met by any log (`village/buildings/Materials.java`,
the one place that rule lives), so a swamp camp's mangrove pays for its house. The planner's
tally, a goal's shortfall, the builder's gathering and the commit that spends the recipe, the
wall's draw and the market's reserve all ask that rule rather than comparing items. Everything
else is exact.

Both roles run the same `ChopStep`. A worker strikes one log for a chop's worth of ticks, and
when it gives the whole connected tree comes down at once through `TreeFelling` (shared with
building placement, [site-selection.md](site-selection.md)): a bounded flood fill over its logs,
all of it into the pack, with the leaves left to decay on their own. Two guards keep the axe off
anything but a wild tree. A candidate must have a natural canopy nearby, leaves whose
`persistent` flag is false, which a building's timber and a player's placed leaves never carry.
And every log is checked against [block-ownership.md](block-ownership.md), which vetoes anything
a player placed. The village's own claim deliberately does not protect trees: a village founded
in a forest starts with claimed ground full of them, and the canopy test is what tells a tree
from a building. So a wild tree overhanging a roof drops its own wood and spares the building's.

The guard carries a stone axe rather than a stone sword, keeping the apple ration in the other
hand, and upgrades through the village's axe supply at bedtime.

## When there is nothing to work on

Emit a `NoResourceBookkeepingEvent`, write an entry to the worker's `PersonalLogData` as
`KIND_ISSUE`, and wander. Nothing else.

Both mechanisms already exist. The shortage feeds attractiveness, so a village that has exhausted
its forest becomes less attractive and the brain is told why. The personal log entry is one plain
sentence that surfaces in conversation, which is the foundation of emergent quests: an issue can
be resolved by anyone or no one, and there is no quest state machine. For as long as the worker
keeps logging it, it also stands in the brain's build briefing as a fact with its age
(`UrbanPlanner.appendWorkplaceTrouble`, [llm-brain.md](llm-brain.md)), so a dead mine is
something the brain knows about when it weighs a second one.

**The job is never freed.** Returning the worker to the campfire would thrash the whole labor pool
every time a radius empties.

**Not reaching the work is its own case, and it is not the same as having nothing to do.** A
villager at the bottom of their own village's mine has a job, materials and a destination, and
simply cannot walk there. The navigator never calls this stuck, because a path that was never
found cannot stall, so a worker in that state stands still forever holding a job nobody else
will take. A worker who walks without ever getting closer therefore says so in their own log,
lets go of the work so everything else gets a turn, and after repeated failures is brought back
to the village center — the same recovery a lost villager already gets.

This is one implementation, not four: the builder, the miner, the lumberjack and the worker
carrying a haul home all hold the same watch. Standing down is also a gate on whether the
goal may start again, which is the half that makes it work — a goal that gives up and is
immediately re-entered has not given up, it has only changed how it spins.

**Two work loops turned out not to walk at all.** Harvesting and tilling act on whatever is
within reach of wherever the villager already happens to be, so a farmer never sets out for
their field: they work it only when ambient wandering has left them standing in it. Nothing
can strand them because nothing is trying to take them anywhere. That is a gap in the loop
rather than a bug in it, and it is the clearest argument in this document for the verb array
above — a job that has to say "walk to the field" cannot forget to.

## Rate, night, and unloaded chunks

- **Cycles stay slow and real.** A fell-replant-grow cycle takes what it takes. A village that
  wants more wood hires another lumberjack. That is the pressure the whole economy runs on, and
  compressing output would remove it.
- **A villager walks toward one thing at a time, and work outranks wandering.** Goals that
  navigate compete for movement rather than running side by side. This is not a detail: every
  work goal used to run simultaneously with ambient strolling and with each other, each
  issuing its own destination between placements, so villagers made slow progress toward
  everything and arrived at nothing. Slow cycles are a design choice; a worker who never
  reaches the work is a bug.
- **Work stops at night.** Per job, with exceptions: guards patrol, an innkeeper stays open.
  Night work is a field on the job definition, not a global rule.
- **Unloaded chunks freeze.** A village only lives while someone is watching. Its brain waits
  too: a village whose ground is not loaded does not plan its next building, because no site
  can be found in chunks that are not there and the decision would cost a model call for an
  answer nobody could act on.

**Note the disagreement on that last one.** Both actively maintained 1.21.1 references refuse to
freeze and force-load instead. We are choosing differently on purpose: abstract simulation
contradicts "everything is physical", and force-loading every village is a performance trap. It
is a real cost and it is worth revisiting if villages feel dead on return.

**Freezing is not a future decision. It is already what happens, and it has been measured.**
Villagers in chunks that are neither force-loaded nor near a player do not tick at all. Proved
by using potion effect durations as a tick counter: a villager in an ordinary village held at
2400 ticks unchanged while one in a force-loaded village burned 505 ticks in 25 seconds.

So the choice recorded above is really a choice *not to add* force-loading, and the
consequences are live today:

- A village the player walks away from produces nothing and ages not at all.
- Anything that needs to be observed running needs a force-load or a player standing in it.
- Any benchmark of villager cost must control for this or it measures nothing.

## Where a job is defined

A **job-definition datapack file** keyed by occupation, loaded exactly like
`BuildingDefinitionLoader` (a `SimpleJsonResourceReloadListener`, so a second loader is a copy of
a file that already exists).

It carries **tuning as data and behavior as a named id**: roaming or fixed, search radius, works
at night, cycle length, what it targets, what it produces, plus the id of a registered behavior.
Pure data was considered and rejected: the miner's excavation pattern, the farmer's
plant-wait-harvest states, and the fisher's waiting are not expressible as a target and a verb,
and forcing them into data produces a config language nobody can debug.

## Finding targets, and the budget

**Per-worker scanning first.** It is a fraction of the code, and if it holds up the shared index
is complexity we never pay for.

The alternative stays designed and unbuilt: a **village-level shared target index**, one scan on
the village's existing phase-staggered slow tick, building a work queue that workers claim from.
It makes twenty lumberjacks cost roughly what one costs and gives the brain a readable "how much
wood is in reach" number for free.

**Measured, and the answer is that we were budgeting the wrong thing.**

[The benchmark](https://github.com/Quzzar/villagelife/issues/62) ran, and per-worker cost is not
where the risk is:

| | Cost |
| --- | --- |
| One villager entity | ~0.086 ms per tick |
| An idle wanderer | under a microsecond, effectively free |
| **One village ticking** | **~0.4 ms per tick, with P99 spikes to 38 ms at only eight villages** |

So **village ticking dominates and villagers are cheap.** The original budget, ten villages of
fifteen workers under 2 ms, framed this as a per-worker scaling question. It is not. Fifteen
workers cost about 1.3 ms; the eight villages they live in cost more, and spike far worse.

Two consequences:

- **Per-worker scanning is confirmed, and the shared target index should not be built.** It
  optimises the cheap half. Leave it designed and unbuilt, as recorded above.
- **The real scaling work is per-village**, in whatever runs on the village tick: attractiveness,
  the planner, job claiming, site scoring. That is where the P99 spikes come from and where a
  future optimisation pass belongs.

## What a worker chooses to gather: the village's shopping list

**`SELECT` reads what the village is short of.** A worker prefers targets that yield a
material the village currently needs, and falls back to its ordinary loop when it needs
nothing or nothing is in reach. Only `SELECT` changes; `TRAVEL` and `ACT` are untouched.

Today every `SELECT` is blind. `WorkInMineGoal` takes the next block down and outward from
its station and nothing else, so a village that needs sand for its stoneworks will watch its
miner dig straight past a sand bank to keep the spiral tidy. The worker is busy and the
village is stuck, which reads as the mod being broken rather than the village being poor.

**The shopping list already exists and needs no new machinery.** Three pieces are in place:

- the brain picks a target building from options it can already see
- that building has a `cost`, a flat list of items and counts. Every one of the 69 shipped
  definitions now carries one ([building-spec.md](building-spec.md))
- `hasItemStackInVillage` already measures stock against a recipe, against real container
  contents

So "what are we short of" is a subtraction over things the code computes anyway. Nothing has
to be invented to know a village needs 38 sandstone; it is the difference between the current
build target's recipe and what is in the chests.

**This is a ranking, not a new scan.** The worker still walks its own per-worker scan and
still respects the same budget; needed materials simply sort first among the candidates it
already found. That matters given [the benchmark](https://github.com/Quzzar/villagelife/issues/62):
village ticking dominates and workers are cheap, so a preference order costs nothing worth
measuring, while a second village-level "who needs what" index would land squarely on the
expensive half.

**Read the list on the worker's own cadence, not per tick.** The brain's target can change
between decisions, and a worker that re-reads it constantly walks halfway to three different
things. It re-reads when it finishes a cycle, which is also when it would pick a new target
anyway.

**Failure is already contracted.** A miner who cannot find sandstone within its radius hits
the existing "nothing to work on" path: a shortage event, one plain sentence in its own
`PersonalLogData`, and wandering. That sentence, *"I cannot find sandstone for the
stoneworks"*, surfaces in conversation and is the seed of an emergent quest, which is a far
better outcome than a silent stall. The shortage also feeds attractiveness, so a village
that cannot reach what it needs becomes measurably less attractive and the brain is told why.

**Bounded by the same rules as everything else.** Working radius still grows with the
settlement, so a camp cannot send its only miner across the world for one block of sand.
Demand changes what a worker prefers, never how far it will go.

**Two sizes, and they are not the same job.** The narrow version consults the current build
target's unmet cost inside `WorkInMineGoal` and prefers those blocks: a small, contained
change to one goal class. The real version is this section as written, and it only exists
once the three verbs do. The narrow one is worth doing on its own terms and does not block
the rewrite, because the ranking it encodes is the same ranking `SELECT` will want.

## Reviewing what gets built

`/vldev village gallery [pos]` places every loaded building definition on labelled plinths, grouped
by category and level, so a whole catalog can be walked end to end. Built for reviewing candidate
structures ([structure-sourcing.md](structure-sourcing.md)) and checking content passes.

## The quartermaster shelves by plan

*2026-09-01: the dialogue has converged live on Llama-3.2-3B (one round, four item types over
27 slots); the automatic trigger shipped the same day.* The quartermaster
already sweeps every workplace chest into the storehouse (`ConsolidateStep`, a CARRY loop). On
top of that it now organises the storehouse to a **shelving plan**: a partition of the
storehouse's numbered slots into named categories, each owning a contiguous run of slots and
the items that live there. The tidy pass lays the shelves out to match, with no model in the
loop.

The plan is built by a conversation, not by rules. The storehouse's slots are numbered across
its chests as one slot space (chest one is slots 1 to 27, chest two 28 to 54, and so on; what
sits where today is not mentioned, since the tidy re-lays every chest anyway), and the model is
asked to assign every slot to one category
and every item to one category, dividing the slots however it likes, across chests or not. Each
good is listed with its count and the slots that takes, the creative-inventory tab it sits in (Building
Blocks, Ingredients, Food & Drinks...) and its item tags (logs, c:ores...): the groupings the
game already knows, handed over as facts for the model to use or ignore, since a small model
guessing from the name alone shelved the list in thirds by list order. Which shelves to keep
and what to call them stays the model's call; nothing sorts by tab or tag itself. It does the
slot arithmetic itself; a deterministic validator then checks the
partition (every slot covered once, no gaps, no overlaps, every item placed) and, when it does
not hold, hands the errors back. The quartermaster and the village brain alternate turns
correcting it, up to six rounds. The first partition that validates wins. Valid means sorted, not
just summed (2026-09-02): with four or more kinds on the shelves a single group over everything is
rejected and sent back like any other problem, the correcting turn is told to keep the groups
given and fix only what was listed, and a correction that merges everything anyway is set aside
for the earlier grouping, because two plans in one night ended as "All Items, slots 1 to 81" once
one group had passed the arithmetic. The rounds are for
the grouping, though, not the sums: once the grouping holds (every item in some group, no
group empty, no number that is not an item; a repeat stays with the first group to name it),
a partition whose slot numbers still overlap or leave gaps is not sent back again; the
shelves are laid
out from those groups by count (each group a contiguous run sized to the stacks it holds, the
spare slots shared out in proportion), because arithmetic is bookkeeping, not a decision, and
Llama-3.2-3B got it wrong six rounds running on a real 81-slot storehouse. Out of rounds with
the grouping still incomplete, the last grouping given is laid out the same way, with the goods
it never named on an "Odds and ends" shelf. Only a run that never parsed a single group ends
with no plan, and then the shelves keep the order they had, never a corrupted one. Two
lessons from Llama-3.2-3B shaped the wire format: replies are read one group object at a time,
because a dropped bracket or a note tucked inside the array used to throw away whole rounds,
and the reply shape is described in words rather than shown as a worked example, because the
example's groups came back copied into the plan verbatim. A third: the goods' numbers and
the slots' numbers share one reply, and the model once filled a group's items with slot
numbers, which with sixteen goods passed as "every item placed"; an empty group, or a number
that is not an item, is now an error it hears about, never a plan. Membership
is a frozen item-id map (the model placed each item by hand), so tidy-time resolution is an
exact lookup, and an item the plan never saw spills to a free slot and waits for the next
re-plan.

Design decided over chat: fully generative categories, a two-role brain/quartermaster dialogue,
rebuilt on the first storehouse and when new items pile up. The trigger lives in the tidy pass.
Whenever the quartermaster settles in to mind a stocked storehouse, they redraw the plan if
there is none yet, if the storehouse has changed size, or if the shelves hold goods the plan
never placed. One dialogue at a time and at most one a day, so a day's new arrivals are planned
together and a model that cannot converge is not asked again every quiet spell. A settled plan
is stored, and the next quiet moment shelves to it in person. `/vldev llm plan <quartermaster>`
runs the same dialogue on demand, prints the result and the quartermaster's note, and applies
it at once; watch the `[quartermaster]` log lines for the round-by-round convergence.

- `village/QuartermasterPlanner.java`: the iterate-until-valid dialogue and the validator.
- `village/ShelvingPlan.java`: the slot partition, persisted in the brain's strategy tag.
- `village/Storehouse.java`: the shared storehouse chests, slot flattening, and plan execution.
- `entities/ai/goals/work/ConsolidateStep.java`: the tidy pass that applies a plan or, with
  none, falls back to ordering like goods together.

## Still open

- **Resource depletion** ([#54](https://github.com/Quzzar/villagelife/issues/54)): trees replant,
  ore does not. What a mined-out village does, and whether the mine is allowed to be a fiction.
- **Where output goes** ([#49](https://github.com/Quzzar/villagelife/issues/49)): per-building
  chests or one pool. Note the research found a third answer neither option covered: MineColonies
  uses a **priority ladder** where the worker's own building resolves above the warehouse, and the
  player is last.
- **The prospector**: a miner that roams to find caves and hunt veins rather than sweeping a pattern.
  The bounded half is built: the miner pulls the veins its own shaft and cave walls expose, capped and
  backfilled. Free-roaming prospection, going out of its way to find ore, is what stays fog.

## Depletion, range, and what the land looks like afterwards

Decided on [#54](https://github.com/Quzzar/villagelife/issues/54). Workers harvest real
blocks, so a village genuinely consumes its surroundings, and these are the rules that stop
that ending badly.

**The mine deepens; it never runs dry.** As the ore within reach is taken, the miner
extends the shaft downward and outward rather than idling or producing from nothing. A
Minecraft world is effectively infinite downward, so the mine is inexhaustible in practice
without ever violating the rule that nothing spawns items. It also means an old village's
mine is a real hole you can climb into and read: this is how far they got, over how long.

**Underground is exempt from the surface rule.** See
[site-selection.md](site-selection.md): a village never reshapes what you see, and may dig
whatever it likes beneath it.

**Working radius grows with the village.** A camp works what is around it; a city ranges
much further. Range scales with the settlement rather than being a single global number, so
a large village visibly influences a large area, and a small one does not send its only
lumberjack half a kilometre into the wolves.

**Trees are replanted; nothing else is tidied.** Lumberjacks replant what they fell, so
forests persist and wood is genuinely renewable. Everything else stays exactly as the
village left it: the stump field, the old quarry face, the mouth of the shaft. The
landscape becomes a record of what this village did and for how long, which is legible in a
way that self-repairing terrain is not.
