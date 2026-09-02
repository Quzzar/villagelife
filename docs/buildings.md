# Buildings: the catalog

**Superseded in part: the catalogue was cut from 36 categories to 17** on
[#57](https://github.com/Quzzar/villagelife/issues/57) and after (the `kiln` was cut; the
`tannery` became the livestock `butchery`, absorbing `pasture` and `workshop`; `inn` became
the `tavern`; and `brewery` and `library` were cut); see the "The cut" section of
[building-spec.md](building-spec.md) for the survivors and the casualties with reasons.
The reasoning below still explains WHY the categories exist and how they group, which is
why it is kept, but where it lists a category the spec has cut, the spec wins.

**Proposed, not yet decided, and largely unbuilt.** Nine building definitions exist against
the catalogue below; capabilities, need-routing, production chains and the ~19 new
occupations are all absent from code. [building-spec.md](building-spec.md) supersedes this
document wherever they differ. This is the full pipeline of what a village can build, laid
out so the shape is visible before any of it ships. It extends the two building axes named
in [village-tiers.md](village-tiers.md) (`category` and `upgrades_from`) into a concrete
list. Nothing here gates on village tier: a camp may build a cathedral if it somehow
affords one.

Read [village-tiers.md](village-tiers.md) first. It owns the rules this catalog fills in.

## The three axes

The word "tier" is already taken by the village ladder (camp to city), so building
progression is called **level** here, never tier.

| Axis | Field | Question it answers | Example |
| --- | --- | --- | --- |
| **Category** | `category` | What does this building *do*? | `house` |
| **Variant** | (its own definition) | Which regional design is this? | `house_desert`, `house_tundra`, `igloo` |
| **Level** | `upgrades_from` | How *developed* is it? | `house_desert_2` upgrades from `house_desert_1` |

### Variants are separate structures

A desert home, a tundra home, and an igloo are **different buildings**, not the same
building rendered in different blocks. They differ in shape, footprint, roof pitch, window
count, and therefore in cost. Each is its own `.nbt` and its own definition file sharing a
`category`.

That is the whole point: the desert house exists *because* it is the right building for a
desert, not because a block-swap table turned oak into sandstone.

### Variants are sparse, not a matrix

Category x variant x level is not a grid to be filled. Most cells never need to exist.

| Category | Variants worth building | Why |
| --- | --- | --- |
| `house` | 5 or 6 | The most numerous and most looked-at building in any village. Regional identity lives here. |
| `village_center`, `well`, `church` | 2 or 3 | Common, central, visible. Worth a regional face. |
| `farm`, `fishery`, `hunting_lodge` | 1 or 2 | Already regional by *category*: a fishery is the coastal answer, so it does not also need a desert variant. |
| `quarry`, `mine`, `charcoal_burner`, `pottery` | 1 | Industry reads the same everywhere. A mine entrance is a mine entrance. |

The food categories carry most of the biome adaptation on their own (below), which is what
keeps the house variants from having to do all the work.

### Vanilla is the starting content

Minecraft already ships village buildings in five biome families: plains, desert, savanna,
snowy, and taiga. Those are directly convertible into variants, and their existence is the
strongest argument that variants are separate structures: Mojang built them that way for
the same reason.

Third-party building sets are a real
option for filling out the catalog faster, but **only with permission from the author
first.** Ask before any asset is copied.

## Levels

A level is the same category, developed further. Two things change:

1. **Scale**: more work stations, more beds, more container slots, more throughput.
2. **Capability**: some levels unlock something the lower level simply cannot do.

Capability unlocks are real and wanted. They are not the primary purpose of levelling, but
they are the reason a village ever bothers past the point where it has enough throughput.

### Wide versus tall is math, not a rule

[village-tiers.md](village-tiers.md) observes that building wide is generally cheaper than
building tall. **That is a property to tune into the cost tables, not a rule to implement
or to tell the brain.**

Nothing in the planner and nothing in the LLM prompt says "prefer sprawl." The brain is
handed the legal, affordable options and picks. A village with land sees a cheap second
small building in its option list and takes it. A village hemmed in by a ravine does not
see that option at all, because site-finding found nowhere to put it, and takes the upgrade
instead. The sprawling village and the one enormous tower are both emergent from cost plus
space.

The only work this creates is a tuning invariant to check when writing cost tables:

> Upgrading a level-1 building to level 2 should generally cost more than building a second
> level-1 building of the same category.

"Generally" is doing real work there. Where a level unlocks a capability rather than just
throughput, the upgrade is buying something a second small building cannot provide, and the
comparison stops applying.

## Capabilities

A **capability** is something the village can do, granted by owning a building at a given
level. The village's capability set is the union across all its finished buildings. This is
where the RTS tech feeling comes from: capability is unlocked by construction, never by
population or village tier.

Some capabilities come from a new level of an existing category. Some come from an entirely
new category. Both are legitimate, and which one a given capability uses is a per-case
design call, not a rule.

Worked example, tools and equipment:

| Capability | Granted by | Kind of unlock |
| --- | --- | --- |
| Wood and stone tools | `village_center` | baseline, no building needed |
| Iron tools, `REPAIR` | `blacksmith_1` | new category |
| Iron armor | `blacksmith_2` | new level |
| Diamond tools and armor | `blacksmith_3` | new level |
| Military-grade equipment, shields | `armoury_1` | new category |
| Enchanted equipment | `church_2` plus `library_1` | two categories combined |

Contrast with a category where every level is pure scale: `storehouse_2` holds more than
`storehouse_1` and unlocks nothing. That is fine and normal. Most levels are like this.

## Needs, not buildings

The biome problem ("can a village thrive here?") is solved by routing on **need**, not on
category. The village brain wants `FOOD`. Several categories satisfy `FOOD`, and which ones
are legal depends on what is actually around the village:

```
need: FOOD  <-  farm | pasture | hunting_lodge | fishery | mushroom_cellar
need: WOOD  <-  lumberjack | (trade via market)
need: STONE <-  quarry | mine
need: FUEL  <-  mine (coal) | charcoal_burner (logs)
```

This is what the existing `Benefit` enum already almost is — though note that enum is
currently **inert**: it parses from JSON, ships in 8 of 9 building files, and has zero
readers in code. It should be split in two, since
it is currently doing both jobs:

| Kind | Meaning | Examples |
| --- | --- | --- |
| **Output** | A resource this building puts into village containers | `GRAIN`, `MEAT`, `LOGS`, `PLANKS`, `STONE`, `ORES`, `FUEL`, `LEATHER`, `CLOTH`, `BRICK`, `GLASS`, `BREAD`, `ALE`, `ARROWS`, `ARMOR` |
| **Service** | A capability the building gives the village, with nothing in a chest | `WATER`, `STORAGE`, `FOOD_STORAGE`, `PROTECTION`, `SMELTING`, `REPAIR`, `HEALING`, `ENCHANTING`, `LEARNING`, `TRADE`, `MORALE` |

A desert village and a taiga village run the same brain. They just resolve `FOOD` to
different categories, and reach for different variants of the categories they share.

---

## The catalog

The categories in six groups. ([building-spec.md](building-spec.md) is the authority and
counts **36**, having absorbed `granary` into `storehouse`; where the two disagree, the spec
wins.) The **Phase** column is the proposed implementation order, not
a gate: phase 1 is what a village needs to not die, phase 4 is what it needs to win a war.

### 1. Core and civic

| Category | Worker | Gives | Levels | Phase |
| --- | --- | --- | --- | --- |
| `village_center` | LEADER (L2+) | beds, starter stations, campfire, `STORAGE` | 3 | 1 |
| `house` | none | beds | 3 | 1 |
| `well` | none | `WATER` | 2 | 1 |
| `storehouse` | none | `STORAGE` | 3 | 1 |
| `granary` | none | `FOOD_STORAGE` | 3 | 1 |
| `market` | MERCHANT | `TRADE` | 3 | 2 |
| `inn` | INNKEEPER | `MORALE` (consumes `ALE`) | 3 | 3 |

`village_center` is the camp: 4 beds, miner/builder/guard stations, the campfire gathering
point. Its levels raise base beds and add the LEADER station, which is where the village
brain's voice lives.

`granary` is separate from `storehouse` on purpose. Attractiveness reads food per capita out
of village containers, so food wants its own building the village can visibly fail to fill.

### 2. Faith, learning, memory

| Category | Worker | Gives | Levels | Phase |
| --- | --- | --- | --- | --- |
| `church` | CLERIC | `HEALING`, `ENCHANTING`, `MORALE` | 3 (shrine, church, cathedral) | 2 |
| `library` | LIBRARIAN | `LEARNING`, books | 2 | 3 |
| `graveyard` | none | `MORALE` recovery after deaths | 1 | 3 |

`graveyard` is small and cheap and exists to interact with the death events already feeding
attractiveness: a village that buries its dead recovers faster than one that does not.

### 3. Food

Raw production. A village needs **at least one** of these five, and which ones are available
is the whole biome story.

| Category | Worker | Output | Wants | Levels | Phase |
| --- | --- | --- | --- | --- | --- |
| `farm` | FARMER | `GRAIN`, vegetables | tillable land, water | 3 | 1 |
| `butchery` pen | HERDER | `CLOTH` (wool), herd breeding | its own fenced stock | with `butchery` | 2 |
| `hunting_lodge` | HUNTER | `MEAT`, `LEATHER` | trees, wildlife | 2 | 1 |
| `fishery` | FISHER | `MEAT` (fish) | adjacent water | 2 | 1 |
| `mushroom_cellar` | FARMER | `FOOD` (low yield) | darkness, any biome | 2 | 2 |
| `apiary` | none | honey, crop yield bonus | flowers | 1 | 3 |

`mushroom_cellar` is the floor: low yield, buildable anywhere, the reason a badlands or
mushroom-island village is poor rather than dead.

Processing. These convert raw output into food that is worth more per item, which is what
actually moves attractiveness.

| Category | Worker | Converts | Levels | Phase |
| --- | --- | --- | --- | --- |
| `mill` | MILLER | `GRAIN` to flour | 2 | 2 |
| `bakery` | BAKER | flour to `BREAD` | 3 | 2 |
| `butchery` | BUTCHER | raw `MEAT` to cooked, preserved | 2 | 2 |
| `brewery` | BREWER | `GRAIN`, honey to `ALE` | 2 | 3 |

`mill` is the clearest case of variants doing real work: a **windmill** (open, windy biomes)
and a **watermill** (adjacent flowing water) are different structures satisfying the same
need, chosen by what the site offers.

### 4. Materials

| Category | Worker | Output | Wants | Levels | Phase |
| --- | --- | --- | --- | --- | --- |
| `lumberjack` | LUMBERJACK | `LOGS`, `PLANKS` (L2) | trees | 3 | 1 |
| `quarry` | MASON | `STONE` | exposed stone | 3 | 1 |
| `mine` | MINER | `ORES`, `FUEL` (coal) | stone, depth | 3 | 1 |
| `charcoal_burner` | COLLIER | `FUEL` from `LOGS` | trees | 2 | 3 |
| `pottery` | POTTER | `BRICK`, pots | clay, sand | 2 | 3 |
| `glassworks` | GLASSBLOWER | `GLASS` | sand, `FUEL` | 2 | 3 |

`charcoal_burner` is the coal-poor answer: a forest village with no ore body still gets
`FUEL`, at the cost of the logs it would rather build with. `pottery` and `glassworks` turn
the two "worthless" biome resources (clay, sand) into building material, which is how a
desert or river village stops being materially poor.

### 5. Craft

| Category | Worker | Output | Consumes | Levels | Phase |
| --- | --- | --- | --- | --- | --- |
| `blacksmith` | BLACKSMITH | tools, `SMELTING`, `REPAIR` | `ORES`, `FUEL` | 3 | 2 |
| `tannery` | TANNER | worked `LEATHER` | `LEATHER` | 2 | 3 |
| `weaver` | WEAVER | `CLOTH`, beds | wool | 2 | 3 |
| `fletcher` | FLETCHER | `ARROWS`, bows | `LOGS`, feathers | 2 | 4 |
| `armoury` | ARMOURER | `ARMOR` | `ORES`, `LEATHER` | 2 | 4 |
| `alchemist` | ALCHEMIST | potions | nether and rare items | 2 | 4 |

`weaver` matters more than it looks: wool is the bottleneck on beds, and beds are the
housing cap. A village that can weave can grow past what it can shear.

### 6. Military

| Category | Worker | Gives | Levels | Phase |
| --- | --- | --- | --- | --- |
| `watchtower` | GUARD | `PROTECTION` | 3 (post, tower, keep tower) | 1 |
| `barracks` | SOLDIER | multiple soldier stations | 3 | 4 |
| `training_yard` | DRILLMASTER | soldier quality | 2 | 4 |
| `wall` / `gatehouse` | none | perimeter | 3 (palisade, stone, fortified) | 4 |

`wall` is not a job building and probably not a `Building` at all. It is a *linear* structure
placed along a computed perimeter, so it needs the site-finding pass
([village-tiers.md](village-tiers.md) flags that as its own upcoming design) before it means
anything. Listed here for completeness, deliberately last.

---

## Production chains

The chains are what make the catalog a system rather than a list. Each arrow is a building
consuming one village output and producing another.

```
trees ---> lumberjack ---> LOGS ---> lumberjack L2 ---> PLANKS ---> (construction)
                             \
                              '---> charcoal_burner ---> FUEL
stone ---> quarry ---------> STONE --------------------> (construction)
        \
         '-> mine ---------> ORES ---> blacksmith ---> tools, REPAIR
                    \                       \
                     '-----> FUEL -----------'------> armoury ---> ARMOR

land ----> farm -----------> GRAIN --> mill --> flour --> bakery --> BREAD --> granary
                                 \
                                  '-> brewery --> ALE --> inn --> MORALE
animals -> pasture --------> MEAT --> butchery --> preserved food --> granary
              \
               '----------> wool --> weaver --> CLOTH --> beds --> housing cap
               '----------> LEATHER --> tannery ---> armoury
water ---> fishery --------> MEAT --> butchery
forest --> hunting_lodge --> MEAT, LEATHER

clay ----> pottery --------> BRICK --> (construction)
sand ----> glassworks -----> GLASS --> (construction, church L3)
```

Two loops close on themselves and are the interesting ones: **FUEL** (mine or
charcoal_burner, consumed by blacksmith, glassworks, butchery) and **beds** (pasture to
weaver to housing cap to more people to more pasture).

## Regional variants and biomes

The starting variant families, applied to the categories worth varying:

| Family | Biomes | Reads as |
| --- | --- | --- |
| `plains` | plains, forest, river, meadow | the default |
| `taiga` | taiga, old growth, grove | steep roofs, spruce, stone footings |
| `snowy` | snowy plains, ice spikes, snowy taiga | packed roofs, small windows, `igloo` as a distinct low-cost house variant |
| `desert` | desert, badlands | flat roofs, sandstone, shaded courtyards |
| `savanna` | savanna, jungle edge | wide eaves, acacia, raised floors |

**Built, 2026-09-01: a village picks its family once, at founding, and keeps it.** The family
is read from the biome the camp stands in through the conventional biome tags rather than
vanilla ids (`village/buildings/VillageStyle.java`): `c:is_desert`, `c:is_badlands` and
`c:is_sandy` found a desert camp, `c:is_snowy` and `c:is_icy` a snowy one, `c:is_savanna` and
`c:is_jungle` a savanna one, `c:is_taiga`, coniferous woods and `c:is_mountain` a taiga one,
and everything else plains. Modded biomes carry those tags, so a modded desert sorts itself
without this mod knowing its name. The family is stored on the village, and every building it
raises afterwards is that family's variant, falling back to plains where a category has no
other; an upgrade follows the variant already standing. The planner therefore offers the brain
one option per category, never five look-alike lodges to choose among. Recipes do not vary by
family at all ([building-spec.md](building-spec.md)): the variant decides the look, nothing
else. `/villagelife create-village <pos> [family]` founds in a named family instead of the
biome's.

How a village survives where it is:

| Biome | Food | Wood | Stone | Variant family | Verdict |
| --- | --- | --- | --- | --- | --- |
| Plains | farm, pasture | scarce | quarry | plains | thrives |
| Forest | hunting_lodge, farm | lumberjack | quarry | plains | thrives |
| Taiga / snowy | hunting_lodge | lumberjack | quarry | taiga, snowy | thrives, slow |
| Desert | farm (irrigated), mushroom_cellar | trade only | quarry, glassworks | desert | hard, trades for wood |
| Savanna | pasture, farm | lumberjack (sparse) | quarry | savanna | thrives |
| Jungle | hunting_lodge, farm | lumberjack | quarry | savanna | thrives, cramped |
| Swamp | fishery, mushroom_cellar | lumberjack | scarce | plains | poor, wet |
| Ocean / beach | fishery | trade only | quarry, glassworks | plains | fish-rich, wood-poor |
| Mountains | pasture, hunting_lodge | scarce | quarry, mine | taiga | ore-rich, food-poor |
| Badlands | mushroom_cellar | none | quarry, pottery | desert | survives, barely |
| Mushroom fields | mushroom_cellar | none | scarce | plains | isolated curiosity |

The `market` category is the pressure valve: a wood-poor village with fish or ore to spare
should be able to trade for logs rather than starve for planks. Whether that trade is with
the player, with a caravan, or abstract is an open question below.

## What this implies

| | Count |
| --- | --- |
| Categories | 37 |
| Variant families | 5 |
| Structures to build (`.nbt`), sparse variants | ~130 |
| New `Occupation` values | ~19 |
| Phase 1 categories (a village survives) | 12 |

Phase 1 alone is 12 categories and roughly 35 structures once its house variants are counted,
and it is the point at which a village in any biome can feed, house, arm, and supply itself.
Everything after that is depth. Converting the vanilla village structures covers a real
fraction of phase 1 for free.

## Open questions

- **Building id scheme.** This doc implies `<category>_<variant>_<level>`
  (`house_desert_2`), with a few named exceptions where the variant has its own name
  (`igloo`, `windmill`, `watermill`). `village-tiers.md` implies
  `<category>_<material>_<size>` (`house_wood_s`). Close, but they should be reconciled to
  one before content work starts.
- **Which capabilities are levels and which are new categories.** Decided per case. The
  tools table above is the worked example; the rest of the catalog has not had that pass.
- **What does `market` actually trade with?** The player, a spawned caravan, or an abstract
  exchange rate against nothing.
- **Do processing buildings consume from containers on a tick, or on a work-station action?**
  The whole chain diagram depends on it, and it is the difference between a simulation and a
  set of decorative buildings.
- **Does `wall` belong in this system at all**, or is it a separate perimeter feature that
  waits for the site-finding pass.
- **Third-party structure permission.** If Yung's or any other set is used, permission has to
  be secured before the assets land in the repo.
