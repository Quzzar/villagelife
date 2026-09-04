# Structure reference review

This file records decisions made while walking the local ValeCraft reference gallery. It is a
design trail for Kithkyn's own structure work: what earned a prototype, what was deferred,
and why. The local gallery and its exact reference templates remain under the gitignored `run/`
tree.

## Review pass: 2026-09-04

### Defensive buildings

| Reference | Decision | What carries forward |
| --- | --- | --- |
| Towns and Towers old-growth pillager fort | Do not prototype in the current pass | The encampment is interesting, but it does not fit an immediate Kithkyn need. |
| Towns and Towers taiga tower | Do not prototype in the current pass | The silhouette is impressive, but the scale is too large for the ordinary watchtower ladder. |
| Dungeons and Taverns forest firewatch tower | Prototype as the level-1 watchtower reference | The compact footprint and clear tower shape are a strong starting point. Add village banners and audit villager navigation between every floor. |
| Towns and Towers flower-forest tower | Prototype as the level-2 watchtower reference | Keep the stronger tier-2 silhouette and banner placements. Rework the dark wood palette toward oak and stone, then audit stairs, ladders, doors, and floor access for villagers. |

The two retained towers should be evaluated as a progression pair. The level-2 result should feel
like a developed defensive building, not an unrelated monument.

### Mediterranean village

The white Mediterranean village has a strong church and several simple, convincing houses. Keep
the family in the reference pool, but defer prototypes until the first defensive and tavern pass
is complete. Its tight interiors and alleys need a deliberate villager-navigation review before
any individual building is selected.

### Taverns

| Reference | Decision | What carries forward |
| --- | --- | --- |
| Dungeons and Taverns desert tavern | Prototype against `tavern_desert_1` | Use it as the immediate desert tavern reference, then reshape it to Kithkyn's scale, palette, stations, and pathing requirements. |
| Oak, spruce, and cherry taverns | Defer | Compare each with the matching shipped Kithkyn tavern before replacing anything. The current oak tavern may already be the better base. |

### Ruins

The Terralith rubble structures are useful environmental references but do not currently map to a
selected Kithkyn building. Keep them in the gallery rather than starting a prototype.

### Second-wing selections

| Reference | Decision | What carries forward |
| --- | --- | --- |
| Towns and Towers Japanese family | Skip as a general Kithkyn direction | Keep the farm and stable as individual candidates. Both may be stronger than the corresponding current structure, but compare them before replacing anything. |
| Towns and Towers Swiss temple | Prototype as the level-1 church reference | Treat it as the first step in a church progression. The large cathedral reference becomes level 2. A distinct level 3 can come later. |
| Towns and Towers Pueblo family | Reserve the `badlands` village biome | Compare its material language and massing with CTOV Mesa and the Towns and Towers Wooded Badlands family without merging their catalogs. |
| Dungeons and Taverns birch fishery | Replace the current fishery direction with a Kithkyn adaptation | Preserve the appealing footprint and silhouette, then adapt its palette, work station, storage, water access, and villager pathing. |
| Dungeons and Taverns birch farm, armorer, and library | Strong candidates | Keep all three visible for the category-by-category variant review. No replacement is decided yet. |

### Dense-wing selections

| Reference | Decision | What carries forward |
| --- | --- | --- |
| Dungeons and Taverns birch animal pen | Strong candidate | Use its readable livestock layout as the leading animal-farm reference. |
| Dungeons and Taverns birch cleric | Do not prototype in the current pass | The building does not add enough beyond the stronger church references. |
| Towns and Towers classic family | Reserve `alpha_islands`, at low priority | Several buildings read close to generic Minecraft structures, but the family remains a distinct catalog rather than being pooled into Plains. |
| Towns and Towers Romanian center | Prototype as a level-3 cathedral reference | The gallery label is `Romanian Center`, from `birch_forest_meeting_point_1`, although its large church-like silhouette makes it useful for the cathedral progression. |
| Towns and Towers Romanian large house | Strong high-capacity house candidate | Preserve the convincing multi-room massing and evaluate it as a four-bedroom house. |
| Towns and Towers Romanian farm and fishery | Strong candidates | Keep both for the food-building comparison pass. |
| Towns and Towers Romanian armor-and-tools and weaponsmith buildings | Strong blacksmith candidates | Compare both as blacksmith levels or regional variants rather than separate professions by default. |
| Towns and Towers Romanian meat-and-leather building | Strong butchery candidate | Compare it with the combined livestock and processing role already assigned to Kithkyn's butchery. |
| Towns and Towers Polish, Viking, swamp, Polynesian, jungle, mushroom, Iberian, Nilotic, and sunflower families | Give each its own village-biome slot | Review their architectural language across whole families rather than selecting isolated material swaps. |

The review now treats Towns and Towers as a major reference source rather than a source of a few
individual buildings. The useful unit is the full reference family: recurring roof language,
footprints, civic hierarchy, industry, housing, and public space. Any Kithkyn adaptation
still needs its own functional layout, navigation, identity anchors, and coherent group-wide
edits.

The next visual pass deliberately moves to non-Towns-and-Towers sources so the project does not
mistake one mod's aesthetic for the entire design space.

The church decision is a building-level progression, not a settlement-tier gate. A camp may still
build a church if resources, space, and the village's own priorities support it.

The next selection pass should begin only after the broader gallery walk. Inventory the current
Kithkyn categories and variants, then review one village biome at a time. Compare nearby
catalogs, such as Badlands, Wooded Badlands, and Mesa, to ensure each has a legible boundary
without pooling them. This prevents a good isolated building from leaving the full village
visually incoherent.

## Village identity must come first

Before the retained structures become production buildings, each village needs a persistent visual
identity:

- a primary color;
- a secondary color;
- a generated banner pattern representing the village;
- authored banner positions in buildings that are important enough to display it;
- semantic palette anchors that can use the two village colors without blindly replacing every
  block of the same material.

The village should establish this identity early in its life. The exact moment is still open:
either at the founding camp or during the transition to hamlet. The choice should belong to the
village's decision process and then be stored with the village so every later structure uses the
same identity.

The primary and secondary colors are ordered Minecraft dye colors, suitable for wool, banners,
and deliberately chosen accent blocks. The banner is a layered Minecraft banner design using
both colors. A dragon-like emblem is one possible outcome, not a required universal motif.

Village identity is independent of village biome. The village biome supplies the architecture; the
identity makes two villages using the same catalog visibly distinct.

Implementation order:

1. Decide when the identity is established and how the village chooses it.
2. Persist the two colors and banner pattern in village save data.
3. Define banner sockets and semantic color anchors in the structure-authoring format.
4. Prototype the level-1 forest firewatch tower, the level-2 flower tower, and the desert tavern.
5. Run villager navigation through every occupied floor before accepting a structure.

## Gallery expansion

The second review wing adds 24 practical village-scale references rather than more oversized
monuments. Its rows cover Towns and Towers Japanese, Swiss, and Pueblo villages, plus the Dungeons
and Taverns birch village. Each row includes a mix of housing, civic, food, and production
buildings so the next review can compare roles as well as silhouettes.

The dense third wing adds another 106 exhibits in thirteen compact rows. It extends the Dungeons
and Taverns birch set, then covers Towns and Towers classic, Romanian, Polish, Viking, swamp,
Polynesian, jungle, mushroom, Iberian, Nilotic, and sunflower-farm families. A final row compares
six Dungeons and Taverns wells. Buildings sit four blocks apart, and the rows emphasize recurring
roles so the review can compare variants instead of merely collecting unusual landmarks.

The fourth wing adds 96 exhibits from sources other than Towns and Towers. Four ChoiceTheorem's
Overhauled Village rows compare beach, alpine, swamp, and mesa settlements across the same twelve
roles. Four Millenaire rows compare Norman, Byzantine, Seljuk, and Mayan architecture using each
building's complete first-stage template rather than isolated upgrade fragments. The rows remain
four blocks apart horizontally and build one at a time to keep world generation bounded.

The Millenaire gallery copies translate its runtime markers and small custom block palette to close
vanilla stand-ins. This keeps the architectural massing, floor plans, and role comparisons visible
without changing the active dev mod stack; the source templates in the ValeCraft instance remain
untouched.

### Fourth-wing selections

| Reference | Decision | What carries forward |
| --- | --- | --- |
| CTOV beach family | Keep for Coast and Jungle | The center reads as a market, the houses establish a convincing warm shoreline language, and the farm and fishery make the set useful beyond housing. |
| CTOV alpine family | Keep for Mountain and Tundra | The enclosed farm is the standout reference for agriculture in hostile weather. The houses and center support a complete highland settlement. |
| CTOV swamp family | Keep for Swamp | Its bushy, overgrown character is distinct from both ordinary Forest and the Towns and Towers boat-based swamp set. |
| CTOV mesa family | Keep for Badlands | The sparse, frontier-like character supplies a useful alternative to Pueblo massing while remaining coherent across a whole town. |
| Millenaire families | Remove from the influence pool | No further Millenaire review or adaptation is needed. The gallery copies may remain as historical exhibits, but they do not inform Kithkyn structures. |

There are still many structures not shown. The local pack contains about 3,300 Dungeons and
Taverns templates, 2,100 CTOV templates, and 840 Towns and Towers templates, although many are
dungeon pieces, repeated material variants, or connectors rather than village buildings. The
next useful gallery should target unresolved boundaries instead of adding volume: CTOV mountain
versus snowy, CTOV jungle-tree versus beach, desert oasis, taiga, mushroom, and the unreviewed
Towns and Towers forest, rustic, Swedish, Tudor, Mediterranean, and Iberian families.

## Recommended village-biome roster

The twelve-group proposal was too coarse. It pooled several complete Towns and Towers families
under Plains, Forest, Tundra, Badlands, and Jungle, which would discard exactly the settlement
variety the gallery exposed.

The revised rule is one distinct village-biome catalog for every Towns and Towers Overworld
settlement family. This establishes 26 slots: Alpha Islands, Subtropical Grassland, Hot Shrubland,
Floodplain, Autumn Forest, Shield, Highlands, Desert Oasis, Badlands, Beach, Birch Forest, Flower
Forest, Forest, Grove, Jungle, Meadow, Mushroom Fields, Deep Ocean, Old Growth Taiga, Savanna
Plateau, Snowy Slopes, Snowy Taiga, Sparse Jungle, Sunflower Plains, Swamp, and Wooded Badlands.
The Piglin family reserves Nether Wastes as a twenty-seventh slot when Nether villages enter
scope. The corresponding table and source-family mapping live in [buildings.md](buildings.md).

Large non-Towns-and-Towers families are also separate when they establish a coherent settlement
language. The current review already adds Plains, Old Growth Birch Forest, Tropical Coast, Alpine
Highlands, Mangrove Swamp, and Mesa. A partial reference family may reserve a slot before every
building role exists; missing roles receive original structures or carefully chosen secondary
references rather than forcing two families into one catalog.

This is not limited by biome supply. The local ValeCraft pack's Terralith data contains 95 custom
biome definitions plus overrides for 35 vanilla Overworld biomes. The actual constraint is
authoring and navigation quality. Every real biome still maps to one village biome at founding,
while shared biome traits carry climate, resource, and placement behavior across architecturally
different catalogs.

Fortified sets, seasonal sets, and settlement stages remain separate axes. They do not become
fake biomes just because they contain many templates. The category-by-category pass also remains
sparse: highly visible buildings need strong village-biome variants, while visually neutral
industry can use shared structures where the environment does not demand a different layout.

Farmer's Structures and Ribbits both contain promising references, but their required runtime
libraries need newer NeoForge builds than the current Kithkyn dev instance. Keep them in the
source inventory and revisit them with the planned runtime upgrade instead of showing partially
missing structures now.

## Birch Forest lock-in wing

The first category-by-category selection wing is the Birch Forest catalog, anchored by the Towns
and Towers Romanian family. It presents all 17 building categories retained by the final cut in
`building-spec.md`, every planned level for each category, the current Kithkyn structure when
one exists, a leading recommendation, and useful alternatives. The wing contains 89 candidates in
18 walkable rows; the final row treats walls and gatehouses as a perimeter system rather than as
ordinary building footprints.

The rows are ordered as follows: Village Center, Houses, Well, Storehouse, Watchtower, Farm,
Lumberjack, Stoneworks, Mine, Hunting Lodge, Fishery, Bakery, Butchery, Blacksmith, Market, Church,
Tavern, and Perimeter System. Removed concepts such as library, brewery, workshop, and specialty
shop remain visible in the broader reference field but are deliberately excluded from this
required-building review.

The role-first wing already uses every one of the 22 Romanian building and decoration templates
at least once. A parallel side library now repeats each of those templates exactly once and adds
the family's 20 street and terminator pieces, producing a complete 42-template Romanian inventory.
It is connected to the Birch entry by an eastward bridge and grouped into nine densely packed,
labeled rows:

- Core and decoration: meeting point 1; decorations 1, 2, and 3.
- Homes: small houses 1 through 6; medium houses 1 and 2; large house 1.
- Professions: armorer and toolsmith; butcher and leatherworker; cartographer and library; fisher;
  fletcher; mason; shepherd; small farm; weaponsmith.
- Streets: corners 1 through 3; turn 1; crossroads 1 through 6; straight streets 1 through 6.
- End pieces: terminators 1 through 4.

Every exhibit has its own `R01` through `R42` sign, so the in-world labels form the requested
complete list while keeping the underlying source identity visible. Run
`/function valecraft_gallery:tour_romanian_reference` to enter the side library. If it has not
been built in the current world, first run
`/function valecraft_gallery:build_romanian_reference` and allow its nine scheduled rows to
finish. The library is intentionally separate from the candidate table: it supports visual
discovery and repurposing without implying that every Romanian piece has already been selected
for a Kithkyn role.

Each candidate sign uses one of four decision labels:

- `Best`: the leading direction for that role and level;
- `Option`: a credible alternative worth comparing in person;
- `Adapt`: a useful shell, silhouette, or scale reference that still needs a Kithkyn role
  layout;
- `Baseline`: the corresponding structure currently shipped by Kithkyn.

Run `/function valecraft_gallery:tour_birch_selection` to enter the wing. If the gallery is rebuilt
from source, run `/function valecraft_gallery:build_birch_selection` once and allow the scheduled
rows to finish before touring it. The selection process should now proceed row by row: choose a
candidate, record the required functional and palette changes, prototype it, check villager access
to every occupied floor and workstation, and only then lock the level into the Birch Forest
catalog.

The local gallery generator also corrects absolute attachment coordinates found in a handful of
source paintings and item frames. For an already-built wing carried through a server restart, run
`/function valecraft_gallery:repair_birch_selection_decorations` once to restore those decorations
without rebuilding any structure.
