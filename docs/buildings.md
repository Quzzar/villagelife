# Buildings: the catalog

**Superseded in part: the catalogue was cut from 36 categories to 17** on
[#57](https://github.com/Quzzar/kithkyn/issues/57) and after (the `kiln` was cut; the
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
| **Village biome** | `variant` | Which environmental architecture catalog is this building authored for? | `house_birch_forest`, `house_snowy_taiga`, `igloo` |
| **Level** | `upgrades_from` | How *developed* is it? | `house_desert_2` upgrades from `house_desert_1` |

### Variants are separate structures

A desert home, an alpine home, and an igloo are **different buildings**, not the same
building rendered in different blocks. They differ in shape, footprint, roof pitch, window
count, and therefore in cost. Each is its own `.nbt` and its own definition file sharing a
`category`.

That is the whole point: the desert house exists *because* it is the right building for a
desert, not because a block-swap table turned oak into sandstone.

### Variants are sparse, not a matrix

Category x variant x level is not a grid to be filled. Most cells never need to exist.

| Category | Variants worth building | Why |
| --- | --- | --- |
| `house` | Every supported village biome, often at several scales | The most numerous and most looked-at building in any village. Village-biome architecture reads most clearly here. |
| `village_center`, `well`, `church`, `market`, `tavern` | Each village biome where environment changes the layout or silhouette | These are common, central, and visible enough to establish the settlement language. |
| `farm`, `fishery`, `hunting_lodge` | Only village biomes where the environment meaningfully changes the work site | A coast fishery and alpine enclosed farm earn unique layouts; superficial palette swaps do not. |
| `quarry`, `mine`, `charcoal_burner`, `pottery` | Usually one shared or visually neutral variant | Industry does not need near-identical structures merely because many village biomes exist. |

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
| `butchery` pen | HERDER | `CLOTH` (wool), herd breeding up to twelve a kind | its own fenced stock | with `butchery` | 2 |
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
| `butchery` | BUTCHER | the pen kept at six a kind; raw `MEAT` to cooked, preserved | 2 | 2 |
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

## Village-biome variants

A **village biome** is a named architecture catalog, not necessarily one literal biome id and not
a real-world culture. Every village resolves its founding site to one village biome and retains
that catalog for life. Multiple vanilla or modded biomes may resolve to the same catalog, but two
complete reference families do not share one catalog merely because their climates are similar.
The catalog name is the building's variant token, so a Birch Forest village resolves
`house_birch_forest_1`.

Architecture and environment are related but not identical. **Biome traits** such as Cold, Wet,
Arid, Coastal, and Wooded drive survival, work, and placement rules. A Swedish-inspired Shield
catalog and a Viking-inspired Snowy Taiga catalog can therefore share Cold and Wooded behavior
without becoming the same architecture.

### Towns and Towers establishes the minimum roster

Every Overworld Towns and Towers settlement family reserves one distinct Kithkyn village
biome. A small family still reserves its identity; it is completed with original Kithkyn
structures or a compatible secondary reference rather than merged into another family. Source
culture names remain gallery labels only. Runtime ids use environmental names.

| Village biome | Towns and Towers reference family | Coverage |
| --- | --- | --- |
| `alpha_islands` | Classic | Complete family |
| `subtropical_grassland` | Iberian | Complete family |
| `hot_shrubland` | Mediterranean | Complete family |
| `floodplain` | Nilotic | Small family; needs original role coverage |
| `autumn_forest` | Rustic | Complete family |
| `shield` | Swedish | Complete family |
| `highlands` | Tudor | Complete family |
| `desert_oasis` | Oriental wandering-trader camp | Special camp family |
| `badlands` | Pueblo | Complete family |
| `beach` | Lighthouse | Small landmark family |
| `birch_forest` | Romanian | Complete family |
| `flower_forest` | Japanese | Complete family; low priority except farm and stable |
| `forest` | Forest ruins | Complete family |
| `grove` | Villager outpost | Small landmark family |
| `jungle` | Tribal | Complete family |
| `meadow` | Swiss | Complete family |
| `mushroom_fields` | Fantasy | Complete family |
| `deep_ocean` | Village ships | Special water settlement family |
| `old_growth_taiga` | Polish | Complete family |
| `savanna_plateau` | Ramshackle | Small family; needs original role coverage |
| `snowy_slopes` | Inn | Small landmark family |
| `snowy_taiga` | Viking | Complete family |
| `sparse_jungle` | Polynesian | Complete family |
| `sunflower_plains` | Farm | Small agricultural family |
| `swamp` | Boat village | Complete family |
| `wooded_badlands` | Tipi | Small family; needs original role coverage |

That is a floor of **26 Overworld village biomes** from Towns and Towers alone. Its Piglin family
reserves a twenty-seventh, `nether_wastes`, when non-Overworld villages enter scope.

Large, coherent non-Towns-and-Towers families earn additional catalogs rather than being folded
into the closest row above. The reviewed set already justifies `plains` for Kithkyn's current
and CTOV plains work, `old_growth_birch_forest` for Dungeons and Taverns birch,
`tropical_coast` for CTOV beach, `alpine_highlands` for CTOV alpine, `mangrove_swamp` for CTOV
swamp, and `mesa` for CTOV mesa. Unreviewed CTOV desert, oasis, taiga, mountain, jungle,
jungle-tree, snowy-igloo, savanna, mushroom, and underground families may add more after their
visual boundaries are reviewed.

Fortified, seasonal, and settlement-stage collections do not become village biomes. For example,
CTOV fortified plains is a defense or development state of `plains`, while Christmas and
Halloween are possible seasonal treatments. This keeps biome, progression, and event state from
becoming one overloaded axis.

Unknown world biomes fall back to `plains`. The eventual resolver should first honor an explicit
datapack mapping, then use conventional biome tags and climate properties, and finally use the
fallback. This lets modpacks classify unusual biomes precisely without hard-coding every registry
id in Kithkyn.

**Current implementation, built 2026-09-01:** `VillageStyle` supports only `plains`, `taiga`,
`snowy`, `desert`, and `savanna`. It remains a useful bootstrap, not the target catalog. The
refactor keeps the invariant that a village chooses once at founding and retains the result.
Recipes remain identical across village biomes, and an upgrade follows the village biome of the
building already standing.

### Village identity is separate from village biome

Every village will also establish a persistent **village identity**: an ordered primary and
secondary Minecraft dye color plus a layered banner design using those colors. Village biome
answers how the village builds; village identity answers which particular community built it.
Two villages may therefore share the same architecture while using different banners and color
accents.

Buildings that support identity need intentionally authored banner sockets and semantic color
anchors. The colors must not be implemented as a blind replacement of every block with a
matching material. The exact point when a village establishes its identity, at founding or
during early growth, remains to be decided before implementation.

How a village survives where it is:

| Environment | Food | Wood | Stone | Representative traits | Verdict |
| --- | --- | --- | --- | --- | --- |
| Plains | farm, pasture | scarce | quarry | Temperate, Open | thrives |
| Forest | hunting_lodge, farm | lumberjack | quarry | Wooded | thrives |
| Taiga | hunting_lodge | lumberjack | quarry | Cold, Wooded | thrives, slow |
| Tundra | hunting_lodge, enclosed farm | scarce | quarry | Cold, Exposed | survives, slow |
| Desert | farm (irrigated), mushroom_cellar | trade only | quarry, glassworks | Arid, Hot | hard, trades for wood |
| Savanna | pasture, farm | lumberjack (sparse) | quarry | Warm, Open | thrives |
| Jungle | hunting_lodge, farm | lumberjack | quarry | Hot, Wet, Wooded | thrives, cramped |
| Swamp | fishery, mushroom_cellar | lumberjack | scarce | Wet, Wooded | poor, wet |
| Ocean / beach | fishery | trade only | quarry, glassworks | Coastal | fish-rich, wood-poor |
| Mountains | pasture, hunting_lodge, enclosed farm | scarce | quarry, mine | Cold, Steep | ore-rich, food-poor |
| Badlands | mushroom_cellar | none | quarry, pottery | Arid, Rocky | survives, barely |
| Mushroom fields | mushroom_cellar | none | scarce | Fungal, Isolated | isolated curiosity |

The `market` category is the pressure valve: a wood-poor village with fish or ore to spare
should be able to trade for logs rather than starve for planks. Whether that trade is with
the player, with a caravan, or abstract is an open question below.

## What this implies

| | Count |
| --- | --- |
| Categories | 37 |
| Implemented village biomes | 5 |
| Towns and Towers Overworld village-biome floor | 26 |
| Additional village biomes already justified by reviewed families | 6 |
| Existing structure-plan estimate, based on five village biomes | ~130 |
| Revised sparse structure total | Recalculate during the category pass |
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
