# Village tiers

**This is the decided design for village progression.** Every village has a tier — its
classification. The ladder ships as five tiers:

> **camp → hamlet → village → town → city → metropolis**

**A tier is a read-out of emergent growth, never a gate.** It is computed from the
village's population — the thing that actually dictates what a city is — and it
restricts nothing: no building unlocks, no population caps. What a village can build is
governed by resources and space (below); how big it gets is governed by the campfire
loop in [population-and-labor.md](population-and-labor.md) (beds are the housing cap —
a camp holds 4 people *because its center has 4 beds*, not because a rule says camps
hold 4). This mirrors Stronghold, whose settlements grow continuously with no rank
gates; the tier is the narrator's word for what the village has become.

Tiers are datapack content, like buildings: JSON at `data/<namespace>/kithkyn/tiers/`,
loaded by a `SimpleJsonResourceReloadListener` exactly like `BuildingDefinitionLoader`.
Addons can extend the ladder upward (metropolis and beyond) or rebalance thresholds by
overriding files — no Java changes.

## Tier definition format

`data/kithkyn/kithkyn/tiers/camp.json`:

```json
{
  "rank": 0,
  "population": 0,
  "idle_cap": 2
}
```

`data/kithkyn/kithkyn/tiers/hamlet.json`:

```json
{
  "rank": 1,
  "population": 5,
  "idle_cap": 4
}
```

- `rank` orders the ladder; `population` is the minimum population that classifies a
  village at this tier. The shipped ladder (all tunable datapack numbers): camp 0,
  hamlet 8, village 24, town 64, **city 256, metropolis 512**, with 1024 as the largest
  settlement the ladder anticipates.

  These numbers are deliberately large (decided 2026-08-26). A village is not meant to
  finish: it keeps sprawling, and a city should read as a city rather than as thirty-six
  people. Reaching the top of the ladder does not end anything — the brain keeps choosing,
  including choosing to do nothing, and keeps responding as buildings are damaged, workers
  die, and shortages arise.

  **A performance caveat that comes with the ambition.** The #62 benchmark measured a
  village member at roughly 0.086 ms of tick time, so a thousand simultaneously simulated
  residents would be far past the 50 ms budget on their own. In practice only the residents
  in loaded chunks tick, and a settlement that large spans far more chunks than any player
  loads at once, so the real ceiling is "how many are near you", not "how many exist". That
  is worth measuring before anyone celebrates a metropolis.
- `idle_cap` is the campfire reservoir size from
  [population-and-labor.md](population-and-labor.md), owned by the tier: a camp attracts
  a couple of drifters to its fire, a city a crowd. This is the one knob a tier carries,
  and it shapes *inflow pacing*, never what can be built.
- The tier id is the file id (`kithkyn:camp`). The translation key is always derived as
  `kithkyn.tier.<path>`; there is no `display_name` field in the codec.
- Attractiveness needs no explicit role here: it already governs whether population
  grows at all, so a starving village never reaches city numbers. The gate is implicit.

**Tier only rises** (high-water mark, checked on the village's own slow tick; `VillageBrain`
has no tier concept). A city that
loses its people to a raid is still called a city — a ghost of one. Promotion is a
celebratory journal entry once the brain lands. Today promotion logs a line and broadcasts
to players; no journal exists in code yet.

## The camp: how a village starts

A new village is rank 0 with the **founding set** (see building-spec.md, "How a village
starts"): the village center (`village_center_plains_1`), the little mine
(`mine_plains_1`), and a storehouse (`storehouse_plains_1`), all placed free as one camp
plat around the campfire. The center's beds ARE the starting housing — **4 beds**, so a
camp supports up to 4 villagers with no houses built (the existing `processNewBuilding`
bed-registration makes this free; the renamed center still defines 2 beds and its miner
and guard stations until the content pass strips it to the decided 4-beds-plus-builder
shape). Founding code places the set and skips payment; the definitions keep their
normal recipes so later copies cost.

**Death is permanent.** Villagers never respawn; population recovers only through the
campfire arrival model. A camp that loses its people to wolves is a dead camp — that
weight is the point, and it is what makes the brain's journal worth reading.

## What governs building: resources and space, not tier

With tiers as pure read-outs, construction is constrained by exactly two computed facts,
and they are peers:

- **Resources**: can the village afford this building's `cost`, checked against real
  container contents (`hasItemStackInVillage`)? "We don't have enough wood."
- **Space**: is there a legal site for this footprint near the village? "We don't have
  enough room." Site-finding (growing out of `LocationValidator` / `UrbanPlanner` /
  `LocationManager`) is its own upcoming design pass — it is the next hard rules problem
  and deserves its own doc. What is decided now: space is a first-class constraint that
  option generation must check, exactly like resources.

Two building fields support variants and sizes (documented now, implemented with the
building rules):

- `"category"`: groups variants — five house designs all declare `"category": "house"`.
  Variants differ in `cost` (a stone house for wood-poor villages); the brain picks
  among affordable ones.
- `"upgrades_from"`: names a building this one can replace on site. Its upgrade price is the
  positive increase from the predecessor's recipe. Constructing this level fresh combines the
  level-1 recipe with every calculated upgrade price in the chain.

### Separate buildings and on-site development

The planner prefers an on-site upgrade when its larger footprint fits around the old one,
because reusing the standing structure pays only the positive recipe increase. The larger
footprint may slide to extend in whichever horizontal direction has room while retaining the
old footprint and orientation. If no such extension can fit, the
higher level remains available as a separate building on another site, but it costs the level-1
recipe plus every upgrade increase through the target. Another lower-level building remains a
distinct, usually cheaper way to add capacity. The layout and the village's means decide which
shape its history takes.

### The three progression axes

**The table below is superseded.** The shipped id scheme is
`<category>_<variant>_<level>` from [building-spec.md](building-spec.md), which the loader
validates: `house_plains_1`, not `house_wood_s`. The axes it describes are still the
intent; only the naming changed.

|            | wood variant                    | stone variant                    |
| ---------- | ------------------------------- | -------------------------------- |
| small      | `house_wood_s` — costs logs     | `house_stone_s` — costs cobble   |
| medium     | `house_wood_m`, upgrades from s | `house_stone_m`, upgrades from s |
| large      | `house_wood_l`, upgrades from m | `house_stone_l`, upgrades from m |

1. **Village tier**: pure classification read from population. Narration, journal
   flavor, and inflow pacing (`idle_cap`) — gates nothing.
2. **Building variant** (`category` + differing `cost`): same function, different
   materials — how a wood-poor village still gets houses.
3. **Building size** (`upgrades_from`): the same building, bigger — chosen when space
   runs out and resources have not (`blacksmith` → `blacksmith_2` in the current
   datapack is this axis).

## Persistence

The current tier is objective village state, not brain strategy: it becomes a proper
`tier` field (string id, default rank-0 tier) on the Village codec — NOT a key inside the
brain's `strategy` CompoundTag, which is reserved for the LLM focus/reason/journal. The
field stores the high-water mark. An unknown tier id in a save (the datapack renamed or
removed it) currently loses that mark: the village is simply reclassified from its present
population, which can move it down. Renaming a tier id is therefore a breaking datapack
change. Storing the rank beside the id would fix it and has not been done.

## How the brain sees tiers

- The situation string states the classification and what growth needs: *"We are a camp
  of 3. More people need more beds and full granaries."*
- The option list the LLM picks from is filtered by resources and space before the model
  ever sees it — the brain picks among legal moves; it cannot invent an unaffordable or
  unplaceable building.
- Tier changes are journal moments: *"Today we are no longer a camp. We are a hamlet."*

## Implementation order

1. Tier loader + `tier` high-water field on Village + classification check on the slow
   tick. Ships alone: villages get named tiers with zero behavior change.
2. Per-tier `idle_cap` wired into the campfire arrival path (with the campfire refactor).
3. `category` on buildings + affordability-driven variant choice in the build rules.
4. Space: the site-finding design pass and its doc, then `upgrades_from` — tall-vs-wide
   only means something once space is a computed constraint.
