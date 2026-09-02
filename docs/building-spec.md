# Building spec: every building, variant, level, recipe, and unlock

**The catalogue below enumerates 36 categories; 22 of them survived the cut.** The totals in
this document count the full map of the possible, not the shipping set — see
[The cut](#the-cut) for which categories stand and why the rest went.

**Proposed, not yet decided. 69 of 224 structures exist.** Reality check before reading:
of the eleven "minimum playable" files, eight exist. `house` ships in all five variants at
levels 1 to 3 (1, 2 and 4 beds), so a village is no longer capped at the center's own beds,
and `farm` ships in all five variants at all three levels. `mine` ships at level 1 in all five variants, so the
founding set above is buildable for the first time. `stoneworks` ships at level 1 in all five variants,
with a real MASON occupation behind it, and `tannery` likewise with TANNER. `hunting_lodge` ships likewise with HUNTER, and
`fishery` with FISHER. **All eleven minimum-playable buildings now exist.** Still
missing: levels 2 and 3 of `mine`, `stoneworks`, `tannery`, `hunting_lodge` and
`fishery`.

**One occupation exists only as a name in these tables**: HERDER is not in the
Occupation enum, and a definition naming one fails the codec
outright, so such a building cannot load at all. MASON and TANNER were added when
their buildings shipped. Anything below that names a worker should be checked
against the enum before it is authored.

**A mine cannot ship its shaft.** A structure's y=0 lands on the topmost solid block
(`InstantBuildStructure.setOriginLocation` subtracts one from the WORLD_SURFACE
heightmap), so nothing in a file can sit below ground: the footprint below is dug at
runtime. `mine_*_1` is therefore a headframe over an open mouth, and its MINER station
sits in the middle of that mouth so `WorkInMineGoal` deepens the hole rather than
undermining the apron. The 7x7 footprint in the table is the headframe, not the mine.

**No house or farm has a `cost` yet**, so nothing gates building one. The recipes, and the
sprawl-versus-upgrade pricing the section below argues about, are still unset.

**Crop variety is not a capability, and must not become one.** A farmer plants whatever
seeds the village holds (`TillSoilGoal.PLANTABLES` covers wheat, beetroot, pumpkin, melon,
carrot, potato and sweet berries), so a player handing a level-1 farm carrots gets carrots.
What a farm level changes is physical: field size, and which seeds its barrels arrive
stocked with. The tables below read as though a level unlocks vegetables; it does not.
One consequence worth keeping: `TillSoilGoal` treats bare farmland as somewhere to plant,
so melon and pumpkin need a fruiting lane the farmer will leave alone. Podzol is that lane
— it is valid ground for fruit and is the one dirt-family block absent from `TILLABLES`.

`upgrades_from` is live: a village offers an upgrade of something it owns alongside new
buildings, rebuilds it in place, and keeps the building's identity so its workers keep their
jobs — see [How upgrading works](#how-upgrading-works). Several
shipped files contradict the rules below (a level-1 church granting ENCHANTING alone, a
watchtower with two guard stations, a storehouse with eleven containers). The footprint
size classes are invented numbers and measured 1.4x to 20.5x off against real candidates.

The complete enumeration behind [buildings.md](buildings.md),
which holds the reasoning. This file is the reference: what exists, what it costs, and what the
village can do once it stands. It is the input to sourcing structure files, so the manifest at
the bottom is the working checklist.

Every count in the manifest is derived from the tables in this file, so the two cannot drift
apart: if you add a variant above, the totals below are what change.

## How a village starts

A village founds with **three buildings**, placed free:

| Building | Contents |
| --- | --- |
| `village_center` | 4 beds, a chest of the campers' own, a campfire and a bell outside, one BUILDER station |
| `mine` | the MINER station |
| `storehouse` | two barrels |

That is the whole camp. Four beds are the entire starting housing cap, so a camp supports four
people until it builds a house. The storehouse's two barrels are the entire village inventory;
the camp circle's chest belongs to the four who sleep there. Two
jobs exist, builder and miner, so at most two of the four people are employed and the rest idle
at the fire where the campfire model wants them
([population-and-labor.md](population-and-labor.md)).

**A new camp is defenceless.** No GUARD station exists anywhere in the founding set, which is
deliberate: danger is the pressure that makes the first watchtower worth building, and deaths
already feed attractiveness, so the cost of having no guard is priced in without a rule saying so.

### The camp is placed as one plat

**Not implemented as described.** Founding currently places the center and then each
companion independently at a fixed offset, each snapped to its own heightmap, with no
composite footprint and no site check. The intent below stands.

The three buildings are not sited independently. Founding places a single composite footprint,
roughly 20x20, with the campfire and bell at its center and the three 7x7 buildings arranged around
it facing the fire. One site check, one claim, and the camp reads as a camp by construction rather
than by luck.

It also composes forward: once site-finding lands, the founding check is just the composite
footprint through the same gate as any other placement, with no special path.

**The consequence to watch:** founding needs a 20x20 site where adding a building later needs a 7x7
one. Where a village *can* be founded is therefore much pickier than where it can grow, and in
mountains, dense forest, or broken terrain that gap is large. If founding starts failing or
clustering onto flat ground in testing, this is why, and the fix is either a tighter plat or
letting founding pay preparation cost that ordinary placement would refuse.

## What is actually needed

36 categories is a map of the possible, not a plan. The practical core is much smaller, and it
is the only part that has to exist for the game to be a game:

> `village_center`, `house`, `storehouse`, `mine`, `lumberjack`, `stoneworks`, `farm`,
> `hunting_lodge`, `fishery`, `well`, `watchtower`, `blacksmith`, `market`

Thirteen categories, and a village that founds, feeds, houses, gathers, arms, defends, and trades.
Everything after that in this file is **sketched, not committed**: it is here so the shape of the
system is visible and so nothing gets designed into a corner, not because it is scheduled. Treat
phases 3 and 4 in particular as a record of what would fit, to be cut freely.

## Id scheme

`<category>_<variant>_<level>`, all lowercase, level always explicit:

```
house_desert_1        cottage, desert variant
house_desert_2        upgraded in place to a house
blacksmith_plains_3   foundry
mill_watermill_1      variant is a name, not a family, where the shape differs
```

The structure file at `data/villagelife/structure/<id>.nbt` shares the id exactly, so a definition
and its structure are never out of step.

**This supersedes the `house_wood_s` sketch in [village-tiers.md](village-tiers.md).** That form
encoded material and size; this one encodes variant and level, which is what the two axes actually
are.

Levels are never built from scratch. A level-2 building exists only by upgrading a level-1 one, so
a village always wears its history.

## Cost, and space

These are two separate questions and the spec keeps them separate.

**Cost is a recipe.** A flat list of items and counts, exactly the `cost` array the datapack
already uses. Nothing abstract, no points, no derived unit. The recipes below are the plains
variant of each building, and since 2026-09-01 every other variant's as well: a building costs
the same whatever family it is built in.

**Every building is now priced.** The recipes in the tables below are the original
sketch; what actually ships is derived from each structure's own block count, and
three rules hold it together. Each is a way a catalogue quietly becomes
unbuildable:

- **Only name what a worker puts into storage.** The miner (stone pickaxe) yields
  cobblestone, sand, sandstone and iron; the lumberjack yields logs and oak
  planks; the mason turns cobblestone into stone and stone brick. **Nothing
  produces glass, wool, or non-oak planks**, so a recipe naming them can never be
  paid however honestly it describes the building. **Wood is wood and stone is
  stone** (`village/buildings/Materials.java`): a recipe's "oak log" is paid by any
  log, its "oak planks" by any plank, and its "cobblestone" by any cobblestone,
  cobbled deepslate or sandstone, whatever the guard felled or the miner dug. A
  mangrove-swamp camp with a store full of mangrove was otherwise stuck on its
  first house for good. Logs also pay for planks, four to a log, with nobody
  sawing; planks never pay for logs. Because one recipe can ask for both, the
  recipe is settled as a whole, log lines first, so a log is never counted twice.
- **Never price a building in what it alone produces.** The lumberjack is the only
  source of planks, so it costs cobblestone and nothing else: a village that has
  only founded, and so has only a miner, must be able to build it. The stoneworks
  likewise costs cobblestone in *every* variant, including the snowy and desert
  ones built from the very blocks it exists to make.
- **One recipe per building, whatever its family.** Every variant of a category
  and level costs exactly what its plains variant costs, in generic wood and
  stone, so a desert or snowy village pays no differently and no recipe waits on a
  material only its own family produces. The loader warns when a datapack breaks
  this, because the planner and the builder both take the price of a building to
  be the price of its category.

Which gives the bootstrap order a village actually follows: found (centre, mine,
storehouse, free) → miner digs cobblestone → **lumberjack**, in cobblestone alone
→ logs and planks → everything timber → **stoneworks**, in cobblestone → stone
brick for the few buildings priced in it. A desert mine cuts through sand and
sandstone before it reaches stone, and sandstone pays a stone cost like any
cobblestone, so a desert camp bootstraps on the same schedule.

**Upgrading costs more than sprawling**, by construction. Two level-1 houses come
to 94 units for two beds where one level-2 costs 120; four level-1s cost 188
where a level-3 costs 253. Building wide stays the cheaper move, which is what
[village-tiers.md](village-tiers.md) asks for.

**Space is a fit check.** How much room a building needs comes from its own dimensions, not from
any number in this file. Whether a site can take those dimensions, and what it would cost to clear
one that nearly can, is [site-selection.md](site-selection.md).

### Variants are a look, not a recipe

**A variant is a family's shape of the same building** (decided 2026-09-01, superseding
[#50](https://github.com/Quzzar/villagelife/issues/50), which had made variants competing
recipes). Every variant of a category and level costs the same recipe, the plains one, in
generic wood and stone. Which variant a village raises is settled once, at founding, by the
biome it stands in ([buildings.md](buildings.md), "Regional variants and biomes"), and kept
for its life, so a village reads as one place. The planner never chooses between variants: it
sees one building per category, the village's own family or plains where that family has no
such building, and the same recipe whichever it is.

Two things follow:

- **A wood-poor village is not rescued by a stone variant.** It is rescued by the market and
  by "wood is wood": the guard's woodland chop pays a log cost with any wood, and logs pay a
  plank cost without a saw. A desert camp mines sandstone, and sandstone is stone.
- **The named specials stay variants.** A watermill or an igloo is a family's shape for its
  category, priced like the category. If a site cannot host one, that is site selection's
  business, not the variant system's.

What does still change with the family is what the building is made of, because the
structure file does: spruce in a taiga house, sandstone in a desert one. That is the template's
business, not the recipe's. Making the built blocks follow the wood actually paid, so a plains
village given spruce raises spruce houses, is a separate piece of work that this rule leaves
room for.

The upgrade to level 2 is priced at 1.5x the level-1 recipe, and level 3 at 3x. That means
upgrading always costs more than putting up a second level-1 building of the same category, which
is worth knowing but is **not a rule anyone implements**. Nothing tells the brain to prefer
sprawl. A village with land finds the cheaper option in its list and takes it; a village hemmed in
by a ravine never sees that option, because site-finding found nowhere to put it.

### Upgrade prices are never derived from the structure

That is not a detail, and getting it wrong silently deletes the whole sprawl-versus-tall choice.
Measured on the real houses we picked:

| | Solid blocks | Beds | Blocks per bed |
| --- | --- | --- | --- |
| tier 1, 7x7 | 153 | 1 | 153 |
| tier 2, 7x13 | 270 | 2 | 135 |
| tier 3, 7x11 | 340 | 4 | **85** |

**Bigger buildings are dramatically cheaper per bed**, because one roof and four walls get reused.
Four small houses cost 612 blocks for four beds; one big house costs 340 for the same four, 44%
less. So a cost that tracks the block count makes upgrading strictly better, and a village would
never sprawl.

Pricing upgrades off the level-1 recipe instead:

| | Cost | Gain | Sprawl alternative |
| --- | --- | --- | --- |
| tier 1 build | 153 | 1 bed | |
| upgrade to 2 | 230 | +1 bed | 153 for a second small house |
| upgrade to 3 | 459 | +2 beds | 306 for two more small houses |

Four beds by sprawling costs 612. By upgrading, 842, **38% more**. The invariant holds against
real geometry rather than assumed geometry.

So: **a level-1 recipe may be derived from its structure's block count. An upgrade price may
not.**

## Capabilities

What a village can do is the union of what its finished buildings grant. Capability comes from
construction, never from population or village tier.

### A building grants permission, not product

**A blacksmith does not produce iron tools. It makes iron tools possible.** The village still
needs real iron, dug by a real miner, sitting in a real chest, before a single tool exists.
Nothing in this system spawns items.

Every capability below is gated twice: once by a building standing, and once by the materials
being present. That is what keeps the simulation legible in ordinary Minecraft terms. A village
with a foundry and no diamonds has exactly what a player with a crafting table and no diamonds
has.

**The village inventory is not a number.** It is literally every container inside the
village's claim, read together, and nothing is abstracted or tracked in parallel. A recipe
is satisfiable when those chests hold those items.

**One pool for planning; real walking for work** (decided on
[#49](https://github.com/Quzzar/villagelife/issues/49)). The union above is what the brain
counts when it asks "can we afford this", so affordability is simple and cannot deadlock on
geography. Workers do not get that luxury: a villager who needs an input walks to the
nearest container that actually holds it and carries it back, and a villager with output in
hand walks it to the nearest container with room. Goods are always in some chest, visibly,
and moving them is a trip someone makes.

The consequences that follow, and which the implementation owes:

- **The storehouse is capacity, not a special inventory.** Every building's chest counts
  toward the same pool; a storehouse is simply the building whose job is holding a lot. It
  stays in the founding set because a camp needs somewhere to put things.
- **A home's own chest is not in the pool** (built 2026-09-01). A definition may list a
  container under `personal_containers` instead of `containers`: every house does, and so does
  the bedside chest of a workplace with a live-in bed and more than one chest (the church, the
  lumberjack hut, the level-1 watchtower). It belongs to whoever sleeps there, shared between
  them. It is never registered as village storage, so no fetch reads it, no deposit fills it,
  the planner does not count it as a store, and the quartermaster's sweep never sees it
  (`village/PersonalChest`). What goes in is whatever kinds of thing its residents choose to
  keep at bedtime, any number or none ([worker-loops.md](worker-loops.md), "A chest of their
  own"). The camp circle is a home for four, so its one chest is theirs, shared (Aaron,
  2026-09-01): a new village's storage is the storehouse's two barrels. A workplace with a
  single chest that is first a workplace, the level-1 blacksmith and the level-2 watchtower,
  keeps that chest shared, and the upper blacksmith's two chests both sit in the workshop, well
  away from the bed, so it has none either. When a home is rebuilt for an upgrade its chest is emptied into village storage with
  the rest, since the alternative is the residents' things on the floor of a building site; and
  a leaver's chest stays with the house, for whoever moves in next.
- **A fetch can fail even when the pool says yes** — the chest holding it is unloaded, or
  unreachable. That emits the ordinary shortage event and a personal-log entry, and the
  worker gives up rather than spinning.
- **A full chest is a storage shortage.** A worker with nowhere to deposit carries to the
  next container with room; when none has room, that is an event, and it is what should make
  a village decide to build another storehouse.

**Who may take from a chest.** Any villager, for a real need: there is no ownership between
residents over the village's stores. A home's own chest is the one exception: only the people
who sleep there put things in it, and no worker's fetch ever reads it. The player may freely
PUT items into any village container, which is a gift and nothing else. The player TAKING
items, or breaking a chest, is **theft, but only if a villager sees it happen**: awake, within
about sixteen blocks, with line of sight. A home's chest counts as a village container here:
robbing someone's house is theft the same as robbing the storehouse. An
unwitnessed theft genuinely costs nothing, because nobody knows. A witnessed one is
recorded by the witness as a fact, and how much they hold it against you is their own
judgement on reflection; it reaches the rest of the village only as gossip through the
relationship web, not as an instant village-wide verdict. The consequences are detailed in
[#64](https://github.com/Quzzar/villagelife/issues/64).

### How upgrading works

Decided on [#56](https://github.com/Quzzar/villagelife/issues/56). A level above 1 is only
ever reached by upgrading; nothing is built fresh at level 2.

**Rebuilt block by block on the same origin.** An upgrade is ordinary construction with the
new template over the old, using the machinery that already builds everything else, so you
watch it happen. Footprints grow between levels, so the larger footprint is fit-checked at
upgrade time with the site-preparation scorer; no room means the upgrade is refused and the
level-1 building simply stands. Nothing is reserved in advance.

**The chest is emptied into the rest of the village before work starts.** Contents are
carried out to other village containers, which the one-pool decision on
[#49](https://github.com/Quzzar/villagelife/issues/49) already makes the natural move.
Nothing is ever destroyed. If the village genuinely has nowhere to put it, that is a storage
shortage and the upgrade waits rather than proceeding.

**The worker keeps their job and waits.** The assignment is held, the station is unusable
for the duration, and the worker idles at the campfire until it is ready. The alternative —
returning them to the pool — would let stat-based placement quietly reshuffle the village
on every upgrade, and a smith who was a smith yesterday should still be one tomorrow.

**The building is unusable while it is being rebuilt.** That is the honest cost of the
upgrade and the reason a village should think before starting one.

**Stations must be reconciled, not assumed.** Most upgrades ADD stations (a watchtower goes
from one guard to two), and `processNewBuilding` only ever runs at construction, so a
definition that gains stations registers nothing. Shrinking and reordering are already safe:
`releaseInvalidAssignments` releases held assignments whose station no longer matches and
drops stale openings. Growing needs the additive half — walk the current definition's
stations, count what is represented across booked and open assignments, and register the
missing ones. Run it after any upgrade and after any datapack reload, which fixes the same
bug in its other guise: an author editing a definition on a live world.

**Demolition does not exist.** Buildings go up and improve; nothing chooses to remove one.
That is deliberately deferred until villages actually run out of space.

### What a capability is at runtime

Decided on [#55](https://github.com/Quzzar/villagelife/issues/55).

**Derived, never stored.** A village's capabilities are simply the set of strings granted by
the buildings currently standing, recomputed whenever a building is added or lost. Nothing
persists, so nothing can desync from reality, and a datapack can invent a capability name
without touching Java. This supersedes `Building.Benefit`, the closed enum that exists today
and is read by nothing: it should be deleted rather than extended.

`grants_if` resolves in the same derivation as a fixed point — grant everything
unconditional, then re-evaluate the conditional grants until nothing new appears. Two
buildings that each require the other's capability simply never grant, which is the correct
quiet failure rather than a crash.

**Capabilities gate what the village can MAKE, never what it can build.** No blacksmith
means no iron tools; no church means no healing. Construction is gated by materials and
space alone, so a village can always build its way toward a capability it lacks and can
never lock itself out.

**Production is opportunistic, decided by the worker.** Nobody schedules it. A blacksmith
standing at their station looks at what the village is short of and makes it from the shared
pool, using the same "what do we lack" reasoning the planner already applies to buildings.
There is deliberately no request queue: a miner with a worn pickaxe does not file a demand,
the smith simply notices the village is short of pickaxes. If that proves too vague in play,
a demand signal is a later addition, not a prerequisite.

**The brain is never told what it cannot do.** Capability filtering happens before the model
sees anything, exactly as building options are filtered by affordability, so it chooses among
legal moves only and cannot fixate on an unreachable ambition.

| Group | Capabilities |
| --- | --- |
| Tools | `TOOLS_STONE` (baseline), `TOOLS_IRON`, `TOOLS_DIAMOND` |
| Armor | `ARMOR_LEATHER`, `ARMOR_IRON`, `ARMOR_DIAMOND`, `SHIELDS` |
| Smithing | `REPAIR`, `SMELTING` |
| Food | `GRAIN`, `MEAT`, `FOOD_COOKED`, `FOOD_PRESERVED`, `FOOD_BAKED`, `ALE` |
| Materials | `LOGS`, `PLANKS`, `STONE`, `CUT_STONE`, `ORES`, `FUEL`, `BRICK`, `GLASS`, `STAINED_GLASS`, `CLOTH`, `DYED_CLOTH` |
| Military | `PROTECTION`, `SOLDIERS`, `VETERANS`, `ARROWS` |
| Services | `WATER`, `HEALING`, `ENCHANTING`, `LEARNING`, `POTIONS`, `TRADE` |

`ATTRACTIVENESS` is deliberately not in that list, because it is not a capability. It is the
village's existing 0-to-100 score from [population-and-labor.md](population-and-labor.md), the
thing that already governs whether anyone moves in. Buildings that "raise morale" raise *that*,
and the brain reads it directly: people are unhappy, can we do something about it. There is no
second happiness stat.

Beds and containers are not capabilities either. They are beds and containers, counted by looking
at them.

Three capabilities need more than one building:

| Capability | Requires |
| --- | --- |
| `ENCHANTING` | `church_2` and `library_1` |
| `TOOLS_DIAMOND`, `ARMOR_DIAMOND` | `blacksmith_3` and `mine_3` |
| An inn's lift to `ATTRACTIVENESS` | `inn_1` and a brewery actually supplying ale |

### How a conditional grant is declared

**Implemented** as of [#68](https://github.com/Quzzar/villagelife/issues/68). `grants` is a
list of capability strings; `grants_if` is a list of objects naming a capability plus
`requires_capability` and/or `requires_supply`. `VillageCapabilities.resolve` walks the
standing buildings, takes everything unconditional, then re-evaluates the conditional ones
until a pass adds nothing. `/vldev village capabilities` prints the resolved set and what
each building contributes, marking conditional grants as granted or withheld with the
requirement that decides it.

One rule worth stating because it caught the market: **currency is not a supply.** A
village's treasury is physical emeralds sitting in a village container, so a supply
requirement naming emeralds would be satisfied by the village's own money. Emeralds are
skipped when checking supply.


**Conditions name capabilities and supplies, never building ids.** A church should not care which
library variant the village built, or whether a future datapack adds a third way to get
`LEARNING`.

```json
{
  "grants": ["HEALING"],
  "grants_if": [
    { "capability": "ENCHANTING", "requires_capability": ["LEARNING"] }
  ]
}
```

| Kind | Checked | Behaviour |
| --- | --- | --- |
| `requires_capability` | When the village's capability set is recomputed, on a building finishing or being lost | Static. Either the village has `LEARNING` or it does not. |
| `requires_supply` | On the brain's slow tick, against real container contents | Dynamic. An inn with no ale grants nothing this tick and grants again when the brewery catches up. |

Capability resolution is a fixed point: grant everything unconditional, then re-evaluate
`grants_if` until nothing new appears. Two buildings that each require the other's capability
simply never grant, which is the correct and quiet failure.

---

## The cut

Decided on [#57](https://github.com/Quzzar/villagelife/issues/57). The catalogue below is
**22 categories**, down from 36. The filters were the ticket's own: a category needs a work
loop describable in one sentence, must not be another category wearing a different hat, and
must not exist solely to feed something else. Stronghold, the stated model, shipped roughly
25 building types.

**Survivors (17).** Infrastructure: `village_center`, `house`, `well`, `storehouse`,
`watchtower`. Extraction: `farm`, `lumberjack`, `stoneworks`, `mine`. Food:
`hunting_lodge`, `fishery`, `bakery`, `butchery`. Craft: `blacksmith`. Civic: `market`,
`church`, `tavern`.

**Cut, and why.** These are gone rather than deferred, so nobody re-proposes them in six
months:

- `granary` — absorbed into `storehouse` — it was a storehouse that only held food
- `mill` — collapsed into `bakery` — the baker grinds their own grain; a step nobody watches is complexity with no audience
- `charcoal_burner` — collapsed into `blacksmith` — the smith burns their own charcoal
- `kiln` (with the `pottery` and `glassworks` it had absorbed) — cut — Aaron's call: the village needs no dedicated brick/glass producer. No building costs brick, and glass is bought through the market like any other traded good (`glass_pane` derives to an authored value, so it is always purchasable). This takes the survivor count from 22 to 21.
- `weaver` and `workshop` — folded into `butchery` — Aaron built the old `tannery` as a livestock butchery (a fenced pen of cows and sheep with a worker who makes meat, leather, and wool), which produces leather directly, so there is no separate leather-goods building. The `tannery` is accordingly renamed `butchery`.
- `pasture` — folded into `butchery` — the butchery already holds the cows and sheep; a separate livestock building is redundant.
- `brewery` — cut — Aaron's call: pure flavour, and there is no ale item for the brewer to make.
- `library` — cut — Aaron's call: knowledge grants nothing the design uses.
- `inn` — renamed `tavern` — it is a bar that draws wanderers (grant `WANDERERS`), not lodging: no beds and no residents. Together with the four cuts above, this takes the survivor count from 21 to 17.
- `mushroom_cellar` — a `farm` variant, not a category
- `apiary` — no worker and no capability it uniquely grants
- `graveyard` — its only purpose was MORALE, and there is no second happiness stat
- `fletcher` — needs an arrow economy that does not exist
- `armoury` — a `blacksmith` at level 3, not a separate trade
- `alchemist` — needs a potion system that does not exist
- `barracks` — needs a military system beyond guards; the `watchtower` carries defence for now
- `training_yard` — a stat on a building that no longer survives

Walls and gatehouses are not in the count either way: they are linear rather than footprints
and are probably not `Building`s at all.

**The structure manifest below predates these cuts** and still counts 173 files across 36
categories. It needs regenerating against the 21 (it still enumerates the cut `kiln`,
`pottery`, and `glassworks`, along with every other cut category).

## Beds belong to houses

Decided on [#61](https://github.com/Quzzar/villagelife/issues/61). **A workplace never
contains a bed.** Blacksmiths smith; houses house. The one exception is `village_center`,
because a camp is people sleeping around a fire before there are any houses, so the centre
carries the starting beds and nobody minds that a station shares the building.

This settles an incoherence that had gone unnoticed: `population-and-labor.md` assigns beds
on arrival independently of employment, so a bed inside the blacksmith went to whichever
newcomer arrived next rather than to the blacksmith. With workplaces bedless, arrival-order
assignment is simply correct, and no employment-aware bed logic is needed. Villagers keep
the first free bed they are given and do not move house when their job changes, so a
villager may well walk across town to work.

It also makes housing the real growth lever: beds are the population cap, houses are the
only source of beds, so a village that wants to grow must build houses. And it makes both
halves of the vanilla template library usable — the 51 bed-only templates as houses, the 87
station-only ones as workplaces — where pairing them made most of vanilla useless to us.

**The catalog's bed columns below predate this rule** and still list beds on workplaces.
They are wrong wherever they do; the shipped definitions have already had those beds
removed.

## The catalog

### Core and civic

#### `village_center`  (founding building)

Worker: **BUILDER**  ·  Phase 1  ·  Variants: `plains`, `taiga`, `snowy`, `desert`, `savanna`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | camp circle | 7x7 | placed free at founding | 4 beds, a chest of the campers' own, campfire, bell, BUILDER station |
| 2 (upgrade) | village hall | 11x11 | 48 oak log, 76 oak planks, 80 cobblestone, 12 glass, 8 wool, 8 iron ingot | +2 beds, +2 containers, LEADER station: the brain's voice, shown in the UI |
| 3 (upgrade) | town hall | 21x21 | 100 oak log, 148 oak planks, 156 cobblestone, 28 glass, 16 wool, 16 iron ingot | +4 beds, +3 containers, festivals: a periodic lift to ATTRACTIVENESS |

Founding building, placed free. Four beds, one chest, a campfire and a bell outside, and a single BUILDER station. That is the entire camp. The four beds are the whole starting housing cap, so a camp supports four people until it builds a house. The chest is the campers' own (`personal_containers`), shared by the four who sleep there, not village storage: the camp keeps its goods in the storehouse.

#### `house`

Worker: **none**  ·  Phase 1  ·  Variants: `plains`, `taiga`, `snowy`, `desert`, `savanna`, `igloo`, `stilt`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | cottage | 7x7 | 12 oak log, 20 oak planks, 20 cobblestone, 4 glass, 2 wool | BEDS 2 |
| 2 (upgrade) | house | 11x11 | 20 oak log, 28 oak planks, 32 cobblestone, 6 glass, 4 wool | BEDS 4 |
| 3 (upgrade) | longhouse | 15x15 | 40 oak log, 60 oak planks, 64 cobblestone, 12 glass, 8 wool | BEDS 7 |

The housing cap, the most numerous building in any village, and where regional identity actually reads. Gets more variants than anything else for exactly that reason. `igloo` is a cheap snowy-only L1; `stilt` is the wetland answer.

Every house level also has one chest, listed as `personal_containers`: the residents' own, not
the village's (see "A home's own chest" under
[A building grants permission, not product](#a-building-grants-permission-not-product)). The
grants column names beds alone because beds are what a house gives the village; the chest is
what it gives the people who live in it.

#### `well`

Worker: **none**  ·  Phase 1  ·  Variants: `plains`, `desert`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | well | 5x5 | 6 oak log, 8 oak planks, 8 cobblestone | WATER |
| 2 (upgrade) | fountain | 7x7 | 8 oak log, 12 oak planks, 12 cobblestone, 2 glass | WATER, raises ATTRACTIVENESS slightly |

The desert variant is a covered cistern, because open water evaporates and a desert village that digs an open well is a village that has not lived in a desert.

All five wells stand ON the ground with the pool at rim level, as authored (2026-09-01). The
definitions declare `"sink": -1`, so the base course, a solid ring of the variant's stone,
sits on top of the ground's top block, with the water and its trapdoor-and-fence rim one above
that. (`sink` seats a structure that many layers below the ground plane; a negative value
raises it. The well was tried at 1 and 0 first and Aaron judged both a block too low in the
world. The ground is still prepared and claimed at the surface whatever the sink.) The well
used to leak from its rim for two reasons, both fixed for every building: the builder's
block-by-block build set the water down before the rim, so the pool spread over the ground and
every trapdoor placed into that spill was waterlogged and became a source; and placement ran
with vanilla's keep-liquids rule, which let a pond beside a site soak into one rim block and
then round the whole ring. Now the builder places every liquid-bearing block of a structure
last, and both placement paths ignore waterlogging, so a block set into water replaces it. A
rim placed before its water stays dry: verified on a bare platform, rim first and water last,
not one trapdoor or fence waterlogged. The pool was briefly moved a layer down into the base
on a wrong reading of the flow rules; Aaron restored the authored layout.

#### `storehouse`  (founding building)

Worker: **none**  ·  Phase 1  ·  Variants: `plains`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | storehouse | 7x7 | 8 oak log, 16 oak planks, 16 cobblestone, 2 glass | 2 barrels into the village inventory |
| 2 (upgrade) | great storehouse | 11x11 | 16 oak log, 24 oak planks, 24 cobblestone, 4 glass, 2 wool | 8 containers |
| 3 (upgrade) | warehouse | 15x15 | 28 oak log, 44 oak planks, 48 cobblestone, 8 glass, 6 wool | 20 containers |

Founding building, placed free. Two barrels: the whole of a new village's inventory, since the camp circle's chest belongs to the campers. Absorbed the granary: both were always the same chests read by the same code, so one category covers both.

#### `market`

Worker: **MERCHANT**  ·  Phase 2  ·  Variants: `plains`, `desert`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | market stall | 7x7 | 16 oak log, 24 oak planks, 24 cobblestone, 4 glass, 2 wool | a trading screen: the player buys and sells with the village, in emeralds |
| 2 (upgrade) | market | 11x11 | 24 oak log, 36 oak planks, 36 cobblestone, 6 glass, 4 wool, 4 iron ingot | the village spends its own emeralds on what the biome cannot make |
| 3 (upgrade) | trade hall | 15x15 | 48 oak log, 68 oak planks, 72 cobblestone, 12 glass, 8 wool, 12 iron ingot | better rates, wider stock |

The village's trade organ, and the *legitimate* alternative to taking from its chests. Needs a staffed MERCHANT like any other workplace; without a market there is no trade at all, for the player or the village. Levels grant capabilities rather than numbers: L1 access, L2 initiative (trading unattended), L3 better rates. The treasury is physical emeralds in this building's chest, and a village founds broke. Full design in [economy.md](economy.md).

#### `inn`

Worker: **INNKEEPER**  ·  Phase 3  ·  Variants: `plains`, `taiga`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | alehouse | 11x11 | 32 oak log, 48 oak planks, 48 cobblestone, 8 glass, 6 wool | raises ATTRACTIVENESS while ale sits in the village inventory |
| 2 (upgrade) | inn | 11x11 | 48 oak log, 68 oak planks, 72 cobblestone, 12 glass, 8 wool | raises ATTRACTIVENESS more, draws wanderers |
| 3 (upgrade) | tavern | 15x15 | 92 oak log, 140 oak planks, 148 cobblestone, 24 glass, 16 wool, 8 iron ingot | raises ATTRACTIVENESS most, recruits wanderers |

Consumes ale from the brewery. An inn with no ale is an empty room and grants nothing, which is the point: the attractiveness lift has a real supply chain behind it.

### Faith, learning, memory

#### `church`

Worker: **CLERIC**  ·  Phase 2  ·  Variants: `plains`, `taiga`, `desert`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | shrine | 7x7 | 16 oak log, 28 oak planks, 28 cobblestone, 4 glass, 4 wool | HEALING basic |
| 2 (upgrade) | church | 15x15 | 28 oak log, 40 oak planks, 40 cobblestone, 8 glass, 4 wool, 16 glass | HEALING, ENCHANTING (requires library_1), raises ATTRACTIVENESS |
| 3 (upgrade) | cathedral | 21x21 | 52 oak log, 80 oak planks, 84 cobblestone, 16 glass, 8 wool, 32 stained glass, 2 diamond | raises ATTRACTIVENESS strongly |

The clearest two-building capability in the catalog: ENCHANTING needs church L2 AND library L1, and neither grants it alone.

#### `library`

Worker: **LIBRARIAN**  ·  Phase 3  ·  Variants: `plains`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | scriptorium | 7x7 | 20 oak log, 28 oak planks, 32 cobblestone, 6 glass, 4 wool, 12 book | LEARNING, book production |
| 2 (upgrade) | library | 11x11 | 28 oak log, 44 oak planks, 48 cobblestone, 8 glass, 6 wool, 24 bookshelf | LEARNING, ENCHANTING support |

### Food

#### `farm`

Worker: **FARMER**  ·  Phase 1  ·  Variants: `plains`, `taiga`, `snowy`, `desert`, `savanna`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | croft | 11x11 | 16 oak log, 28 oak planks, 28 cobblestone, 4 glass, 4 wool | GRAIN |
| 2 (upgrade) | farm | 15x15 | 28 oak log, 40 oak planks, 40 cobblestone, 8 glass, 4 wool | GRAIN more, vegetables |
| 3 (upgrade) | estate farm | 21x21 | 52 oak log, 80 oak planks, 84 cobblestone, 16 glass, 8 wool, 8 iron ingot | GRAIN most, irrigation works poor soil |

The desert variant is terraced and irrigated: it costs more for the same yield, which is exactly what farming a desert should feel like.

#### `tannery`

Worker: **TANNER**  ·  Phase 1  ·  Variants: `plains`, `taiga`, `snowy`, `desert`, `savanna`

*Absorbs the old `pasture`.* Keeping cattle and taking beef and leather off them is
one job, not two buildings. **There is no separate "turn hides into worked leather"
step**, because a cow in Minecraft drops leather outright: a processing stage there
would be busywork with no material change behind it, unlike the mason, who turns
cobblestone into a block that genuinely does not otherwise exist.

The station is the water cauldron, vanilla's own leatherworker block, which reads as
the tanning vat; the smoker beside it cures the beef.

This is the settled half of the animal split. The other half is `hunting_lodge`,
which is deliberately the opposite shape: a hunter roams out after wild animals,
where the tanner cultivates a herd that stays put.

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | tannery | 15x7 | 16 oak log, 24 oak planks, 24 cobblestone, 4 glass, 2 wool | MEAT, LEATHER |
| 2 (upgrade) | stockyard | 15x15 | 24 oak log, 36 oak planks, 36 cobblestone, 6 glass, 4 wool | MEAT more, LEATHER more |
| 3 (upgrade) | ranch | 21x21 | 48 oak log, 68 oak planks, 72 cobblestone, 12 glass, 8 wool | breeding: the herd grows on its own |

Wool still matters as the bottleneck on beds, and the pen is what produces it: the shipped
butchery structures carry their starting cows and sheep in the template, and a HERDER
station beside the pen keeps the herd sheared, fed, and marked as farmed stock the hunter
will not touch (docs/worker-loops.md).

#### `hunting_lodge`

Worker: **HUNTER**  ·  Phase 1  ·  Variants: `plains`, `taiga`, `snowy`, `desert`, `savanna`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | hunter's camp | 7x7 | 12 oak log, 20 oak planks, 20 cobblestone, 4 glass, 2 wool | MEAT, LEATHER |
| 2 (upgrade) | hunting lodge | 11x11 | 20 oak log, 28 oak planks, 28 cobblestone, 4 glass, 4 wool | MEAT more, feathers, hides |

A camp, not a workplace: a shelter, a fire, a chest and an archery target on worn
ground. That is deliberate, and it is the half of the animal split that `tannery`
is not. A tanner cultivates a herd that stays put; a hunter roams after animals
that do not, so the hunter's building is somewhere to return to rather than
somewhere to stand all day.

The lit campfire is safe despite `VillagelifePoiTypes` registering a POI over lit
campfire states: the village reads its gathering point from the town centre's own
`gathering_point` field, and nothing consumes that POI.

Feathers at L2 are what make the fletcher possible at all.

#### `fishery`

Worker: **FISHER**  ·  Phase 1  ·  Variants: `plains`, `taiga`, `snowy`, `desert`, `savanna`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | fishing hut | 9x7 | 12 oak log, 16 oak planks, 16 cobblestone, 4 glass, 2 wool | MEAT (fish), WATER |
| 2 (upgrade) | fishery | 11x11 | 16 oak log, 24 oak planks, 28 cobblestone, 4 glass, 4 wool | MEAT more, docks |

**It grants WATER, because it contains a 2x2 of source blocks**, which is an
infinite water source in Minecraft. That does not make the `well` redundant: a
well is cheap and lifts attractiveness, a fishery feeds people. Neither ranks
above the other, they differ.

Carrying its own water is also what makes this shippable at all. A fishery is the
one category whose SITE matters, and site-selection does not understand
shorelines; a self-contained source means it can be built anywhere. Requiring a
real shore belongs with the level-2 docks.

Keys off adjacent water, not off a biome, so it serves coast, river, lake, and swamp alike.

#### `bakery`

Worker: **BAKER**  ·  Phase 2  ·  Variants: `plains`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | bake house | 7x7 | 16 oak log, 24 oak planks, 28 cobblestone, 4 glass, 4 wool | FOOD_BAKED: bread |
| 2 (upgrade) | bakery | 11x11 | 24 oak log, 36 oak planks, 40 cobblestone, 6 glass, 4 wool | FOOD_BAKED: pies and cake |
| 3 (upgrade) | guild bakery | 15x15 | 48 oak log, 76 oak planks, 80 cobblestone, 12 glass, 8 wool, 4 iron ingot | FOOD_BAKED at the best conversion ratio |

#### `butchery`

Worker: **BUTCHER**  ·  Phase 2  ·  Variants: `plains`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | smokehouse | 7x7 | 12 oak log, 20 oak planks, 20 cobblestone, 4 glass, 2 wool | FOOD_COOKED |
| 2 (upgrade) | butchery | 11x11 | 20 oak log, 28 oak planks, 32 cobblestone, 6 glass, 4 wool | FOOD_PRESERVED: keeps through winter |

Consumes FUEL, which is what ties the food chain to the mine or the charcoal burner.

#### `brewery`

Worker: **BREWER**  ·  Phase 3  ·  Variants: `plains`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | brewhouse | 11x11 | 24 oak log, 32 oak planks, 36 cobblestone, 6 glass, 4 wool, 4 iron ingot | ALE |
| 2 (upgrade) | brewery | 15x15 | 32 oak log, 48 oak planks, 52 cobblestone, 8 glass, 6 wool, 8 iron ingot | ALE enough to keep an inn supplied |

### Materials

#### `lumberjack`

Worker: **LUMBERJACK**  ·  Phase 1  ·  Variants: `plains`, `taiga`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | woodcutter's hut | 7x7 | 8 oak log, 16 oak planks, 16 cobblestone, 2 glass | LOGS |
| 2 (upgrade) | sawmill | 11x11 | 16 oak log, 24 oak planks, 24 cobblestone, 4 glass, 2 wool, 4 iron ingot | PLANKS |
| 3 (upgrade) | timber yard | 15x15 | 28 oak log, 44 oak planks, 48 cobblestone, 8 glass, 6 wool, 8 iron ingot | PLANKS more, beams for large footprints |

PLANKS at L2 is a real gate, not throughput: without it a village builds in logs and stone only.

#### `stoneworks`

Worker: **MASON**  ·  Phase 1  ·  Variants: `plains`, `taiga`, `snowy`, `desert`, `savanna`

*Replaces the old `quarry`.* A quarry was a second hole competing with the mine for
the same cobblestone, which is not a building, it is a duplicate. **The mason does not
dig.** The mine brings raw stone up; the mason turns it into what a village actually
builds with. That chain is load-bearing rather than flavour: `stone_bricks` are the
single most-used crafted block across every structure shipped so far, and nothing
gathers them.

The conversion needs no new mechanic. `ProcessItemGoal` is general (input stack,
output stack, sound) and already does this work elsewhere: the lumberjack turns
stripped logs into planks, the farmer turns pumpkins into seeds. The mason is
registered the same way, which is also why this needs no separate "sawmill"-style
building of its own.

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | stone yard | 7x7 | 12 oak log, 16 oak planks, 16 cobblestone, 4 glass, 2 wool | STONE, CUT_STONE |
| 2 (upgrade) | stoneworks | 11x11 | 16 oak log, 24 oak planks, 28 cobblestone, 4 glass, 4 wool, 4 iron ingot | CUT_STONE more, sandstone |
| 3 (upgrade) | masonry | 15x15 | 32 oak log, 48 oak planks, 52 cobblestone, 8 glass, 6 wool, 8 iron ingot | pillars and decorative stone |

#### `mine`  (founding building)

Worker: **MINER**  ·  Phase 1  ·  Variants: `plains`, `taiga`, `snowy`, `desert`, `savanna`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | mine shaft | 7x7 | 16 oak log, 20 oak planks, 24 cobblestone, 4 glass, 2 wool | ORES: coal and iron. FUEL |
| 2 (upgrade) | mine | 11x11 | 20 oak log, 32 oak planks, 36 cobblestone, 6 glass, 4 wool, 4 iron ingot | ORES: gold, redstone, lapis |
| 3 (upgrade) | deep mine | 15x15 | 44 oak log, 64 oak planks, 68 cobblestone, 12 glass, 8 wool, 12 iron ingot | ORES: diamond |

Founding building, placed free, and the only job a new camp has besides its builder. Two upgrades for one capability: DIAMOND at L3 is what makes blacksmith L3 mean anything, and the pairing is deliberate. The deepest mine and the greatest forge are a village's endgame together.

### Craft

#### `blacksmith`

Worker: **BLACKSMITH**  ·  Phase 2  ·  Variants: `plains`, `desert`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | forge | 11x11 | 28 oak log, 44 oak planks, 44 cobblestone, 8 glass, 6 wool, 8 iron ingot, 1 anvil | TOOLS_IRON, REPAIR, SMELTING |
| 2 (upgrade) | smithy | 11x11 | 44 oak log, 64 oak planks, 68 cobblestone, 12 glass, 8 wool, 16 iron ingot | ARMOR_IRON |
| 3 (upgrade) | foundry | 15x15 | 84 oak log, 128 oak planks, 136 cobblestone, 24 glass, 16 wool, 32 iron ingot, 4 diamond | TOOLS_DIAMOND, ARMOR_DIAMOND (requires mine_3) |

The worked example for capability-by-level. Every level is a genuine unlock rather than throughput, and L3 additionally requires mine_3 for its diamond supply.

#### `workshop`

Worker: **TANNER**  ·  Phase 3  ·  Variants: `plains`

*Merged category: absorbs the old `weaver`: turns hides and wool into goods.*

**The leather half of this is now redundant** and should probably be cut: `tannery`
yields leather directly, because cows drop it. What is left worth having here is the
wool half, which is the real bottleneck on beds.

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | tanning racks | 7x7 | 12 oak log, 20 oak planks, 20 cobblestone, 4 glass, 2 wool | worked LEATHER |
| 2 (upgrade) | tannery | 11x11 | 20 oak log, 28 oak planks, 32 cobblestone, 6 glass, 4 wool | ARMOR_LEATHER |

### Military

#### `watchtower`

Worker: **GUARD**  ·  Phase 1  ·  Variants: `plains`, `taiga`, `desert`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | watchpost | 5x5 | 8 oak log, 12 oak planks, 16 cobblestone, 2 glass | PROTECTION, GUARD station x1 |
| 2 (upgrade) | guard tower | 7x7 | 12 oak log, 20 oak planks, 20 cobblestone, 4 glass, 2 wool | GUARD station x2, longer sight range |
| 3 (upgrade) | keep tower | 11x11 | 28 oak log, 40 oak planks, 40 cobblestone, 8 glass, 4 wool, 8 iron ingot | GUARD station x3, alarm bell raises the village |

The only phase 1 military building. A camp with no watchpost is a camp the wolves clear out.

#### `wall`

Worker: **none**  ·  Phase 4  ·  Variants: `plains`, `taiga`, `desert`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | palisade | per segment | free | perimeter, per segment |
| 2 (upgrade) | stone wall | per segment | 2 oak log, 4 oak planks, 4 cobblestone | perimeter, per segment |
| 3 (upgrade) | fortified wall | per segment | 6 oak log, 8 oak planks, 8 cobblestone | perimeter with walkway, per segment |

Linear rather than a footprint, so it is priced per segment and is probably not a `Building` at all. Blocked on the site-selection pass.

#### `gatehouse`

Worker: **none**  ·  Phase 4  ·  Variants: `plains`, `desert`

| Level | Name | Footprint | Recipe (plains) | Grants |
| --- | --- | --- | --- | --- |
| 1 | gate | 7x7 | 8 oak log, 12 oak planks, 16 cobblestone, 2 glass | controlled entry |
| 2 (upgrade) | gatehouse | 11x11 | 20 oak log, 28 oak planks, 32 cobblestone, 6 glass, 4 wool, 4 iron ingot | controlled entry, GUARD station x1 |
| 3 (upgrade) | barbican | 15x15 | 40 oak log, 60 oak planks, 64 cobblestone, 12 glass, 8 wool, 12 iron ingot | controlled entry, GUARD station x2, portcullis |

Pairs with `wall`. Same phase, same blocker.

---

## Structure manifest

> **Superseded.** This manifest predates the 36→21 category cut and still enumerates cut
> categories (`kiln`/`pottery`/`glassworks`, `graveyard`, `apiary`, `charcoal_burner`,
> `weaver`, and all of Phase 4). It needs regenerating against the 21 survivors; until then
> the per-category tables above are authoritative, not this list.

Every `.nbt` this catalog needs, at `data/villagelife/structure/<id>.nbt`.

| Set | What it buys | Structures |
| --- | --- | --- |
| **Minimum playable** | One level-1 building per phase 1 category, plains variant only. A village that founds, feeds, houses, and defends itself. | **11** |
| Phase 1 | A village survives in any biome, at every level | 138 |
| Phase 2 | It thrives: processed food, iron, faith, trade | 32 |
| Phase 3 | It deepens: brewing, cloth, brick, glass, learning | 25 |
| Phase 4 | It fights: soldiers, walls, arrows, potions | 29 |
| | **Total** | **224** |

36 categories, 86 category-variant pairs, 224 structures. That total is the honest number and it
is large. Two things make it tractable:

- **The minimum playable set is 11 structures.** One level-1 plains building per phase 1 category.
  That alone gives a village that founds itself, feeds itself, houses its people, gathers wood and
  stone and ore, and posts a watch. Everything past it is variety and depth, not viability.
- **Vanilla cannot be copied, only referenced.** [Research on conversion](https://github.com/Quzzar/villagelife/issues/53)
  found the EULA forbids redistributing Mojang `.nbt` files in our jar, modified or not. Loading
  them at runtime by `ResourceLocation` is legal and is the only route. Coverage is also thinner
  than this doc first claimed: 27 to 30 of the phase 1 files, all level 1, and 22 of our categories
  have no vanilla equivalent at all, including `storehouse`, `lumberjack`, `mine`, `hunting_lodge`,
  and `watchtower`.
- **Third-party sets** need the author's permission, asked for before anything is copied.

### Minimum playable (11 files)

```
  village_center_plains_1
  house_plains_1
  well_plains_1
  storehouse_plains_1
  farm_plains_1
  hunting_lodge_plains_1
  fishery_plains_1
  lumberjack_plains_1
  stoneworks_plains_1
  mine_plains_1
  watchtower_plains_1
```

### Phase 1 (81 files)

```
village_center_plains_1       village_center_plains_2       village_center_plains_3
village_center_taiga_1        village_center_taiga_2        village_center_taiga_3
village_center_snowy_1        village_center_snowy_2        village_center_snowy_3
village_center_desert_1       village_center_desert_2       village_center_desert_3
village_center_savanna_1      village_center_savanna_2      village_center_savanna_3
house_plains_1                house_plains_2                house_plains_3
house_taiga_1                 house_taiga_2                 house_taiga_3
house_snowy_1                 house_snowy_2                 house_snowy_3
house_desert_1                house_desert_2                house_desert_3
house_savanna_1               house_savanna_2               house_savanna_3
house_igloo_1                 house_igloo_2                 house_igloo_3
house_stilt_1                 house_stilt_2                 house_stilt_3
well_plains_1                 well_plains_2                 well_desert_1
well_desert_2                 storehouse_plains_1           storehouse_plains_2
storehouse_plains_3           farm_plains_1                 farm_plains_2
farm_plains_3                 farm_taiga_1                  farm_taiga_2
farm_taiga_3                  farm_snowy_1                  farm_snowy_2
farm_snowy_3                  farm_desert_1                 farm_desert_2
farm_desert_3                 farm_savanna_1                farm_savanna_2
farm_savanna_3                hunting_lodge_plains_1        hunting_lodge_plains_2
hunting_lodge_taiga_1         hunting_lodge_taiga_2
hunting_lodge_snowy_1         hunting_lodge_snowy_2
hunting_lodge_desert_1        hunting_lodge_desert_2
hunting_lodge_savanna_1       hunting_lodge_savanna_2         fishery_plains_1
fishery_plains_2              fishery_marsh_1               fishery_marsh_2
lumberjack_plains_1           lumberjack_plains_2           lumberjack_plains_3
lumberjack_taiga_1            lumberjack_taiga_2            lumberjack_taiga_3
stoneworks_plains_1           stoneworks_plains_2           stoneworks_plains_3
stoneworks_taiga_1            stoneworks_taiga_2            stoneworks_taiga_3
stoneworks_snowy_1            stoneworks_snowy_2            stoneworks_snowy_3
stoneworks_desert_1           stoneworks_desert_2           stoneworks_desert_3
stoneworks_savanna_1          stoneworks_savanna_2          stoneworks_savanna_3
mine_plains_1                 mine_plains_2                 mine_plains_3
mine_taiga_1                  mine_taiga_2                  mine_taiga_3
mine_snowy_1                  mine_snowy_2                  mine_snowy_3
mine_desert_1                 mine_desert_2                 mine_desert_3
mine_savanna_1                mine_savanna_2                mine_savanna_3
watchtower_plains_1           watchtower_plains_2           watchtower_plains_3
watchtower_taiga_1            watchtower_taiga_2            watchtower_taiga_3
watchtower_desert_1           watchtower_desert_2           watchtower_desert_3
```

### Phase 2 (38 files)

```
market_plains_1               market_plains_2               market_plains_3
market_desert_1               market_desert_2               market_desert_3
church_plains_1               church_plains_2               church_plains_3
church_taiga_1                church_taiga_2                church_taiga_3
church_desert_1               church_desert_2               church_desert_3
tannery_plains_1              tannery_plains_2              tannery_plains_3
tannery_taiga_1               tannery_taiga_2               tannery_taiga_3
tannery_snowy_1               tannery_snowy_2               tannery_snowy_3
tannery_desert_1              tannery_desert_2              tannery_desert_3
tannery_savanna_1             tannery_savanna_2             tannery_savanna_3
mushroom_cellar_plains_1      mushroom_cellar_plains_2      mill_windmill_1
mill_windmill_2               mill_watermill_1              mill_watermill_2
bakery_plains_1               bakery_plains_2               bakery_plains_3
butchery_plains_1             butchery_plains_2             blacksmith_plains_1
blacksmith_plains_2           blacksmith_plains_3           blacksmith_desert_1
blacksmith_desert_2           blacksmith_desert_3
```

### Phase 3 (25 files)

```
inn_plains_1                  inn_plains_2                  inn_plains_3
inn_taiga_1                   inn_taiga_2                   inn_taiga_3
library_plains_1              library_plains_2              graveyard_plains_1
graveyard_desert_1            apiary_plains_1               brewery_plains_1
brewery_plains_2              charcoal_burner_plains_1      charcoal_burner_plains_2
pottery_plains_1              pottery_plains_2              pottery_desert_1
pottery_desert_2              glassworks_desert_1           glassworks_desert_2
tannery_plains_1              tannery_plains_2              weaver_plains_1
weaver_plains_2
```

### Phase 4 (29 files)

```
fletcher_plains_1             fletcher_plains_2             armoury_plains_1
armoury_plains_2              alchemist_plains_1            alchemist_plains_2
barracks_plains_1             barracks_plains_2             barracks_plains_3
barracks_desert_1             barracks_desert_2             barracks_desert_3
training_yard_plains_1        training_yard_plains_2        wall_plains_1
wall_plains_2                 wall_plains_3                 wall_taiga_1
wall_taiga_2                  wall_taiga_3                  wall_desert_1
wall_desert_2                 wall_desert_3                 gatehouse_plains_1
gatehouse_plains_2            gatehouse_plains_3            gatehouse_desert_1
gatehouse_desert_2            gatehouse_desert_3
```

## Open questions

- **Does the level-2-only rule hold everywhere?** A village that loses its blacksmith to a raid
  must rebuild from `blacksmith_*_1`, which is a real setback. Probably correct, worth confirming.
- **Are the recipes at the right scale?** They were generated to sit in the same range as the
  existing datapack costs, which were themselves untuned. Nothing here has been played.
- **Do wall and gatehouse belong in this file at all**, or do they wait for site-selection and get
  their own treatment as linear structures.

