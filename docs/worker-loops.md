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

## Roaming, fixed, and the shape in between

**Roaming by default, fixed where a job is simpler that way.** Per job, not global.

The miner is neither: it sweeps a pattern outward and downward from its work station, digging a
real shaft, treating lava and water and bedrock and wrong-tool as obstacles. A miner holding a
bucket keeps the shaft dry instead of abandoning it at a leak: liquid on the shaft walls is sealed
with cobblestone (from its own mined stock) and liquid inside the corridor is cleared, so water and
lava stop the shaft only when the miner has no bucket. The bucket is a tool, never filled or
consumed; bedrock and wrong-tool still stop it outright. When the shaft opens onto a void the miner
does not stand down at the mouth: it lays a single cobblestone foothold where its next step needs
one, carrying the descent across a block at a time. And ore that a shaft or cave wall exposes is not
left in the rock: the miner pulls the vein, capped so a rich seam is a detour and not a second
career, and plugs the holes back up with cobblestone so the wall ends solid. **That pattern with
deviation is probably what roaming really is** for most jobs, and the model has to be able to express
it. Keep the excavation as it stands; the miner works the veins its own shaft exposes, while a
prospector that roams to find caves and hunt veins is a better story still deliberately left as fog.

**The hunter is bounded roaming, not a chase.** It works a hunting ground rather than pursuing
animals wherever they wander. Pure roaming would walk a hunter arbitrarily far into danger, and
passive mobs barely respawn, so it would strip its region permanently. A hunting ground that runs
dry is the correct pressure: the village's answer is a pasture, which breeds and is genuinely
renewable.

## When there is nothing to work on

Emit a `NoResourceBookkeepingEvent`, write an entry to the worker's `PersonalLogData` as
`KIND_ISSUE`, and wander. Nothing else.

Both mechanisms already exist. The shortage feeds attractiveness, so a village that has exhausted
its forest becomes less attractive and the brain is told why. The personal log entry is one plain
sentence that surfaces in conversation, which is the foundation of emergent quests: an issue can
be resolved by anyone or no one, and there is no quest state machine.

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
