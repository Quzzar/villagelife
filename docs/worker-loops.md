# Worker loops: what a villager actually does

**Decided.** Resolves [What a worker actually does](https://github.com/Quzzar/villagelife/issues/48)
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

## Roaming, fixed, and the shape in between

**Roaming by default, fixed where a job is simpler that way.** Per job, not global.

The miner is neither: it sweeps a pattern outward and downward from its work station, digging a
real shaft, treating lava and water and bedrock and wrong-tool as obstacles. **That pattern with
deviation is probably what roaming really is** for most jobs, and the model has to be able to
express it. Keep the excavation as it stands; a prospector that finds caves and follows ore veins
is a better story and is deliberately left as fog.

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

## Rate, night, and unloaded chunks

- **Cycles stay slow and real.** A fell-replant-grow cycle takes what it takes. A village that
  wants more wood hires another lumberjack. That is the pressure the whole economy runs on, and
  compressing output would remove it.
- **Work stops at night.** Per job, with exceptions: guards patrol, an innkeeper stays open.
  Night work is a field on the job definition, not a global rule.
- **Unloaded chunks freeze.** A village only lives while someone is watching.

**Note the disagreement on that last one.** Both actively maintained 1.21.1 references refuse to
freeze and force-load instead. We are choosing differently on purpose: abstract simulation
contradicts "everything is physical", and force-loading every village is a performance trap. It
is a real cost and it is worth revisiting if villages feel dead on return.

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

**The budget that decides between them: 10 villages of 15 workers must stay under 2 ms per tick.**

Measured elsewhere for scale: a vanilla villager costs 4 to 5 microseconds per tick, MineColonies
4 to 6, Ancient Warfare 2 25 to 65. At MineColonies-class efficiency, 150 workers is roughly
0.75 ms, so the budget is achievable and the margin is real. Benchmarking is its own ticket, not a
blocker on building the simple thing.

## Reviewing what gets built

`/villagelife gallery [pos]` places every loaded building definition on labelled plinths, grouped
by category and level, so a whole catalog can be walked end to end. Built for reviewing candidate
structures ([structure-sourcing.md](structure-sourcing.md)) and checking content passes.

## Still open

- **Resource depletion** ([#54](https://github.com/Quzzar/villagelife/issues/54)): trees replant,
  ore does not. What a mined-out village does, and whether the mine is allowed to be a fiction.
- **Where output goes** ([#49](https://github.com/Quzzar/villagelife/issues/49)): per-building
  chests or one pool. Note the research found a third answer neither option covered: MineColonies
  uses a **priority ladder** where the worker's own building resolves above the warehouse, and the
  player is last.
- **The prospector**: a miner that finds caves and follows veins rather than sweeping a pattern.
