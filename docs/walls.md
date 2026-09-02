# Walls

A wall is the first building that is not a building. Every structure the village raises today
is a fixed box: one NBT template of known size, dropped on a prepared pad at a chosen site. A
wall is a perimeter. Its length and shape are read from the village at the moment it is built,
it follows the ground instead of flattening it, and it is priced by how far it runs rather than
by a footprint. That is why it was parked in `building-spec.md` (the `wall` and `gatehouse`
ladders, Phase 4) as "linear rather than a footprint, probably not a `Building` at all, blocked
on the site-selection pass." This document is that pass, for walls.

## What we are building

Two tiers, defensive, built late:

- **Wood palisade** (tier 1): pointed log posts and rails, tall enough to stop a mob, no walkway.
- **Stone brick wall** (tier 2): taller, crenellated, with a real gatehouse.

The stone wall is not a second building. It is an in-place upgrade of the palisade on the same
ring, the way `village_center_*_2` replaces `_1`: same identity, only the material delta is
charged (`BuildingUpgrade.effectiveCost`). A third `fortified wall` with a walkway is the
natural next rung, and is out of scope for now.

The wall is **triggered by safety**, not by a shortage. `UrbanPlanner` already grows a safety
need that spikes after deaths and threats; the wall is the large safety project it offers once
the village is established enough to be worth enclosing. It **rings the current extent once**,
with padding, and never moves again. Because our growth has no size cap and biases new buildings
outward, the town will eventually spill past the wall. That is intended: the wall marks where
the town stood when it decided it needed defending, an old-town core with newer growth beyond
it, which is how real walled towns aged. Building it late, on a mature extent, is what keeps the
outgrowing slow enough to read as history rather than as a bug.

## The route: a ring from `claimGrid`

The village has no perimeter concept, but it holds the exact material for one. `claimGrid`
(`Village.java`) is the 2D union of every building's footprint, a set of packed ground columns.
The wall's route is the **outline of that set, pushed outward by a padding margin**, closed into
a loop.

The ring is computed **once**, when the wall project begins, and stored on the village as new
save state (the ring path plus the current tier). It is never recomputed. The stone upgrade
re-walks the stored ring; nothing re-derives it from a since-grown `claimGrid`.

Padding is sized so the town does not immediately outgrow the wall: enough that a few more
buildings still land inside, not so much that the wall rings empty fields. Proposed starting
point is to pad the claim bounds by roughly one building-search radius (`LocationValidator`
grows its radius as `20 * (1 + buildings/4)`), and tune from there.

The wall does **not** join `claimGrid`. Buildings must keep placing outside it (that is the
whole "ring once, let it outgrow" decision), so the ring is written into the world but stays
invisible to siting.

## Building it: a builder step, not a template

A wall cannot reuse `Building` / `StructureInProgress`: siting, cost, claim, planner selection,
and upgrade-fit are all keyed to an NBT bounding box, and a variable-length ring has none. The
right vehicle already exists in another corner of the codebase.

`PathStep` (`entities/ai/goals/work/PathStep.java`) is a builder work-step that lays blocks
procedurally along a route as the villager walks, from no template, at variable length, and it
follows terrain the honest way: it places at the walker's feet, so the path rises and falls with
the land. The wall is that same shape of tool.

**`WallStep`** (new, modeled on `PathStep`): the builder walks the stored ring and, column by
column, places a wall segment at the **surface height of that column**. It steps up and down
with the terrain and never cuts into it, which satisfies the standing rule from
`site-selection.md`, "a village changes the surface of the land, never its shape." Where
`PathStep` swaps one ground block to a path, `WallStep` raises a short stack.

The straight runs are **procedural**. For each column along the ring, place a post or panel by
rule, then cap it with the tier's crown: a rail for wood, crenellations for stone. Tiers are a
palette swap on the same rule, so wood and stone cost nothing extra to express. Corners and
terrain steps fall out of the per-column placement on their own, because every column stands on
its own surface.

The **gatehouse is the one authored piece**: a small hand-built NBT dropped where a gate
belongs, because it earns the detail and does not need to flex in length. Gates go where the
ring crosses the village's real traffic. There is no road graph to read (paths are `DIRT_PATH`
blocks in the world with no registry), so a gate is placed where the ring intersects the lines
from building centers to the campfire, the routes villagers actually walk, with a fallback of
scanning for existing `DIRT_PATH` where the ring passes.

## Making it actually stop mobs

"Real defense" is a constraint on the procedural rule, not a separate feature:

- **Height**: the run must be tall enough that a ground mob cannot jump it and has nothing
  adjacent to climb. Proposed: 3 solid blocks above the surface for wood, 4 to 5 for stone.
  Spiders are the known exception; a lipped overhang on stone is a possible answer, noted for
  later.
- **No gaps**: because each column sits at its own surface height, the run must fill the
  vertical seam wherever the ground steps, or a mob walks through the gap. `WallStep` closes
  each step down to the height of its lower neighbor.
- **Gates that seal**: an open arch is a mob highway, so the gate has to close at night or under
  threat. Settled: a wooden door hangs in each gateway. Our villagers already open and close
  doors as they path (`Person` sets `canOpenDoors`, `RealPerson` runs an `OpenDoorGoal`), so the
  gate shuts behind them and stands closed at night, while a mob — bar a hard-difficulty zombie,
  which can break a wooden door — cannot work it. A lowered portcullis or a guard-operated
  closure was the alternative; the door reuses behaviour the village already has and needs no
  new AI. The door is hung a single column wide below the lintel, so it fills the doorway with
  no gap for a mob to slip past.

## Where it plugs into planning

Because a wall is not a `BuildingInfo`, the planner cannot choose, cost, or track it as-is. The
hooks it needs:

- **Selection**: `UrbanPlanner` gains a wall option, offered when the safety need is high and
  the village is established (a building-count or population floor). It sits alongside the
  ranked NBT candidates, not inside the `Buildings` registry.
- **Cost**: priced per ring length (segments times a per-segment recipe), not per footprint.
  This is the "priced per segment" the spec always called for. **Priced at a tenth
  (2026-09-02):** one log raises ten blocks of wall (`WallTier.BLOCKS_PER_ITEM`), both in the
  bill the village checks before starting and in the builder's draw as it lays each column,
  the remainder of an item carrying over to the next column. At a block per log no village
  ever afforded a wall: Wildflower Downs, sprawling to a 740-segment ring, wanted 3700 logs
  and logged the shortage forever. Aaron's call: the wall may well be worth that many logs,
  and the village pays a tenth anyway.
- **Project**: the village runs it as its one `currentProject`, the same slot an NBT build uses,
  but backed by the ring and `WallStep` rather than a template and `StructureInProgress`.
- **Completion and upgrade**: on finish, the village records the wall's tier. The stone upgrade
  is offered later as a safety project too, re-runs `WallStep` on the stored ring with the stone
  palette, and charges only the delta.

## Status

Implemented: the ring from `claimGrid`, the save state, the terrain-following `WallStep`, the
wood-to-stone in-place upgrade, and the safety trigger (commits `e961b3c`, `5090616`), and
closing gates — a wooden door in each gateway that villagers work as they path and mobs cannot.
A dev command, `/vldev village wall <wood|stone>`, rings a village at once for inspection.

Concrete choices the code now makes, all one line to tune:

- ring padding: 8 blocks beyond the claim bounds,
- heights: 3 courses for wood, 5 for stone (a merlon on alternate stone columns),
- gateways: one at the midpoint of each edge, a wooden door below a lintel that faces the town
  so it opens inward, which villagers open and close as they path,
- material: one palette per tier (oak log, stone brick), not varied by biome; the wood tier is
  paid for by any log ([building-spec.md](building-spec.md), `Materials`) and placed as oak.

Deferred, and the honest gaps against "real defence":

- **The authored gatehouse.** A gateway is a plain door below a lintel; the hand-built gatehouse
  NBT the design calls for — a proper arch with the `gatehouse` GUARD station — still needs
  authoring in-world and capturing. The door already seals the opening, so this is a richer
  build over a working gate, not a defence gap.
- **Spiders.** A closed gate and an unbroken run stop a walking mob, but a spider still climbs a
  flush wall; the lipped overhang noted above is the answer, not yet built.

## Relationship to the rest of the docs

This unblocks the `wall` and `gatehouse` sections of `building-spec.md` (Phase 4), which were
explicitly waiting on the site-selection pass, and it is the perimeter case that
`site-selection.md` set aside as "linear, not footprint." `village-tiers.md` already treats a
wall as a village's growth boundary; here that boundary is drawn once and then outgrown by
design.
