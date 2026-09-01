# How other mods drive villager work loops

Research for [issue #52](https://github.com/Quzzar/villagelife/issues/52), feeding the decision in
[What a worker actually does (#48)](https://github.com/Quzzar/villagelife/issues/48) and, secondarily,
[Where a building output goes (#49)](https://github.com/Quzzar/villagelife/issues/49). Part of the
[building and village progression map (#47)](https://github.com/Quzzar/villagelife/issues/47).

Four mods have already solved the problem this project is about to solve: a villager doing real work
on real blocks inside a village economy. This is what they did, what it cost them, and which parts
survive the trip to NeoForge 1.21.1.

**Source discipline.** Everything below was read from the mods' own source repositories, their own
documentation, and their own issue trackers and changelogs. Community wikis are labelled inline where
they were the only source. Claims that could not be traced to a primary source are marked UNVERIFIED.

| Mod | Version read | Platform | Maintenance |
| --- | --- | --- | --- |
| [MineColonies](https://github.com/ldtteam/minecolonies) | branch `version/1.21` | **NeoForge, MC 1.21.1** | active |
| [Millenaire](https://www.millenaire.org/downloads) | 9.0.0-beta.2 (2026-07-25), plus the 6.0.2 source for history | **NeoForge 21.1.226, MC 1.21.1** | active, mid-rewrite |
| [Minecraft Comes Alive](https://github.com/Luke100000/minecraft-comes-alive) | branch `1.21.1` | Fabric + NeoForge 21.1.234, MC 1.21.1 | active |
| [Ancient Warfare 2](https://github.com/P3pp3rF1y/AncientWarfare2) | branch `1.12.x`, 2.7.0 | Forge 14, MC 1.12.2 | last commit 2021-04-29 |

The framing in the ticket assumed MineColonies was the only live reference. That was wrong in a useful
way: **Millenaire shipped a complete from-scratch rewrite onto MC 1.21.1 / NeoForge in July 2026**
([millenaire.org/downloads](https://www.millenaire.org/downloads), which describes 9.0 as "a complete
rewrite for Minecraft 1.21.1 and NeoForge"). Three of the four mods are on our exact target platform,
so most of this is a live comparison rather than archaeology, and the Millenaire 6.x to 9.0 delta is a
free record of which mechanisms an author keeps when given a clean sheet on this API.

---

## TL;DR and recommendation

**Build one parameterized work cycle with a handful of action handlers, not a class per job. Bound the
work area to the building. Batch several actions per trip. Make production a counter transform over
real chest contents, and reserve genuine block-by-block simulation for the jobs the player watches.
Do not simulate anything while chunks are unloaded, because nobody else managed to either.**

The five findings that matter most:

1. **The four mods converge on the same cycle.** Acquire a target, travel, act repeatedly, carry the
   result somewhere, repeat. The differences are all in *who owns the target list* and *how often the
   loop is allowed to run*, not in the shape of the loop. That shape is settled prior art and can be
   adopted without further debate.
2. **Class-per-job loses to data-driven handlers over time.** MineColonies has roughly 40 `EntityAIWork*`
   classes on a shared skeleton. Millenaire 6.x had 53 hand-written `Goal` subclasses, and its 2026
   rewrite replaced most of them with **25 goal classes plus one data-driven `GatheringGoal` covering
   405 JSON-defined work types through 20 handlers**. That is the same author, same problem, one decade
   of experience apart, deliberately collapsing the class explosion.
3. **Abstract production is legitimate and already load-bearing elsewhere.** In Millenaire 9.0,
   **288 of 405 work types (71%) are `craft_*`**: an input-count to output-count transform performed at
   a designated spot inside the building. It stays honest because *storage is real chests*, scanned
   live. This is exactly the shape that `building-spec.md`'s "a building grants permission, not product"
   demands, and it is the cheapest correct answer to the blacksmith case in #48.
4. **Population is the tick budget, and no amount of clever AI code changes that.** A profiled AW2
   server showed NPC updates at 45.71% of tick time, of which the maintainer measured only about 12% as
   his own AI code: the rest was vanilla `EntityLiving`
   ([#808](https://github.com/P3pp3rF1y/AncientWarfare2/issues/808)). A LagGoggles comparison put a
   vanilla villager at 4-5 us/tick and MineColonies at 4-6
   ([#612](https://github.com/P3pp3rF1y/AncientWarfare2/issues/612)). Whatever the population cap is,
   that arithmetic sets it.
5. **Pathfinding is the failure everyone shares and nobody fixed.** MineColonies ships
   teleport-on-stuck as a supported fallback. Millenaire 9.0 ships a builder that places blocks
   remotely from up to 50 blocks away when it cannot path to the site. AW2's maintainer measured
   vanilla pathfinding giving up "even if the npc has to walk 20 blocks around the wall"
   ([#794](https://github.com/P3pp3rF1y/AncientWarfare2/issues/794)) and never replaced it. The
   campfire model's locality is a feature. Keep work close to home.

---

## 1. The work-cycle abstraction

### MineColonies: one skeleton, one class per job, two stacked state machines

`AbstractAISkeleton<J extends IJob<?>>` owns a `TickRateStateMachine` and exposes
`registerTarget` / `registerTargets(TickingTransition<IAIState>...)`
([AbstractAISkeleton.java](https://github.com/ldtteam/minecolonies/blob/version/1.21/src/main/java/com/minecolonies/core/entity/ai/workers/AbstractAISkeleton.java)).
Above it sits a second machine for the person rather than the job:

```
CitizenAIState : IDLE, FLEE, EATING, SICK, SLEEP, MOURN, WORK, WORKING, INACTIVE
AIWorkerState  : IDLE, INIT, INVENTORY_FULL, PREPARING, START_WORKING, NEEDS_ITEM,
                 DECIDE, PAUSED, GATHERING_REQUIRED_MATERIALS, + job-namespaced states
```

([CitizenAIState.java](https://github.com/ldtteam/minecolonies/blob/version/1.21/src/main/java/com/minecolonies/api/entity/ai/statemachine/states/CitizenAIState.java),
[AIWorkerState.java](https://github.com/ldtteam/minecolonies/blob/version/1.21/src/main/java/com/minecolonies/api/entity/ai/statemachine/states/AIWorkerState.java))

The generic half of the cycle is registered once in the `AbstractEntityAIBasic` constructor: safety
checks, visual state, waiting, inventory dumping, needs-item, gather-materials, restart, paused. A job
subclass only registers its own domain states. Transitions come in three flavours classified by
`AIBlockingEventType` (`AI_BLOCKING`, `EVENT`, `STATE_BLOCKING`), evaluated in that fixed order each
machine tick
([TickRateStateMachine.java](https://github.com/ldtteam/minecolonies/blob/version/1.21/src/main/java/com/minecolonies/api/entity/ai/statemachine/tickratestatemachine/TickRateStateMachine.java)).

One detail worth stealing outright: **every `AIWorkerState` constant carries an `isOkayToEat` flag**,
and `canBeInterrupted()` is just `getState().isOkayToEat()`. Interruptibility is a column in a table
rather than guard clauses scattered through the AI.

### Millenaire: priority argmax over goals, and a rewrite that split selection from execution

In 6.x, `Goal` is abstract with three mandatory methods (`priority`, `performAction`, `getDestination`),
53 subclasses, registered by string key. Selection is a flat argmax run **on goal completion, not per
tick**. State is implicit in three fields on the villager: `goalKey`, `goalDestPoint`, and `actionStart`
(zero means travelling, non-zero means working). Default `ACTIVATION_RANGE` is 3, default `stuckDelay`
10000 ms.

The gates are declarative and are the most reusable part: `townhallLimit` (stop when the town hall
already holds N of an item), `buildingLimit`, `maxSimultaneousTotal`, `maxSimultaneousInBuilding`,
`balanceOutput`, `canBeDoneAtNight` / `canBeDoneInDayTime`, and a `leasure` flag whose goals are
invalidated the instant any real work becomes possible.

**9.0 split the abstraction in two**, which is the single most instructive change in this whole
document:

- `VillagerGoal` is a **stateless selector**: `computePriority(ctx)`, `canStart(ctx)`,
  `start(ctx) -> VillagerTask`.
- `VillagerTask` is **stateful execution**: `tick(ctx)`, `isFinished()`, `stop(ctx, StopReason)`,
  `consumeProgress()`.

`GoalScheduler` then adds four things 6.x lacked: tick-based timing instead of
`System.currentTimeMillis()`, a strict two-tier priority where any real work beats every leisure goal
regardless of score, **idle exponential backoff** (`idleBackoffDelay = min(delay*2, 20)`) so an idle
villager stops re-scoring every goal every tick, and a **watchdog**: `MAX_TASK_TICKS = 6000` with no
`consumeProgress()` call force-stops the task and increments `NavigationCounters.incGoalAbandoned()`.

### Ancient Warfare 2: the worker is a generator, and the worksite does the work

This is the most different answer of the four, and the cleanest idea in the survey.

```java
public interface IWorkSite extends ITorqueTile, IBlockBreakHandler, IOwnable {
	boolean hasWork();
	void addEnergyFromWorker(IWorker worker);
	void addEnergyFromPlayer(EntityPlayer player);
	WorkType getWorkType();
```
([IWorkSite.java](https://github.com/P3pp3rF1y/AncientWarfare2/blob/1.12.x/src/main/java/net/shadowmage/ancientwarfare/core/interfaces/IWorkSite.java))

The NPC never touches a block. It walks to a worksite, stands there for `npcWorkTicks` (50), and calls
`addEnergyFromWorker`, which is literally priced against a hand crank:

```java
public final void addEnergyFromWorker(IWorker worker) {
	addTorque(null, AWCoreStatics.energyPerWorkUnit * worker.getWorkEffectiveness(getWorkType())
	                * AWAutomationStatics.hand_cranked_generator_output);
}
```
([TileWorksiteBase.java](https://github.com/P3pp3rF1y/AncientWarfare2/blob/1.12.x/src/main/java/net/shadowmage/ancientwarfare/automation/tile/worksite/TileWorksiteBase.java))

There is exactly **one** `NpcWorker` class, and its profession is recomputed from the tool in its hand
every check (hoe means farming, axe means forestry, pickaxe means mining, hammer means crafting, quill
means research). The worksite privately decides its own next action through two abstract methods,
`getNextAction()` and `processAction(action)`, where `IWorksiteAction` is a single-method interface
returning an energy cost.

The consequence is that a villager, a windmill, an RF cable, and a player right-clicking with a hammer
are interchangeable inputs. The cost is that **work cannot look different per villager** beyond an arm
swing every 10 ticks, which is precisely the legibility this project is trying to buy.

### Minecraft Comes Alive: the negative result

`VillagerEntityMCA extends Villager` and uses the vanilla `Brain` system throughout, forwarding
profession work wholesale to `VillagerGoalPackages.getWorkPackage(profession, speedModifier)`.
`goalSelector` appears only on a boss mob.

**MCA has no autonomous productive work at all.** Its only village store is `Village.storageBuffer`,
whose sole writer conjures items from a config table with population as a multiplier:

```java
double taxes = Config.getInstance().taxesFactor * village.getPopulation() * village.getTaxes() + random;
village.storageBuffer.add(new ItemStack(item, 1));
```

No villager participates and no block is touched. The `Chore` enum is
`NONE, PROSPECT, HARVEST, CHOP, HUNT, FISH`, of which `PROSPECT` has no handler at all (**MCA villagers
cannot mine**), chores must be assigned by a `Player`, and `AbstractChoreTask.tick` abandons the job the
moment the assigning player is absent. Output goes into the villager's own inventory, never a village
store. Buildings are *detected* by flood-fill from seed blocks, never built.

Roughly 740 lines of player-commanded chore code is the entire world-mutating surface of a mature,
actively maintained 1.21.1 villager mod. This is a useful negative control: MCA is popular without any
of what #48 is designing, because family simulation was the product. It says nothing about whether our
loop is a good idea, but it does say the loop is not table stakes.

---

## 2. Target finding

Four mods, four genuinely different answers, in increasing order of cost.

### Pre-indexed points owned by the building (Millenaire): zero scanning

Every villager is bound to a house and a town hall, and never scans the world. `BuildingResManager`
holds typed point lists indexed at construction time: `chests`, `furnaces`, `sources` (mine faces),
`soilPoints(name)`, `spawns`, `healingspots`, plus named singletons `getCraftingPos()`,
`getSellingPos()`, `getSleepingPos()`. The point types come from the building plan itself. A harvest
goal iterates candidate buildings, asks each for its soil points, and filters by live block state.

This is **the same model this repo already has**: `BuildingInfo` exposes `getWorkLocations()`,
`getBedLocations()`, and `getContainerLocations()` as offsets from the building origin, and
`LocationManager.getJobLocation` resolves a worker's station out of them.

Terrain-scale search is a separate, cached layer: `MillWorldInfo` keeps twelve arrays over the village
radius (`topGround`, `spaceAbove`, `danger`, `canBuild`, `water`, `tree`, `path`, and more), refreshed
chunk-by-chunk on a background thread, and is used **only for building placement**, never for
per-villager targeting.

In 9.0 the parameters became datapack JSON. `GatheringType` is a record carrying `scanRadius`,
`batchRadius`, `arrivalRange`, `maxActionsPerTask`, `actionCooldown`, `stuckTimeout`, and
`destinationBuilding`.

### One block per tick from a refilling queue (Ancient Warfare 2 farms): the cheapest correct scan

```java
protected final void updateWorksite() {
	world.profiler.startSection("Incremental Scan");
	if (blocksToUpdate.isEmpty() && hasWorkBounds()) fillBlocksToProcess(blocksToUpdate);
	if (!blocksToUpdate.isEmpty()) scanBlockPosition(blocksToUpdate.poll());
	world.profiler.endSection();
	updateBlockWorksite();
}
```
([TileWorksiteFarm.java](https://github.com/P3pp3rF1y/AncientWarfare2/blob/1.12.x/src/main/java/net/shadowmage/ancientwarfare/automation/tile/worksite/TileWorksiteFarm.java))

**Exactly one `getBlockState` per tick, forever.** The queue refills when drained, so a maximum-size
16x16 farm fully rescans about every 256 ticks. Scanned positions sort into four `Set<BlockPos>`
buckets and `getNextAction()` walks a fixed priority ladder: harvest, then fertilize, then plant, then
till. The tree farm ladder is shear, chop leaf, chop trunk, plant, bonemeal.

The work area is a bounded box auto-assigned from the player's facing at placement and then edited in a
GUI, with a `byte[256]` `targetMap` marking which columns are in scope. Farm bounds max out at 5, 9, or
16 blocks wide depending on upgrade, with a height of **1**. Quarry bounds go 16, 32, or 64.

The quarry uses no list at all: a single `BlockPos current` cursor advanced east along X, then +Z, then
Y-1, and crucially **chunk-major** (`isMaxInChunk(coord)` is `(coord & 15) == 15`), so it finishes one
16x16 column before moving on. When it runs out it sets a persisted `finished` flag and
`getNextAction()` returns empty forever. That is the only terminal state in the mod: farms never
finish, their buckets just stay empty until crops regrow.

### Search fused with pathfinding (MineColonies): only ever returns reachable targets

`EntityAIWorkLumberjack.findTree()` does not scan a box. It enqueues `PathJobFindTree` onto the
pathfinding thread and polls a `TreePathResult`
([PathJobFindTree.java](https://github.com/ldtteam/minecolonies/blob/version/1.21/src/main/java/com/minecolonies/core/entity/pathfinding/pathjobs/PathJobFindTree.java)).
The A* expansion *is* the search: one off-thread traversal answers both "where is the nearest tree" and
"how do I get there", and it structurally cannot return a tree the worker cannot reach.

| Constant | Value |
| --- | --- |
| `SEARCH_RANGE` | 50 |
| `SEARCH_INCREMENT` | 5 |
| `SEARCH_LIMIT` | 150 |
| `WAIT_BEFORE_SEARCH` | 400 ticks |
| `MAX_BLOCKS_MINED` | 32 (return to chest after half a stack) |
| config `maxtreesize` | 400 logs |

Range escalates from 50 by 5 per failed search up to 150, then gives up. A player-marked zoning box
overrides the radius entirely when `building.shouldRestrict()` is set. Tree identification is a bounded
flood-fill over connected logs, aborting at `maxTreeSize`, with leaf validation requiring 3 leaves
within 4 blocks.

The miner is different again: not a search at all but a **persistent graph**. `MinerLevel` holds
`Map<Vec2i, MineNode> nodes` plus a queue of open nodes; closing a node pushes its neighbours as
available. Node types map to blueprint paths (`SHAFT`, `TUNNEL`, `CROSSROAD`, `BEND_RIGHT`), and the
whole graph is NBT-serialized. `NODE_DISTANCE` is 7, `SHAFT_RADIUS` 3, `MAX_BLOCKS_MINED` 64. The mine
is durable world state, not a re-derived search. The farmer, meanwhile, works a scarecrow-marked
`FarmField` with stages EMPTY, HOED, PLANTED.

### Radius scan from the worker (Ancient Warfare 2 job-finding): the expensive one

When an AW2 worker has no work order it runs `NpcAIPlayerOwnedFindWorksite`, scanning `RANGE = 40` (an
80x40x80 box, roughly 5x5 chunks) every `CHECK_FREQUENCY = 200` ticks by iterating each chunk's
tile-entity map. In 1.12 that call loads or generates chunks rather than returning null for unloaded
ones. (The chunk-load consequence is a reading of the call site, not a filed bug: UNVERIFIED.)

### What happens when targets run out

Every mod does the same thing, which is back off and do something else, and the differences are only in
how loudly:

- MineColonies enters `LUMBERJACK_NO_TREES_FOUND`, waits 400 ticks, then diverts to sapling gathering
  rather than idling.
- Millenaire 9.0 backs off exponentially (`min(delay*2, 20)`) and, on a task that makes no progress for
  6000 ticks, force-stops it and increments an abandoned-goal counter.
- AW2 sets `workRetryDelay = 20` and retries, **silently**. That silence is the direct cause of
  [#1446](https://github.com/P3pp3rF1y/AncientWarfare2/issues/1446), where workers stood at stations
  showing the work icon and doing nothing for a long time because a foreign chunk claim was failing
  every block-break permission check. The failure path had no feedback at all.

---

## 3. Whether the world actually changes

### MineColonies: yes, genuinely, with one revealing exception

`AbstractEntityAIInteract.mineBlock()` computes real drops via `BlockPosUtil.getBlockDrops` (honouring
Fortune and a synthetic Silk Touch path), moves them into the citizen's inventory, breaks the block with
the tool in hand, and damages the tool. The lumberjack's `plantSapling()` places an actual sapling and
plays the block's place sound, gated on actually having saplings. The miner really places ladders with
orientation derived from the shaft delta, backfills cobblestone, and gets torches from its node
blueprints; `BuildingMiner` keeps a stack each of ladders, torches, and cobblestone reserved for this.

The exception is the Nether worker, and it is instructive. In
[EntityAIWorkNether.java](https://github.com/ldtteam/minecolonies/blob/version/1.21/src/main/java/com/minecolonies/core/entity/ai/workers/production/EntityAIWorkNether.java)
the citizen is **removed from the world** (`worker.remove(Entity.RemovalReason.DISCARDED)`) and the
expedition resolves statistically in a `NETHER_AWAY` state: encounters roll random booleans for damage
dealt and taken, rewards come from real mob and block loot tables, tools take durability, and the worker
can die on the trip. This is the one place output is generated rather than simulated, and it is exactly
the case where the player will never watch.

### Ancient Warfare 2: yes, everywhere, with no abstraction at all

The quarry does real `getDrops` plus a real break, with a pre-flight inventory-space check that aborts
rather than voiding items. Crop farms do real tilling, real bonemeal (including the particle event),
real seed consumption, real harvest. Tree farms walk the actual trunk and leaf blocks. Block-break
events fire by default so server protection mods work (`fire_block_break_events` defaults true).

The only things not physical are the worker's cosmetic arm swing and item movement inside the warehouse
multiblock.

### Millenaire: half, and the split is the interesting part

**Storage is real, always.** There is no abstract goods counter. `Building.countGoods()` walks the
actual chest and furnace block entities every call, and this survives verbatim into 9.0 as
`BuildingInventory.scanChests(Level)`. The whole economy lives in loaded block entities, which is
precisely *why* the mod must force-load chunks.

**Gathering is mostly real.** Harvest consumes the block. Cooking uses genuine furnace mechanics, with
slot 0 loaded from building goods and slot 2 drained back into the chests. But **mining does not consume
the block**: `GoalMinerMineResource.performAction()` reads the block at a designated source point, adds
cobblestone or sand or clay to the villager's inventory, plays a breaking sound, and never sets the
block. Mine faces are infinite renewable sources.

**Production is abstract.** Crafting is a pure counter transform against the building's chests,
performed at a designated crafting spot:

```java
for (final InvItem item : inputs.keySet()) {
    final int nbTaken = villager.takeFromInv(item, 1024);
    dest.storeGoods(item, nbTaken);
    dest.takeGoods(item, inputs.get(item));
}
for (final InvItem item : outputs.keySet())
    dest.storeGoods(item, outputs.get(item));
```

Defined entirely in config. The 6.x form was a `.txt` file; the 9.0 form is a datapack JSON:

```json
{"handler":"crafting","priority":50,"scanRadius":32,"batchRadius":8,
 "maxActionsPerTask":5,"actionCooldown":100,"stuckTimeout":4000,
 "townhallLimit":{"minecraft:white_wool":64},
 "handlerParams":{"inputs":[{"item":"millenaire:cotton","count":4}],
                  "outputs":[{"item":"minecraft:white_wool","count":1}]}}
```

No crafting grid, no recipe system, no block interaction. The villager stands at a spot for five
seconds and the chest contents transmute. **288 of the 405 `gathering_type` JSON files in 9.0 are
`craft_*`, which is 71% of the whole work catalog.** The rest breaks down as craft 288, plant 23,
harvest 21, cook 18, mine 15, slaughter 12, gather 6, steal 4, breed 4, and single digits of the rest.

**Construction is genuinely block by block.** `GoalConstructionStepByStep.performAction()` places
exactly one block per invocation. Materials are deducted from the villager's inventory only when the
last block lands.

### Millenaire: nothing runs while unloaded, and that is the deliberate answer

`Building.updateBackgroundVillage()` is the only method that runs outside the active radius, and it does
exactly two things: plan a raid and resolve one. No goods, no construction, no growth. Everything else
is gated behind an `isActive` check that force-loads the village chunks while a player is within
`MLN.KeepActiveRadius = 200` and freezes the village solid otherwise.

This is unchanged in 9.0, only re-plumbed: `VillageChunkLoader` uses NeoForge's `TicketController`
registered through `RegisterTicketControllersEvent`, and `Village.backgroundTick()` contains only
diplomacy drift. Third parties still sell the workaround: **MillOptimize** advertises that "In standard
Millenaire, villages often 'pause' or lose their momentum when no players are nearby", and
**LoadMyVillage** exists solely to sell a "decree of permanence" that force-loads one village.

MineColonies made the identical call. `Colony` is itself a state machine with ACTIVE, UNLOADED, and
INACTIVE states, and `worldTickUnloaded()` is exactly two calls: update child time, update the
chunk-load timer. **Nothing else runs. No offline production, no catch-up.** Force-loading is
configurable (`forceloadcolony` default true, `loadtime` default 20 minutes) and the timer drains three
times faster when no important players are online.

**Two independently maintained mods, on our platform, both refuse to simulate unloaded work and both
answer it by force-loading instead.** That is as close to a settled answer as this survey produces.

---

## 4. Rate and tick cost

### The measured numbers

The most valuable data in the survey comes from an AW2 issue where a user profiled four mods side by
side with LagGoggles ([#612](https://github.com/P3pp3rF1y/AncientWarfare2/issues/612)):

| Entity | microseconds per tick |
| --- | --- |
| Vanilla villager | 4 to 5 |
| MineColonies citizen | 4 to 6 |
| Millenaire villager | 7 to 8, spiking to 30 |
| AW2 faction NPC | 25 to 65 |

A Spark profile on a struggling AW2 server put `NpcBase.onUpdate` at **45.71% of server tick time**
([#808](https://github.com/P3pp3rF1y/AncientWarfare2/issues/808)). The maintainer's own breakdown is the
part that matters:

> "those 45% are spent on onUpdate... if you drill down much further you will see that it then returns
> to calls to aw methods in an ai task where it spends 12% of the time. This is what I am now tweaking
> and almost all of those 12% will likely go away, but the rest is mostly just vanilla code that needs
> to be called"

**Two thirds of the cost was `EntityLiving` itself, unreachable by optimisation.** He measured 500 NPCs
at 27 ms per tick, roughly 54 microseconds each, matching the LagGoggles figure independently. The
practical reading: entity count sets the budget, and clever goal code does not move it. 1.21's brain and
behaviour system is cheaper than 1.12's `EntityAIBase`, but the `LivingEntity` tick floor is the same
shape.

### The six mechanisms MineColonies layers to stay inside that budget

1. **Worker AI runs at 4 Hz, not 20.** `ENTITY_AI_TICKRATE = 5`, and `aiStep()` gates on
   `tickCount % ENTITY_AI_TICKRATE == 0`.
2. **Per-transition tick rates with construction-time phase offsets.** Each `AITarget` gets its rate
   clamped to `[1, 12000]`, and staggering comes from a static counter:
   `this.ticksToUpdate = tickOffsetVariant % this.tickRate; tickOffsetVariant++`, wrapping at 50. Every
   transition ever constructed starts at a different phase, so identical transitions across hundreds of
   citizens never land on the same tick. Deterministic, not random.
3. **An adaptive global throttle** driven off measured tick time in `ServerTickEvent.Pre`, feeding a
   `slownessFactor` clamped to `[1.0, 5.0]`. Note that it **divides** the tick rate by that factor, so a
   struggling server makes transitions fire after *fewer* game ticks. The charitable reading is
   wall-clock compensation. Whether that was the intent is UNVERIFIED, and it is worth deciding
   deliberately rather than copying.
4. **Colonies do not simulate while unloaded** (section 3).
5. **Chunk force-loading is time-boxed and permission-gated** (section 3).
6. **Work itself is spread across ticks.** `BLOCK_MINING_DELAY = 500`, scaled by
   `pow(0.85, skill/2) * hardness / toolDestroySpeed`; `BUILD_BLOCK_DELAY = 15`.

Population cap is `maxcitizenpercolony`, default **250**, range 25 to 500. There is no citizen culling:
cost is linear in loaded citizens. No published ticks-per-citizen figure exists (UNVERIFIED).

**Long walks are decomposed into hops.** `AbstractWalkToProxy` uses `MIN_RANGE_FOR_DIRECT_PATH = 400`
squared, about 20 blocks; beyond that, citizens path through colony waypoints and building positions as
intermediate proxies rather than running one long A*.

### Millenaire's two eras, which read as a before-and-after of getting this wrong

**6.x had no throttling whatsoever.** `ServerTickHandler.tickStart()` iterates every building in every
village in the world, every tick, and the handler has **no `event.phase` guard**, so on Forge 1.7.10
the body runs at both START and END. (The missing guard is directly observable in source; the two-phase
firing behaviour is inferred from the FML API, so UNVERIFIED.) Town hall updates run unthrottled: world
info refresh, construction completion, project finding, seller checks, worker checks, mob killing.
Goal *evaluation* is cheap because it only fires on completion, but `priority()` and `getDestination()`
are frequently O(buildings x items) chest walks.

The tracker shows what that cost: GitLab
[#74](https://gitlab.com/Millenaire/Public/-/issues/74) reports "Event Subscriber taking 21050 ms/t 42%
of the server" with the note "None of the players are in a village, or near one that is active", and
[#712](https://gitlab.com/Millenaire/Public/-/issues/712) reports a 12-player server where "according to
many different profilers I've run, Millenaire seems to be enemy number one". Millenaire knew: `Building`
carried a `PerformanceMonitor` tracking per-goal time plus pathing counters, and pathing was dominant.

**9.0 made throttling first-class**, and the knobs are per-work-type datapack JSON:

| Knob | Harvesting | Crafting |
| --- | --- | --- |
| `scanRadius` | 32 | 32 |
| `batchRadius` | 8 | 8 |
| `maxActionsPerTask` | 16 | 5 |
| `actionCooldown` | 10 ticks | 100 ticks |
| `stuckTimeout` | 4000 | 4000 |

**`batchRadius` plus `maxActionsPerTask` is the key idea**: one goal acquisition and one walk amortised
over up to 16 harvest actions within 8 blocks. Village-level work is on coarse intervals too: growth
evaluation every 20 ticks, integrity checks every 600.

### AW2 throttles with an energy economy rather than a tick budget

Two independent throttles multiply. The NPC delivers one work unit every `npcWorkTicks = 50` (2.5
seconds), worth `energyPerWorkUnit = 50`. The worksite's buffer is capped at 150, which is **exactly
three work units**, and each action deducts its own energy cost. Worksites tick every tick, with a
`workRetryDelay` of 20 ticks applied only when `processAction` returns false. That delay was added
specifically as a performance fix: the 2.2.66 changelog reads "add a small delay to worksite work
processing if previous attempt failed. Should smooth tick-times with empty worksites."

Nothing runs off-thread anywhere in AW2. They were profiler-conscious, though, wrapping worksite work
and NPC updates in named profiler sections.

### MCA's cheap tricks

Per-village phase staggering with an explicit comment: `time += getId(); // spread performance to avoid
lag spikes`. Taxes accrue on a coarse season interval and reputation on a 24000-tick interval.
`VillageManager.tick` processes **one** building per cooldown from a queue. Population cap is bed count,
read from a cached `PoiManager` scan for `PoiTypes.HOME`. Worth noting: tax *accrual* is not chunk-gated
even though almost everything else is, so the buffer fills while unloaded and flushes when a player
enters. That is a cheap way to fake offline progress without simulating anything.

---

## 5. Inventory topology

### MineColonies: a priority ladder, not a centralized warehouse

This is the correction that matters most for #49. MineColonies is usually described as
"warehouse plus couriers", and that is only half the design. Resolvers are ranked, and **the worker's
own building outranks the warehouse**
([RSConstants.java](https://github.com/ldtteam/minecolonies/blob/version/1.21/src/main/java/com/minecolonies/api/util/constant/RSConstants.java)):

| Resolver | Priority |
| --- | --- |
| `BuildingRequestResolver` (the worker's own building) | **200** |
| `AbstractWarehouseRequestResolver` | 150 |
| Crafting resolvers | 125 |
| Default | 100 |
| `StandardRetryingRequestResolver` | 50 |
| `StandardPlayerRequestResolver` | 0 |

Local hut storage is consulted before the warehouse, and the player is the resolver of last resort. This
matches the player-facing lookup order documented on the wiki: personal inventory, then hut block, then
racks in the hut, then raise a request
([minecolonies.com/wiki/systems/request](https://minecolonies.com/wiki/systems/request/)).

**Requests beget requests.** The warehouse resolver, on finding a match, does not hand the item over. It
calls `manager.createRequest(this, delivery)` with a new `Delivery`, spawning a second request that a
Deliveryman resolves. Fulfilment is a graph of chained requests, and `RequestState` has **13** values
(CREATED, REPORTED, ASSIGNING, ASSIGNED, IN_PROGRESS, RESOLVED, FOLLOWUP_IN_PROGRESS, COMPLETED,
OVERRULED, CANCELLED, RECEIVED, FINALIZING, FAILED). That graph is exactly where the deadlocks in
section 6 live.

Couriers run a six-state machine (IDLE, START_WORKING, PREPARE_DELIVERY, DELIVERY, PICKUP, DUMPING)
with a five-second decision delay. Warehouse level caps concurrent couriers at two per level, maximum
ten (community source: the [Warehouse wiki page](https://minecolonies.com/wiki/buildings/warehouse/)).
The Rack is a 27-slot container that lives in *every* building, not just the warehouse, exposed through
NeoForge's `Capabilities.ItemHandler`. Notably, **the wiki gives no stated rationale** for centralizing
storage or for racks over vanilla chests. The opinions attributed to MineColonies on this point are
inferred from the code, not documented.

### Millenaire: real chests plus a reservation policy

Four tiers: villager inventory (a `HashMap<InvItem, Integer>`, not an `IInventory`, so no slot count and
no stack limit), building goods (the real chests and furnaces), the town hall (a building like any other
but privileged), and a village trade pool that is a *policy* over the first three rather than a
container.

Movement is explicit and physical. `GoalBringBackResourcesHome` has `priority = 10 + nbGoods * 3`, so
the more a villager is carrying the more urgently it wants to go home, with a debounce so it does not
run home with one wheat.

The brain of the economy is `Building.nbGoodAvailable(item, forExport, forShop)`, which subtracts four
classes of claim from the raw chest count:

- a configured `reservedQuantity` (or `targetQuantity` when exporting) from `traded_goods.txt`
- every resident's required food and goods
- **the entire recipe cost of the current building project**, returning zero if the project would need it
- a circularity guard so a shop never exports an item it is itself supplied with

That last mechanism is how a single shared pool is made to behave like reserved local stock without
actually being local.

### Ancient Warfare 2: the warehouse is itself a worksite

The Warehouse Control Block extends `TileWorksiteBounded`, has an energy buffer, and is powered by
Craftsman NPCs exactly like a quarry. Its "work" is item movement, and energy is consumed per move
regardless of stack size. It has no accessible inventory of its own: storage blocks hold the goods, and
breaking one drops its contents.

"Storage requests" are declarative minimum levels living on the **Interface**, not on the worksite:
filter an interface for 64 wheat seeds and the warehouse keeps it topped up, while anything unfiltered
dropped in gets swept back to storage. Couriers are the inter-building leg and are **not**
warehouse-specific: they walk a `RoutingOrder` of route points with 12 transfer types (Fill Upto, Take
Upto, Put Any, Take Any, Put Except, Take Except, Put Ratio, Take Ratio, Put Exact, Take Exact, Fill
Minimum, Take Minimum) through the generic item-handler capability, so vanilla chests work identically.
Courier cost scales with volume: `(20 - level) * itemsMoved` ticks. Items in transit sit in the
courier's backpack, physically inspectable and stealable.

A Stock Linker closes the loop as a feedback controller: bound to a warehouse and mounted on a machine,
holding up to four `{item, operator, value}` conditions, it emits a redstone signal that disables the
attached machine when stock crosses a threshold.

---

## 6. What they got wrong

### MineColonies: the request system has been declared unsalvageable, repeatedly

The repo contains a family of update steps whose entire body is `manager.reset()`:
`ResetRSToRemoveAssistantCookResolver`, `ResetRSToStoreJobInResolvers`,
`ResetRSToUpdateRestaurantResolver`. **The shipped remedy for request-system corruption is to wipe the
whole request graph on world load**, and the version counter shows this has happened repeatedly across
the format's life. That is the strongest design admission in this survey.

The live failure mode, filed against 1.21 in December 2025:
[#11422 "Miner permanently stuck in NEEDS_ITEM state with invisible request"](https://github.com/ldtteam/minecolonies/issues/11422),
reproduced on a clean instance, with a second reporter confirming Quarriers stuck in a build loop
*while holding the required materials*. Same family:
[#11285](https://github.com/ldtteam/minecolonies/issues/11285) (partial ingredients never requested),
[#7343](https://github.com/ldtteam/minecolonies/issues/7343),
[#5365](https://github.com/ldtteam/minecolonies/issues/5365).

**Pathfinding is unreliable enough to need teleportation as a supported fallback.** Citizens are
configured with `.withTakeDamageOnStuck(0.2f).withTeleportSteps(6).withTeleportOnFullStuck()`: they
teleport six path nodes forward when stuck, teleport to the goal on full stuck, and take damage on the
way. See [#10003](https://github.com/ldtteam/minecolonies/issues/10003) for the fallback itself failing,
and [#7207](https://github.com/ldtteam/minecolonies/issues/7207) for underwater path costs stranding
NPCs.

**The clearest architectural retreat** is threading. On `version/1.20.4` the pathfinding pool was
`new ThreadPoolExecutor(1, config.pathfindingMaxThreadCount.get(), ...)` with a configurable maximum of
10. On `version/1.21` the config key is deleted and the pool is pinned to a single daemon thread. Every
citizen's pathing, and every lumberjack tree search, funnels through it.

Performance history includes [#6366](https://github.com/ldtteam/minecolonies/issues/6366) (huge TPS
drop), [#10715](https://github.com/ldtteam/minecolonies/pull/10715) (unbounded guard-patrol event queue
in the state machine), and a cluster of leaks:
[#10491](https://github.com/ldtteam/minecolonies/issues/10491),
[#10492](https://github.com/ldtteam/minecolonies/issues/10492),
[#11436](https://github.com/ldtteam/minecolonies/issues/11436).

No maintainer statement explicitly calls either design a mistake. The `manager.reset()` migrations and
the thread-pool walkback are strong circumstantial evidence, but the intent behind both is UNVERIFIED.

### Millenaire: livelocks, duplication, and a decorative block that broke vanilla pathing

The best single source is **MillMix_Jubi**, a third-party mixin patch for 8.1.2 whose feature list is
effectively a defect list. Its first entry: a Millenaire path slab had a collision box of 7/16 instead
of 8/16, which made the vanilla pathfinder trigger a broken in-place jump, so **entities froze on that
block forever unless pushed off it**.

Building livelock is the most relevant failure to #48. GitLab
[#687](https://gitlab.com/Millenaire/Public/-/issues/687) (open) describes it precisely: "The assigned
architect keeps changing... A villager will have 'Getting resources for a construction' over their head,
but when they get to the center building the words change to 'delivering resources'. Then that builder
walks away, a new one comes to the main building, and the cycle repeats." The single-slot builder
reservation had no timeout and no progress check. See also
[#663](https://gitlab.com/Millenaire/Public/-/issues/663) and
[#502](https://gitlab.com/Millenaire/Public/-/issues/502), both stuck at 99%.

Villager duplication is chronic and still live on the current era:
[#545](https://gitlab.com/Millenaire/Public/-/issues/545),
[#717](https://gitlab.com/Millenaire/Public/-/issues/717), and
[#732 "UUID and Chunkloading NPC Cloning Issues"](https://gitlab.com/Millenaire/Public/-/issues/732),
filed 2026-01-05: "each trip to the village and I find more and more Villagers."

**Every one of 9.0's new safety mechanisms is a direct fix for a documented 8.x bug**: the goal-scheduler
watchdog, the idle backoff, the per-villager throttle, and a set of builder escape hatches
(`PLACING_STUCK_TIMEOUT = 400` then teleport to the site, `NAVIGATE_STUCK_THRESHOLD = 100` then place
the block remotely from up to 50 blocks away, construction reservations that expire after 200 ticks if
the reserved builder entity is gone, plus a stale-reservation sweep every 600 ticks).

### Ancient Warfare 2: eleven years of the same three bugs

**Pathfinding.** The maintainer diagnosed the real constraint on
[#794](https://github.com/P3pp3rF1y/AncientWarfare2/issues/794):

> "In my testing even if the npc has to walk 20 blocks around the wall the algorithm stops on reaching
> the max number of combinations. As said I have this on my list of enhancements (basically replace
> vanilla pathfinding with something more efficient)"

He also rejected the obvious workaround for good reasons: "Zombies actually don't really find a path
they wander randomly towards a point... I could potentially use that logic for npcs, but I wouldn't like
watching drunk like NPCs." The replacement never landed.
[#1365](https://github.com/P3pp3rF1y/AncientWarfare2/issues/1365) has been open since 2021 with a
comment reading "It's been this way for 5 years. It is and always will be broken." The 2014 changelog
already carried the caveat: long-range pathing "Might still have issues with some terrain or if there
are multiple vertical levels involved." Eight years, same failure mode.

**The `hasWork()` mistake, and why it is scar tissue.** `hasWork()` is a battery-not-full check, not a
there-is-work check, so a worker keeps mining an exhausted quarry until the buffer tops out. The
changelog explains why: an earlier version drained worksite energy, which made `hasWork()` permanently
true, and the fix was "Remove power drain from worksites as it would cause a worker to never leave a
site". The design flaw is the residue of a worse bug. The lesson generalises: decide up front whether
your predicate means *this site needs labour* or *this worker should stay here*, because AW2 conflated
them and could only ever fix one at a time.

**Cached aggregates over authoritative inventories.** There are **17 separate warehouse duplication or
loss issues from 2014 to 2025**, three still open, the newest filed 2025-10-27
([#1450](https://github.com/P3pp3rF1y/AncientWarfare2/issues/1450)).
[#1097](https://github.com/P3pp3rF1y/AncientWarfare2/issues/1097) names the mechanism: "I took 51 iron
ore from my warehouse, pulverized it... Coming back later I found the ingots gone and the 51 iron ore
instead... the entire warehouse is being rolled back in time?" The maintainer's own diagnosis on
[#598](https://github.com/P3pp3rF1y/AncientWarfare2/issues/598) points at the cached item map: "i am
guessing it lost cached pointer to the storage where the item is stored." Same root cause as
[#1002](https://github.com/P3pp3rF1y/AncientWarfare2/issues/1002), where a multiblock's one-shot
`if (!init) scanForInitialTiles()` silently drops cross-chunk members that unload, open and uncommented
for six years.

**Unbounded per-entity save data.** [#1368](https://github.com/P3pp3rF1y/AncientWarfare2/issues/1368),
still open: "1.5s lag spikes every 45 seconds on server auto saves... Watch the capabilities.dat grow
from roughly 11KB to 5MB over a few weeks." Worth designing against from day one.

**Unfinished by admission.** `ItemBlockWorksiteStatic` ships with `// TODO validate that worksite does
not intersect any others`, so two worksites can be placed with overlapping bounds and will fight over
the same blocks. [#159 "Torque System, Rework to a proper network graph"](https://github.com/P3pp3rF1y/AncientWarfare2/issues/159)
has been open since 2014: the energy layer every worksite depends on was known-inadequate for a decade
and never rebuilt. [#697 "[Epic] AI enhancements"](https://github.com/P3pp3rF1y/AncientWarfare2/issues/697),
opened by the maintainer himself in 2018, is still open with zero comments.

The mod stopped at 1.12.2 with no discontinuation notice. The escalation is visible in the tracker
(2018: "quite a bit of refactoring on my side"; 2020: "We have no current plans to update past 1.12";
2021: "No, not at this time"), and the maintainer has been shipping other mods continuously through
2026. A live fork exists at
[blahthebiste/AncientWarfare2](https://github.com/blahthebiste/AncientWarfare2) ("Ancient Warfare 2:
Tweaked"), which is the better read for anyone maintaining this codebase in the 2020s.

### Minecraft Comes Alive

[#1088 "Pathfinding Megathread"](https://github.com/Luke100000/minecraft-comes-alive/issues/1088) is
open, there is a dedicated `feature/pathfinding` branch, and the tip of `1.21.1` is literally "Fix
crashes/pathfinding bugs". MCA's answer is `WanderOrTeleportToTargetTask`, an explicit teleport escape
hatch, with `Config.villagerPathfindingDistance` defaulting to 80 and clamped to `[16, 256]`.

---

## 7. What ports to NeoForge 1.21.1 and what does not

**Three of the four are already there.** MineColonies `version/1.21`, Millenaire 9.0, and MCA `1.21.1`
all target MC 1.21.1, so their mechanisms are live references. Only Ancient Warfare 2 requires
translation.

### The vanilla-AI question, answered three ways by three current mods

| Mod | Approach |
| --- | --- |
| MineColonies | **Bypasses both.** `goalSelector` carries only ambient goals (float, look-at, door toggling). All work AI is a custom `TickRateStateMachine` ticked manually from `aiStep()`. The Brain system is unused. |
| Millenaire 9.0 | **Bypasses both.** `MillVillager extends PathfinderMob`, not `Villager`, and a search across all 583 files returns **zero** hits for `Brain`, `Activity`, or `MemoryModuleType`. Given a from-scratch 2026 rewrite, the author declined the vanilla brain and kept his own scheduler. |
| MCA | **Uses the vanilla Brain** throughout, and correspondingly has no autonomous work (section 1). |

The pattern MineColonies uses is the one worth copying for `RealPerson`: **vanilla goals for reflexes,
a custom state machine for work.** The two do not need reconciling, and this repo is already halfway
there, since `entities/ai/goals/` holds per-job `Goal` subclasses with no brain involvement.

Also worth knowing: **MineColonies uses no mixins, no coremods, and no ASM.** Everything is built on
public NeoForge event and API surface, which is why they track Minecraft versions as fast as they do. It
uses NeoForge **capabilities** (`RegisterCapabilitiesEvent`, `event.registerBlockEntity(ItemHandler.BLOCK, ...)`),
not data attachments, so that one area of their patterns will not transfer directly if this project
prefers attachments. Their blueprint engine is an external dependency (Structurize), which is a decision
worth making consciously before building further on `village/buildings/`.

### Ancient Warfare 2: what would need rewriting

| Mechanism | AW2 (1.12) | NeoForge 1.21.1 |
| --- | --- | --- |
| AI tasks | `EntityAIBase`, `tasks.addTask(int, ...)`, custom `NpcNavigator` / `NpcWalkNodeProcessor` | `Goal` / `GoalSelector.addGoal`; `PathNavigation` and `WalkNodeEvaluator` reshaped. Rename, then real work on the navigator. |
| Tile ticking | `ITickable` plus `public void update()` (14 files) | `BlockEntityTicker<T>` from `EntityBlock#getTicker`. The whole `updateWorksite()` template re-plumbs. |
| Chunk loading | `ForgeChunkManager.requestTicket / forceChunk / unforceChunk` (8 files) | **Gone.** NeoForge 21.1.72 ships `net.neoforged.neoforge.common.world.chunk.TicketController`, a record with `forceChunk(ServerLevel, BlockPos owner, int chunkX, int chunkZ, boolean add, boolean ticking)`, registered via `RegisterTicketControllersEvent`. Verified in the `neoforge-21.1.72-minecraft-sources.jar` this repo compiles against. Millenaire 9.0 already uses exactly this. |
| Blockstate metadata | `getMetaFromState` / `getStateFromMeta` in **47 files** | Removed in 1.13. Real properties plus a data-fixer story. The single biggest mechanical cost. |
| Config | Forge `Configuration` with mutable `public static` fields | `ModConfigSpec` with load/reload events. The *values* port unchanged. |
| RF compat | `cofh.redstoneflux.api` behind `@Optional.Interface` | RedstoneFlux is dead; `IEnergyStorage` is the swap, and `@Optional.Interface` no longer exists as a mechanism. |

**What ports for free** is the important half: `IWorkSite`, `IWorker`, and `IWorksiteAction` are pure
Java interfaces with almost no Minecraft surface, so the energy-as-work abstraction is API-agnostic. So
are all the algorithms: the quarry's chunk-major cursor, the farm's one-position-per-tick refilling
queue, the `byte[256]` target mask, the priority ladder, the 12-type routing matrix, and every tuning
constant. AW2 had also already migrated off `IInventory` to capability item handlers, which NeoForge
keeps.

Millenaire's 6.x to 9.0 diff is the cleanest available list of what does not survive this API jump:
wall-clock `System.currentTimeMillis()` timing became tick counters; `ForgeChunkManager` became
`TicketController`; **buildings as stacks of PNG images** (one image per Y layer, pixel colour to block)
became 3620 vanilla `.nbt` structure templates; `.txt` config became JSON datapacks; block metadata
became flattened states; a bundled JPS A* with a per-village path cache became `MillPathNavigation`
extending vanilla navigation; custom flat-file persistence became vanilla `SavedData`.

---

## 8. Direct implications for #48, "What a worker actually does"

### 8.1 One abstraction with parameters, and the evidence is not close

#48 asks whether lumberjack, miner, farmer, and blacksmith are one abstraction or four things. Three
data points, all pointing the same way:

- MineColonies runs ~40 `EntityAIWork*` classes on a shared five-layer skeleton, and the shared layers
  carry the entire generic cycle. The per-job class only registers domain states.
- Millenaire went from 53 hand-written goal classes to 25 classes plus **one** data-driven
  `GatheringGoal` covering 405 JSON work types through 20 handlers, in a rewrite by the same author.
- AW2 has one worker class and pushes all variation into the worksite.

**Recommendation: a small fixed set of action handlers, each parameterized from datapack JSON, over one
shared cycle.** The handler set the four mods collectively need is short: `harvest`, `plant`, `mine`,
`convert` (inputs to outputs), `build`, and `fetch`. That maps onto the catalog in `buildings.md`
without a class per category, which matters when the catalog is 37 categories wide. This repo already
has per-job `Goal` subclasses (`WorkOnWoodcuttingGoal`, `WorkInMineGoal`, `HarvestCropGoal`,
`TillSoilGoal`, `ProcessItemGoal`), which is the MineColonies shape at small scale. The refactor
question is whether to grow that to 19 occupations or collapse it now.

### 8.2 The cycle states, which are settled prior art

All four converge on the same skeleton, so name it and move on:

```
IDLE -> ACQUIRE_TARGET -> TRAVEL -> ACT (repeat up to N times) -> DEPOSIT -> IDLE
                                \-> NEEDS_ITEM
                                \-> BLOCKED
```

Two refinements worth taking verbatim:

- **Put interruptibility on the state**, as MineColonies does with `isOkayToEat`. This repo already has
  a `shouldInterrupt()` check scattered across goals; a state table is cleaner and answers eating,
  sleeping, and panic uniformly.
- **Split selection from execution**, as Millenaire 9.0 does with stateless `VillagerGoal` and stateful
  `VillagerTask`. It is what makes a watchdog and a throttle possible without touching job logic.

### 8.3 Target finding: bound it to the building, batch the actions, never radius-scan per tick

The cheapest correct design in the survey is AW2's farm: **a refilling queue over a bounded footprint,
one block state read per tick**. The highest quality is MineColonies' fused search-and-path, which
structurally cannot hand a worker an unreachable target. The lowest cost of all is Millenaire's, which
never scans because the building already knows its own points.

This repo is closest to Millenaire's model and should stay there. `BuildingInfo` already carries
`getWorkLocations()`, `getContainerLocations()`, and `getBedLocations()` as origin-relative offsets, and
`WorkInMineGoal` already advances a cursor inward from the station with a fixed cross-section, which is
a small version of the MineColonies node graph. The gap is that a lumberjack's target is *outside* the
building, so the building needs a **work area** as well as a footprint: a bounded box, sized per
category, populated by a refilling queue.

Two things to add on top:

- **Batch actions per trip.** Millenaire 9.0's `batchRadius = 8` with `maxActionsPerTask = 16` amortises
  one target acquisition and one walk over up to 16 actions. Given that pathfinding is the shared
  failure of all four mods, reducing walks per unit of output is the highest-leverage single knob.
- **Check reachability at claim time, not at arrival.** Every "worker stuck" bug in every tracker here
  is ultimately a target that was assigned before anyone asked whether it could be reached.

**When targets run out**, back off and surface it. The precedents are exponential backoff capped at 20
ticks (Millenaire), a 400-tick wait and a diversion to a secondary task (MineColonies), and a 20-tick
silent retry (AW2, which caused the worst diagnosability bug in the survey). This repo already has
`NoResourceBookkeepingEvent` feeding attractiveness, which is the right place to make "this worker has
nothing to do" visible rather than silent.

### 8.4 Rate: the population cap is the answer, not the tick budget

The measured numbers say a vanilla villager costs 4 to 5 microseconds per tick and a MineColonies
citizen 4 to 6, and that roughly two thirds of any villager's cost is `LivingEntity` itself and cannot
be optimised away. A village of 40 at 6 microseconds is about 0.24 ms per tick, which is comfortable. A
world of ten such villages is 2.4 ms, which is still fine but is the point at which staggering matters.
Five hundred workers is not viable.

Concretely, for this repo:

- **`Village.update` is already the right shape**: 1 Hz, phase-staggered by `id.hashCode()`. Extend that
  discipline rather than inventing a second scheduler.
- **Run worker AI at 4 Hz, not 20.** MineColonies' `ENTITY_AI_TICKRATE = 5` is the reference. The
  existing goals tick every entity tick.
- **Stagger deterministically at construction**, MineColonies-style, with a wrapping static counter
  rather than randomness. It is cheaper than a scheduler and reproducible in tests.
- **Do not copy the adaptive slowness throttle** without deciding what degradation should mean. As
  written it does more AI work per tick when the server is already behind.

### 8.5 Unloaded chunks: freeze, do not accrue, and say so in the doc

MineColonies and Millenaire, both actively maintained on MC 1.21.1, independently refuse to simulate
anything while unloaded and both answer it by force-loading a bounded region for a bounded time.
MineColonies' `worldTickUnloaded()` is two calls. Millenaire's `backgroundTick()` is diplomacy only.
Neither does catch-up accounting.

The one cheap trick available comes from MCA: accrue a *coarse, non-simulated* quantity while unloaded
and flush it when a player arrives. That would be an explicit departure from "nothing spawns items" in
`building-spec.md`, so it should be decided, not slipped in.

This repo already skips arrival cycles in unloaded edge chunks, so freezing is consistent with what is
built. The decision that follows is whether villages get a `TicketController` force-load like both
current references use, and for how long.

### 8.6 The blacksmith case has a direct, proven precedent

#48 calls fetch-then-craft "a different animal from harvesting and probably the harder one". Millenaire
says it is the easier one, and has made it 71% of its entire work catalog for over a decade.

The shape: the worker walks to a designated crafting spot inside its own building, and after a fixed
duration the building's **real chest contents** are decremented by the recipe inputs and incremented by
the outputs. No crafting grid, no recipe system, no block interaction. Because storage is real chests
scanned live, this satisfies `building-spec.md` exactly: the iron must physically be in a chest before a
tool exists, the tool physically appears in a chest, and nothing is tracked in parallel. Aaron's line in
#48 ("The blacksmith does go collect raw materials from the mine's chest that the miner has mined") adds
a fetch leg in front of it, which is Millenaire's `GoalGetResourcesForShops` and
`GoalDeliverResourcesShop` pair, whose priority scales with the size of the backlog.

The natural boundary for how much to simulate comes from MineColonies' Nether worker: **abstract the
work the player will never watch, simulate the work the player will.** A blacksmith standing at an anvil
is watched. An expedition is not.

### 8.7 Replanting, and the mine that eats its own building

#48 characterises the miner as "harvest, no replant". Millenaire's answer is worth putting on the table:
its miners deliberately do **not** consume the block. They read a designated source face, take
cobblestone or sand or clay, play a break sound, and leave the world untouched. Mine faces are infinite
renewable sources.

That is a real option, and it avoids the failure mode where a mine hollows out its own site over time,
or where a lumberjack deforests the village and then has nothing to do. MineColonies takes the opposite
path and pays for it with a persistent NBT node graph and blueprint-driven corridors. This project
should pick per job: the lumberjack's fell-and-replant loop is exactly the visible cycle Aaron described
and should be real; the miner has a live choice between a real node graph and a renewable face.

### 8.8 Bearing on #49, "Where a building output goes"

Three findings transfer directly:

1. **MineColonies is local-first with a shared fallback, not one or the other.** The worker's own
   building resolves at priority 200, the warehouse at 150, and the player at 0. That is a third answer
   to #49's binary, and probably the right one: a storehouse is where you look *second*.
2. **Do not cache an aggregate index over authoritative per-block inventories.** AW2's 17-issue
   duplication and loss history, plus the six-year-old multiblock membership bug, are all one bug in
   seventeen hats. This repo's `VillageBrain.getVillageInventory` derives on read every call, which is
   the safe design and should survive whatever #49 decides.
3. **Millenaire's `nbGoodAvailable` is how a shared pool behaves like reserved local stock**: subtract
   configured reserves, residents' needs, and the whole recipe cost of the current building project from
   the raw count. That is a policy layer over one pool rather than genuinely separate chests, and it
   gets most of #49's legibility without the deadlock risk of physical fetching.

The deadlock risk in #49 is not hypothetical. MineColonies' warehouse resolver hands back a *delivery
request* rather than the item, chaining requests into a 13-state graph whose shipped remedy for
corruption is to wipe it on load. If this project builds anything resembling a request graph, the
guiding rule is that **a stuck request should be re-derivable from world state**, never a durable object
that can go bad.

### 8.9 The short list of things to avoid

- **Silent blocked work.** AW2 [#1446](https://github.com/P3pp3rF1y/AncientWarfare2/issues/1446).
  A worker that cannot act must say so.
- **A predicate that conflates "there is work here" with "stay here".** AW2's `hasWork()`.
- **Reservations without a timeout.** Millenaire's single-slot builder livelock,
  [#687](https://gitlab.com/Millenaire/Public/-/issues/687).
- **Teleport-on-stuck as a feature.** All three of MineColonies, Millenaire 9.0, and MCA ship it. It is
  a symptom of pathfinding that needs shortening, not a design.
- **Unbounded per-entity save data.** AW2 [#1368](https://github.com/P3pp3rF1y/AncientWarfare2/issues/1368),
  11 KB to 5 MB in weeks.
- **Long commutes.** Vanilla pathfinding measurably gives up at roughly 20 blocks around an obstacle.
  Keep work sites near homes, which the campfire model already wants.
