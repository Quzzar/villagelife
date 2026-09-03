# Automatone: server-side Baritone, and what to glean from it

A note to ourselves, prompted by the LLM-mod landscape survey: Automatone is the one genuinely
hard-won piece in that whole ecosystem, a server-side fork of Baritone that gives non-player
entities Baritone-grade pathfinding. Our own recurring pains are pathfinding-shaped (villagers
getting stuck, long walks failing, miners needing ramps and ladder descents, builders reaching
upper floors, terrain bridging), and [worker-loops.md](../worker-loops.md) already
found that vanilla navigation measurably gives up around 20 blocks past an obstacle and that
every mature village mod ships teleport-on-stuck as a result. So the question was: is there good
logic to glean here, or a library to lean on. This is what the source actually says.

**Bottom line: read it, do not ship it.** Automatone is Fabric-locked, built around fake
`ServerPlayer` entities, unmaintained at 1.21, and designed for a handful of bots rather than a
village of dozens. The gold is a small, license-clean set of *ideas*, not the library.

**Source discipline.** Everything below was read from the projects' own source. File paths are
repo-relative within the clones and mirror GitHub. Claims not traceable to source are marked
UNVERIFIED. Automatone vendors Baritone's entire source (298 `.java` files still carry the
`This file is part of Baritone` header under the `baritone.*` package root), so reading Automatone
is reading Baritone's code refactored for the server; "Baritone" claims below are grounded in the
vendored source in the canonical clone, cross-checked against the project's `FEATURES.md`.

| Repo | MC | Loader | Status (last commit read) | Notes |
| --- | --- | --- | --- | --- |
| [Ladysnake/Automatone](https://github.com/Ladysnake/Automatone) (canonical) | 1.18 | Fabric | frozen Dec 2021 | the reference; Pyrofab's server-side refactor onto Cardinal Components |
| [minefortress-mod/automatone](https://github.com/minefortress-mod/automatone) | 1.20.2 | Fabric | alive, May 2025 | drives the MineFortress colony mod; closest "village of workers" precedent |
| [sailex428/Automatone](https://github.com/sailex428/Automatone) | 1.21 | Fabric | Sep 2025, README says no longer maintained | regressed to driving Carpet fake players; README: "idk if this still works" (UNVERIFIED it runs) |

There is no NeoForge build anywhere, and no maintained MC 1.21.1 build. Every variant hard-depends
on Cardinal Components API, a Fabric-only entity/world component framework with no NeoForge
equivalent.

## TL;DR and recommendation

1. **Do not port or depend on it.** It is Fabric-rooted to the bone (Cardinal Components, Fabric
   API, Yarn, Fabric mixins and networking), its terrain-modifying navigation assumes a real
   player entity, and its per-entity weight is built for 1 to 5 bots, not a village.
2. **Minecraft 1.21.1 already has most of the thin pathfinding core.** Its `PathFinder` is weighted
   A\* with heuristic factor 1.5, has a hard expansion budget, returns a best-known partial path,
   and searches a `PathNavigationRegion` that substitutes empty chunks rather than loading them.
   Reimplementing those parts would add machinery without adding the missing capability.
3. **The first Villagelife change is smaller:** retain the stock `PathFinder` and our existing
   `PersonNodeEvaluator`, decouple route lookahead from the genetically varied `FOLLOW_RANGE`, and
   measure the resulting searches at debug log level. The route window is now at least 48 blocks,
   still capped at roughly 1,280 expanded nodes and still unable to load chunks.
4. **Only deepen the module if measurements justify it.** A custom frontier score or segmented
   planning is the next candidate when vanilla's crow-flies partial endpoint is repeatedly bad.
   Tick-priced movement costs and break/place edges become valuable only if a job must choose among
   terrain-modifying routes. They do not improve today's walk-only graph by themselves.
5. Keep the existing mine-ramp waypoints and the no-progress recovery described in
   [worker-loops.md](../worker-loops.md). Better planning reduces recoveries; it does not replace a
   bounded operational safety net.

## 1. How it drives an entity: the fake-player wall

This is the single most important finding, because it decides whether the good part is even
reachable for our design.

Baritone drove the real client player (`EntityPlayerSP`). Automatone replaced that not with an
arbitrary mob but with a fake *server player*: `FakeServerPlayerEntity extends ServerPlayerEntity`
(`baritone/api/fakeplayer/FakeServerPlayerEntity.java`), which fabricates a dummy network handler,
overrides `isPlayer()` to false so it ticks like a mob, and keeps no chunks loaded. The pathing
brain is a Cardinal Components component, `IBaritone`, keyed on the entity and registered by
default only for `PlayerEntity` (`baritone/AutomatoneComponents.java`).

There is a hard split between locomotion and terrain modification:

- **Locomotion is genuinely entity-agnostic.** Execution writes `LivingEntity.forwardSpeed`,
  `sidewaysSpeed`, and `setJumping(...)` (`baritone/utils/InputOverrideHandler.java`), the exact
  fields vanilla `LivingEntity.travel()` reads, and `IEntityContext.entity()` is typed
  `LivingEntity`. Walk, jump, fall, swim, ascend, descend, and parkour work on any `LivingEntity`.
- **Terrain modification requires a real player.** Non-players get `DummyEntityController`
  (`clickBlock` false, reach distance 0), and the cost model prunes every break and place move for
  them: `CalculationContext` sets `toolSet = player == null ? null : new ToolSet(player)`, and
  `getMiningDurationTicks` returns `COST_INF` when the tool set is null while `costOfPlacingAt`
  returns `COST_INF` when there is no throwaway inventory
  (`baritone/pathing/movement/CalculationContext.java`, `MovementHelper.java`).

**Consequence:** Baritone-grade *terrain-modifying* navigation (our ramps, shaft descents, bridges)
essentially needs the entity to be a fake `ServerPlayerEntity`. The MineFortress fork confirms
this: its colonist is still a fake player (`baritone/api/minefortress/PlayerMinefortressEntity.java`),
and it had to add `MixinMoveControl` / `MixinJumpControl` / `MixinLookControl` to reconcile the
player brain with vanilla mob controls. One bright spot: the cost model is dimension-aware
(`width`, `height`, `requiredSideSpace = ceil((width - 1) * 0.5)`), and movements carve a tunnel
sized to the entity, so the *idea* generalizes to non-1x1 workers even though the code is
player-shaped.

## 2. The pathfinding core

**Algorithm:** a weighted (inadmissible) A\* with a binary-heap open set, positions packed into a
`Long2ObjectOpenHashMap` by a long hash (`baritone/pathing/calc/AStarPathFinder.java`,
`AbstractNodeCostSearch.java`). Baritone's signature trick is that one search tracks seven
"best so far" nodes at once, one per heuristic-inflation coefficient
`{1.5, 2, 2.5, 3, 4, 5, 10}`, so a search that times out or hits the render-distance bound still
yields a usable partial segment (the incremental cost backoff, `bestSoFar()`). Two guards matter
for us directly: `COST_INF` (1,000,000) prunes illegal moves, and a chunk-border cutoff stops
expansion into unloaded chunks rather than loading them.

Vanilla 1.21.1 is closer to this than the original comparison implied. It already multiplies its
heuristic by 1.5 and reconstructs the target's nearest discovered node when the search does not
reach the goal. The important difference is partial-path quality: vanilla tracks one best node by
Euclidean distance to the target, while Automatone tracks several inflated heuristics and backs
off among them. That is a possible later seam, not a reason to replace the current search first.

**Cost model is time, in ticks,** derived from real Minecraft physics
(`baritone/api/pathing/movement/ActionCosts.java`: walk one block about 4.63 ticks, sprint about
3.56, ladder up about 8.5, fall costs integrated from the `0.98^ticks` drag curve). Because every
edge is priced in the same unit, "mine straight through" and "walk around" and "bridge over" are
directly comparable numbers in the same search. That comparability is the whole point.

**Movement catalog** (8 concrete classes in `baritone/pathing/movement/movements/`, expanded
per-direction by the `Moves` enum). There is no separate swim, ladder, or door class; those live
inside the traverse, pillar, and downward cost and state logic plus `MovementHelper`.

| Movement | What it is | Cost includes | Legality gate |
| --- | --- | --- | --- |
| `MovementTraverse` | walk one block, or bridge across a gap | walk time, or walk + place + mining | floor walkable, else a face to place against |
| `MovementAscend` | step up one | jump + walk + mining | headroom clear, not under a falling block |
| `MovementDescend` | step down one | walk-off + landing recenter | landing solid, else becomes a fall |
| `MovementFall` | fall more than one (to 3 dry, ~23 with a water-bucket clutch) | tiered fall cost, plus bucket place | fall-height settings |
| `MovementDiagonal` | diagonal, can change Y | walk times about root-2 | both orthogonal corners passable |
| `MovementParkour` | sprint-jump a 1 to 4 gap, with parkour-place | jump + air time, plus place if needed | parkour settings, run-up clear |
| `MovementPillar` | pillar up (place under feet and jump), or climb ladder/vine | jump + place + penalty + mining, or ladder cost | throwaway available; width 1 for ladders |
| `MovementDownward` | straight down (mine floor, or descend ladder/water) | mine or ladder-down cost | (inside cost logic) |

## 3. Terrain-modifying navigation: the crown jewel

There is no separate "should I dig here" planner. The cost of changing the world is folded into
each movement's cost function, and execution is a small per-movement state machine that emits
simulated player inputs. Two hooks are the entire economic model:

- `CalculationContext.costOfPlacingAt(...)` returns a placement penalty (about one second by
  default) or `COST_INF` if placing is disabled, there is no throwaway block, or the spot is
  protected.
- `MovementHelper.getMiningDurationTicks(...)` returns `1 / toolSet.getStrVsBlock(state)` plus a
  break surcharge, plus the break cost of any block that would fall from above, or `COST_INF` if
  the block is unbreakable, a fluid, tool-less, or on the avoid-breaking list.

Because those enter the A\* edge weights, the planner *naturally* trades mining through a wall
against walking around it against bridging over a gap, by comparing tick totals. `FEATURES.md`
puts it well: with an efficient diamond pick it may mine through a stone barrier, while with a
wood pick it may be faster to climb over. In `MovementTraverse.cost`, a walkable floor gives a
plain walk cost plus the mining cost of every block in the entity's width-by-height volume that is
in the way; an unwalkable floor forces a bridge, which tries a side-place against any of five
neighbors and falls back to a more expensive sneak-back-place, or returns `COST_INF` if there is
nothing to place against. Execution consumes a real throwaway item from the fake player's
inventory (`selectThrowawayForLocation(...)`), and mining runs through the real
`interactionManager`, which is exactly why non-players (reach 0) cannot do it.

Above the movement layer sit `IBaritoneProcess` implementations that emit goals and placements:
`BuilderProcess` builds a schematic layer by layer, `MineProcess` scans for target blocks and sets
composite goals to reach and break them. Both are player-inventory-centric, and both are breadth we
do not need (we have datapack-JSON buildings and a hand-written mine loop already).

## 4. Threading, tick model, and why many-agents is the wrong shape

- **Planning is off-thread** on a shared pool that spawns a new OS thread per concurrent plan
  beyond four core threads (`baritone/behavior/PathingBehavior.java`, `Automatone.java`). Fine for
  a handful of bots; with dozens of villagers replanning at once it becomes dozens of threads.
- **Execution is on the server tick,** and the component calls `IBaritone.KEY.sync(entity)` every
  tick to push path data to clients for rendering. That per-tick network sync is pure overhead for
  a headless villager and would have to be stripped.
- **It plans ahead and splices:** it keeps a current and a next executor, precomputes the next
  segment when the current has under about 7.5 seconds left, and splices them. It recomputes when
  the entity is knocked off course, with `pathStart()` heuristics that account for standing off a
  block edge or being mid-jump. That recovery logic is cheap and worth lifting conceptually.
- **Per-entity state, shared only at the chunk cache.** Each entity owns its executors and each
  A\* builds its own node map; only a per-world compressed chunk grid (2 bits per block:
  air, solid, water, avoid) is shared. Baritone was built assuming one bot, and the full
  `ServerPlayerEntity` weight per villager (network handler, inventory, advancement tracker)
  compounds the cost. This is the wrong shape for many cheap NPCs on a local-CPU budget.

## 5. What to glean, concretely

High-value ideas, ordered by what remains missing after accounting for vanilla 1.21.1:

1. **Better frontier ranking and segmented planning, if measurements demand it.** Vanilla already
   returns a partial path, but crow-flies proximity can choose the surface above a mine instead of
   its ramp. Keep the explicit mine waypoints now; generalize only after repeated examples show a
   common scoring rule.
2. **A tick-priced movement cost catalog, when the graph gains heterogeneous actions.** A small
   set (traverse, ascend, descend, fall, pillar-up, ladder, bridge) priced in ticks makes
   mine-through, walk-around, and bridge-over comparable. Plain walking edges do not need a new
   unit merely to widen their search horizon.
3. **Break and place folded into the edge weight** (`costOfPlacingAt`,
   `getMiningDurationTicks`, with tool-speed `1 / strVsBlock`, the falling-block surcharge, and
   `COST_INF` gates). This is a ramp, shaft-descent, and floor-reaching planner: exactly the miner
   and builder pains. Keep it behind a per-job gate so only they pay for it.
4. **Pre-plan a next segment before the current one ends.** The chunk-border cutoff itself already
   exists in effect because vanilla's navigation region uses only loaded chunks. Segment handoff
   could make very long walks smoother without weakening that boundary.
5. **`pathStart()` recovery heuristics:** cheap anti-stuck logic for an entity that has been pushed
   off its path.
6. **Entity-size-aware clearance** (`requiredSideSpace`, tunnel sized to width-by-height) if we
   ever have workers wider than one block.

Overkill for us, skip: the fake-player infrastructure, Cardinal Components, client path rendering
and per-tick sync, `BuilderProcess` and the schematic engine (we have datapack buildings),
`FarmProcess`, `ExploreProcess`, the chat-command layer, the chunk-cache-to-disk system,
parkour and parkour-place, the water-bucket clutch, and the multi-process priority arbiter.

## 6. License

The top-level license is LGPL-3.0. A header audit found 298 files LGPL-3.0 and 7 files GPL-3.0
with a linking exception (the Requiem-derived fake-player glue). The README's "anime exception"
badge is a meme, not a legal term (UNVERIFIED as anything operative); the operative license is
LGPL-3.0. Both are copyleft. The implication for us:

- Depending on the jar (dynamic link) would keep our own code under our own license, but is a
  technical non-starter on NeoForge regardless.
- Copying or porting the source makes those files' license follow into our tree, and a NeoForge
  port is a derivative work that must ship LGPL-3.0.
- Reading it and reimplementing a needed algorithm in our own code is clean, because algorithms
  and cost models are not copyrightable, only the concrete expression is. Do that only where
  measurements show vanilla leaves a real gap.
