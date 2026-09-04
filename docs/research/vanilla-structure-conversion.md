# Research: converting vanilla village structures

Resolves [#53](https://github.com/Quzzar/kithkyn/issues/53). Part of the wayfinder map in
[#47](https://github.com/Quzzar/kithkyn/issues/47).

Everything below was measured against the actual Minecraft 1.21.1 game files on this machine
(`~/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar` and the matching server
jar) and against the NeoForge 21.1.72 decompiled sources
(`build/moddev/artifacts/neoforge-21.1.72-minecraft-sources.jar`). Method and scripts are at the
end. Nothing here is from a wiki.

## The short answer

The claim in [building-spec.md](../building-spec.md) that vanilla buildings "convert directly and
cover a real fraction of phase 1 for free" is **half right and legally wrong**.

| Claim | Verdict |
| --- | --- |
| Vanilla ships convertible village buildings in five biome families | **True.** 152 non-zombie house templates plus 16 town centers. |
| They convert directly | **Nearly true.** One processor call strips the jigsaw blocks. There are no data markers and no structure voids to deal with. |
| They cover a real fraction of phase 1 | **Overstated.** Vanilla plausibly seeds about 30 of the 81 phase 1 files, and every one of those is a level 1. It covers zero of `storehouse`, `lumberjack`, `mine`, `hunting_lodge`, and `watchtower`. |
| Bed and work station coordinates can be extracted programmatically | **True, with a catch.** No vanilla house template contains both a bed and a work station. Not one of the 152. |
| We can ship copies of them in the mod jar | **False. Do not do this.** The EULA and the Usage Guidelines both forbid it. |
| We can reference them in place at runtime | **True, and this is the only legal route.** |

There is also an existing violation in the repo, described in
[The file already in the repo](#the-file-already-in-the-repo).

## 1. What vanilla actually ships

Minecraft 1.21.1 contains **1180 structure templates** total, under `data/minecraft/structure/` in
both the client and server jars. `village/` is by far the largest set at **483 files**, all at
`DataVersion 3955`, all with a single palette (none use the multi-palette `palettes` list).

Of the 483, **176 are zombie-village variants** and 307 are the live village. The zombie files are
degraded copies used by the `zombie_<family>` processor lists and are not useful to us.

### Per biome family, non-zombie

| Family | houses | town_centers | streets | terminators | villagers | decor and lamps |
| --- | --- | --- | --- | --- | --- | --- |
| plains | 36 | 4 | 16 | 4 | 3 | 1 |
| savanna | 31 | 4 | 19 | 1 | 3 | 1 |
| snowy | 30 | 3 | 17 | 0 | 3 | 3 |
| desert | 28 | 3 | 11 | 2 | 3 | 2 |
| taiga | 27 | 2 | 16 | 0 | 3 | 7 |
| **total** | **152** | **16** | **79** | **7** | **15** | **14** |

Plus `common/` (19 animal spawn markers, `iron_golem`, `well_bottom`) and `decays/` (3 grass
patches).

The `villagers/` and `common/animals/` files are not buildings. They are 1x2 or 1x3 columns of air
holding one or two entities, reached through a jigsaw from inside a house. That is how vanilla
populates a village: the house template ships empty and a jigsaw pulls a villager into it.

### Buildings are small

Across the 152 house templates the largest horizontal dimension has a **median of 9 blocks**, a
minimum of 5 and a maximum of 17. The most common footprint is 7x7x7 (15 templates). These are
cottages, not the multi-level buildings the level 2 and level 3 rows of `building-spec.md` describe.

### Mapping onto our 37 categories

Classified by filename and by the point-of-interest block each template contains.

| Our category | Vanilla candidates | Count | Note |
| --- | --- | --- | --- |
| `house` | `*_small_house_*`, `*_medium_house_*`, `plains_big_house_1` | 51 | The strongest match in the whole set |
| `village_center` | `*/town_centers/*` plus `plains_meeting_point_4`, `plains_meeting_point_5` | 18 | Bell is the only work station |
| `farm` | `*_farm_*`, `*_small_farm*`, `*_large_farm_*` | 13 | Composter as the farmer station |
| `blacksmith` | `*_tool_smith_*` (5) and `*_weaponsmith_*` (10) | 12 | Smithing table and grindstone |
| `pasture` | `*_animal_pen_*` (11), `plains_stable_1/2` (2) | 13 | Pens have no work station at all |
| `church` | `*_temple_*` | 8 | Brewing stand as the cleric station |
| `butchery` | `*_butcher*_shop_*` | 8 | Smoker |
| `armoury` | `*_armorer*` | 7 | Blast furnace |
| `quarry` | `*_mason*` | 6 | Stonecutter. A mason's house is not a quarry face, so this is a weak match |
| `library` | `*_library_*` | 6 | Lectern |
| `fishery` | `*_fisher*` | 5 | Barrel |
| `tannery` | `*_tannery_*` | 5 | Water cauldron |
| `weaver` | `*_shepherd*` | 5 | Loom. Also our closest `pasture` worker building |
| `fletcher` | `*_fletcher_house_*` | 5 | Fletching table |
| `well` | `plains_fountain_01` only | 1 | It is a town center, not a standalone well |

Vanilla also ships 5 cartographer houses and `plains_accessory_1`, which map onto nothing in our
catalog.

**Where vanilla has nothing.** Twenty two of our thirty seven categories have no vanilla source at
all: `storehouse`, `granary`, `market`, `inn`, `graveyard`, `hunting_lodge`, `mushroom_cellar`,
`apiary`, `mill`, `bakery`, `brewery`, `lumberjack`, `mine`, `charcoal_burner`, `pottery`,
`glassworks`, `alchemist`, `watchtower`, `barracks`, `training_yard`, `wall`, `gatehouse`.

Two near misses outside `village/`: `pillager_outpost/watchtower.nbt` is a real watchtower, and
`igloo/top.nbt` is the `house_igloo` variant the manifest asks for. Both carry the same licensing
problem as everything else here.

**Where vanilla has things our catalog missed.** The cartographer house is a category we do not
have. So is the standalone animal pen with no worker, which is a cheap decorative building a
village could plausibly want. The zombie sets are a ready-made "ruined village" content pack if we
ever want abandoned sites for `site-selection`.

### Against the phase 1 manifest specifically

The manifest asks for 81 phase 1 files. Vanilla plausibly seeds these:

| Category | Manifest files | Vanilla covers | What is missing |
| --- | --- | --- | --- |
| `house` | 21 | ~15 | No `stilt` variant. `igloo` only from the separate igloo structure |
| `village_center` | 15 | 5 | Level 1 only |
| `farm` | 9 | 3 | Level 1 only, plains/taiga/desert |
| `well` | 4 | 0 or 1 | The fountain is a town center |
| `fishery` | 4 | 2 | No `marsh` variant |
| `quarry` | 3 | 1 | And it is a mason's house, not a quarry |
| `storehouse` | 3 | 0 | |
| `lumberjack` | 6 | 0 | |
| `mine` | 3 | 0 | |
| `hunting_lodge` | 4 | 0 | |
| `watchtower` | 9 | 0 | Outpost watchtower is outside `village/` |

Best case around **27 to 30 of 81**, and every one of them is a level 1 building of roughly cottage
size. The level 2 and level 3 rows of the manifest have no vanilla source whatsoever. "A real
fraction" is defensible for phase 1 house and farm variety. It is not defensible as a claim about
the sourcing plan as a whole.

## 2. The technical conversion path

This is the good news. Vanilla village templates are far cleaner than the ticket assumed.

### What is actually in them

Measured across all 483 village templates:

| Thing | Present? |
| --- | --- |
| Jigsaw blocks | Yes. Every one of the 221 house templates has between 1 and 8 |
| Structure voids | **No.** Zero village templates contain `minecraft:structure_void` as a block |
| Data markers (`structure_block` in DATA mode) | **No.** Zero village templates contain a structure block |
| Multiple palettes | No. All single palette |
| Entities | Only 47 templates, almost all of them the `villagers/` and `animals/` spawn markers. Exactly one house (`taiga/houses/taiga_armorer_2.nbt`) carries an entity |
| Explicit air blocks | Yes, in 150 of 152 houses |

So of the four things the ticket listed as needing stripping, only one exists. Data markers and
structure voids are used by the nether bastions and the trial chambers, not by villages.

### Stripping the jigsaws

Do not hand-edit. Vanilla already ships the processor that does this:
`net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor.INSTANCE`.
It reads the jigsaw block entity's `final_state` string, parses it as a block state, and either
substitutes that state or drops the block entirely when `final_state` resolves to
`minecraft:structure_void`.

Across all 483 templates the `final_state` distribution is:

| final_state | Occurrences |
| --- | --- |
| `minecraft:structure_void` (block is deleted) | 1124 |
| `minecraft:grass_block` | 373 |
| `minecraft:dirt` | 66 |
| `minecraft:dirt_path` | 53 |
| everything else (sand, sandstone, planks, stairs, fences, snow) | ~250 |

Every jigsaw in the set has a valid `final_state`. Nothing has to be guessed.

The fix in our placer is one line:

```java
this.settings = new StructurePlaceSettings()
    .setRotation(this.rotation)
    .setRandom(RandomSource.create(this.random.nextLong()))
    .addProcessor(JigsawReplacementProcessor.INSTANCE);
```

Both `InstantBuildStructure` and `StructureInProgress` run
`StructureTemplate.processBlockInfos(...)` with the place settings, so a processor added here is
respected by both. Neither currently adds any processor at all
(`grep -rn "addProcessor" src/main/java` returns nothing), which means **today a raw vanilla
template would place literal jigsaw blocks into the world**. They are visible in survival and they
are not solid, so a house would have holes in it.

### Two behaviour differences to decide on, not bugs

**Air handling.** Every village pool entry uses `minecraft:legacy_single_pool_element`, not
`single_pool_element`. `LegacySinglePoolElement.getSettings` swaps
`BlockIgnoreProcessor.STRUCTURE_BLOCK` for `BlockIgnoreProcessor.STRUCTURE_AND_AIR`, so vanilla
**does not place the air blocks** in a village template. It builds around whatever terrain is
already there. Our placer does place air, which carves the building's bounding box out of the
hillside. For an autonomous builder that is probably what we want, but it is a real difference in
how the buildings will look, and it is worth seeing rendered before committing.

**Terrain matching.** Not a problem for houses. Of the 627 village pool entries, 445 are `rigid`
and 182 are `terrain_matching`, and **every single houses and town_centers entry is `rigid`**. Only
streets use terrain matching, via `GravityProcessor(WORLD_SURFACE_WG, -1)`. We do not build streets
from vanilla, so `GravityProcessor` never enters the picture.

### Pool-level processors we would lose

Houses carry a processor list from the pool, not from the template. Nothing load-bearing:

- plains and taiga houses: `minecraft:mossify_10_percent`
- desert, savanna, snowy houses: empty
- all farm templates: `minecraft:farm_<family>`, which randomizes the crop growth stages

Only the farm one matters, and only cosmetically. Crops would all place at the same growth stage.

### The `minecraft:village/...` references

The pool references live in the worldgen data
(`data/minecraft/worldgen/template_pool/village/**`), not in the templates. The only in-template
reference is the `pool` field on each jigsaw block entity, and
`JigsawReplacementProcessor` deletes the whole block. There is nothing left pointing at
`minecraft:village/...` after processing.

## 3. Referencing them in place

**Yes, and it works cleanly.** `StructureTemplateManager` resolves any `ResourceLocation`, and the
vanilla templates are ordinary datapack resources at
`data/minecraft/structure/village/plains/houses/plains_small_house_1.nbt`. Both the client jar and
the server jar carry all 483, so this works in single player and on a dedicated server.

The one code change needed: `InstantBuildStructure` and `StructureInProgress` both hardcode our
namespace.

```java
// InstantBuildStructure.java:62, StructureInProgress.java:125
ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, building.getInfo().getPath())
```

`BuildingInfo`'s `structure` field is a bare `String` and would have to become a full
`ResourceLocation` (`ResourceLocation.CODEC` in place of `Codec.STRING`) so a definition can say
`"minecraft:village/plains/houses/plains_small_house_1"`. Everything downstream already goes
through `getOrCreate`, which takes a `ResourceLocation`.

**Does it survive someone changing them?** Two separate answers.

- **A resource pack cannot touch them.** Structure templates are datapack content, not resource
  pack content. A texture pack is irrelevant here.
- **A datapack can, and we would silently get the replacement.**
  `StructureTemplateManager`'s sources are, in order: the world save's `generated/<ns>/structures/`
  directory, then the resource manager. The resource manager returns the highest-priority pack that
  has the file, so any datapack overriding `minecraft:village/plains/houses/plains_small_house_1`
  wins. `structureRepository` is a cache but `onResourceManagerReload` clears it, so a
  `/reload` picks up the change.

That last point is the real risk of referencing in place, and it is not a small one. Our
`BuildingInfo` would carry hardcoded bed and work station coordinates for a template someone else
can replace. A datapack that swaps in a different building leaves our bed at coordinates that are
now solid stone. Mitigation: re-derive the coordinates from the loaded template at build time
rather than trusting the JSON, which section 5 shows is cheap. That also removes the need to store
them at all for vanilla-referencing definitions.

## 4. Licensing

**No. We may not ship copies of Mojang's `.nbt` files in the mod jar, modified or unmodified, paid
or free.** This is not a grey area and it is not a "everyone does it" situation.

### The Minecraft EULA

<https://www.minecraft.net/en-us/eula>

The summary line is "Do not distribute or make commercial use of anything we've made without our
permission." The operative section says "you must not distribute anything we've made unless we
specifically agree to it", and defines the prohibited act to include "give copies of our game
software or content to anyone else". The scope is explicitly partial as well as whole: it covers
"modified versions of a game, part of those things". A single `.nbt` is part of the game's content.

The mod carve-out is defined so as to exclude exactly this. The EULA's definition:

> "By 'Mods,' we mean something original that you or someone else created that doesn't contain a
> substantial part of our copyrightable code or content."

followed by "We have the final say on what constitutes a Mod and what doesn't." The permission to
distribute a mod is conditioned on the artefact not containing Mojang content. A jar carrying
`plains_small_house_1.nbt` fails that test on its face, and whether it counts as "substantial" is a
call Mojang reserves to itself, not one we get to make.

"But we modified it" does not help. The CONTENT section states Mojang owns "things that are copies
(or substantial copies) or derivatives of our property and creations", with the worked example that
a single Minecraft block including its look and feel is theirs.

### The Usage Guidelines

<https://www.minecraft.net/en-us/usage-guidelines>

This page has the single most on-point sentence, under the essential guidelines that apply to all
uses:

> "Do not redistribute our games or any alterations of our games or game files"

"Alterations of game files" is precisely "take a vanilla `.nbt`, strip the jigsaws, ship it". The
same page defines assets to include "the code, software, graphics, textures, images, models, sounds
and other audio from any of our games", and permits mod distribution only where "You distribute the
mod only and not a modded version of Minecraft".

It also defines commercial use as "any uses of our name, brand, or assets that you use and share
with others (regardless of whether you receive payment or provide it for free)". Being free is not
a defence for asset redistribution.

Two caveats about this document's status, both stated on the page itself. It says the guidelines
"do not form part of the policies", and that Mojang is "not able to give advice about whether a
specific project does or does not comply". It is a revocable permission layered on the EULA, not a
licence grant to rely on.

### Where the documents are silent

Worth stating plainly, because these are the gaps people argue in.

- Neither document mentions `.nbt`, "structure", "template", or "data pack". There is no clause
  written specifically about structure templates.
- Neither document contains "decompile", "reverse engineer", or "disassemble". **Extracting the
  files to look at them, as this research did, is not the prohibited act. Redistributing them is.**
- Neither quantifies "substantial part". No threshold, no percentage, no file count.
- There is no clause expressly permitting a mod to bundle Mojang assets under any condition.

### Corroboration from the distribution platforms

Secondary, and it does not change the legal answer. It does mean the channels will enforce it.

- Modrinth content rules (<https://modrinth.com/legal/rules>) require that you "must own or have
  the necessary licenses, rights, consents, and permissions" for what you upload.
- CurseForge moderation policies
  (<https://support.curseforge.com/support/solutions/articles/9000197279-moderation-policies>) state
  "CurseForge follows game developers EULA and ToS, and so should your projects" and "Your project
  should contain distinct content and assets".

No Mojang employee statement on an official channel addressing bundled vanilla assets in mods could
be found. Forum posts exist and are not authority. Treat the licence text as the only official word.

### Why referencing at runtime is a different situation

Resolving `minecraft:village/plains/houses/plains_small_house_1` through the game's own
`StructureTemplateManager` is fine, and the reason is structural rather than a loophole.

1. **No copy is distributed.** The prohibited act is defined as distribution: "give copies of our
   game software or content to anyone else". A `ResourceLocation` is a string. The bytes never
   leave Mojang's own distribution channel, and every player already got that file from Mojang
   under their own licence.
2. **The player already holds the permission.** The EULA grants that buying the game means "you can
   download, install, and play them" and that "you may play around with it and modify it by adding
   modifications, tools, or plugins".
3. **The EULA names in-memory mods as ours.** "Any Mods you create for Minecraft: Java Edition from
   scratch belong to you (including pre-run Mods and in-memory Mods)."
4. **It satisfies the Mod definition.** The definition turns on what the mod *contains*. Code that
   references content contains no content. Code that embeds an `.nbt` does.

In one line: shipping the file makes us a distributor of Mojang's work, reading the file makes us a
consumer of the player's own licensed copy.

This is a reading of two licence documents, not legal advice. The Usage Guidelines themselves
suggest speaking to an attorney where a specific project is unclear.

### What this means for the sourcing plan

Three legal routes, in preference order.

1. **Reference vanilla in place** by `ResourceLocation`. Costs nothing, ships nothing, and is the
   only way vanilla content reaches our catalog. Constrained by the datapack-override risk in
   section 3.
2. **Rebuild by hand in a structure block** and save under `kithkyn:`. A building "inspired by"
   the vanilla plains small house, actually built by a person, is ours. This is what the existing
   twelve files under `data/kithkyn/structure/` presumably are.
3. **Third-party sets with written permission**, which `building-spec.md` already requires.

What is not available: extracting, stripping, and shipping. That has to come out of the plan.

## The file already in the repo

`src/main/resources/data/minecraft/structure/village/common/iron_golem.nbt` is a live instance of
the prohibited pattern and should be dealt with before any public release.

Compared against vanilla's `village/common/iron_golem`:

| | Repo file | Vanilla 1.21.1 |
| --- | --- | --- |
| size | `[1, 3, 1]` | `[1, 3, 1]` |
| blocks | 3 | 3 |
| palette | jigsaw, air | air, jigsaw |
| entities | **7 iron golems** | 1 iron golem |
| DataVersion | **1976** (1.13 era) | 3955 |

Same path, same size, same block layout, same palette, with the golem count changed from one to
seven. That is an alteration of a Mojang game file being redistributed, which the Usage Guidelines
name explicitly.

There is a second, non-legal problem with it. Because it sits under the `minecraft` namespace at a
vanilla path, it overrides vanilla worldgen for the whole instance. **Every naturally generated
village in any world with this mod installed spawns seven iron golems instead of one**, and it
overrides the same file for every other mod and datapack in the pack. That is almost certainly not
intended.

Suggested resolution, out of scope for this ticket: delete it, and if the seven-golem behaviour is
wanted, produce it from our own code at village founding rather than by overriding a vanilla
template.

## 5. Beds, work stations, and containers

**Extraction is trivially programmatic.** A template is a palette plus a flat list of
`{pos, state}`, so finding every bed and every point-of-interest block is a single pass. A 60 line
script did all 483 files in under two seconds.

Yields across the 152 non-zombie house templates:

| | Count |
| --- | --- |
| Bed heads (one per bed, deduplicated from the two-block bed) | 67 |
| Point-of-interest work station blocks | 116 |
| Chests and barrels | 57 |
| Templates yielding at least one of the three | 138 of 152 |

Sample output, in `BuildingInfo` JSON shape, generated straight from the template:

```json
{ "structure": "plains_small_house_1", "beds": [[4, 1, 2]] }
{ "structure": "plains_armorer_house_1",
  "work_stations": [{ "pos": [4, 1, 6], "occupation": "ARMORER" }] }
{ "structure": "plains_meeting_point_1",
  "work_stations": [{ "pos": [3, 2, 7], "occupation": "LEADER" }] }
```

The block-to-occupation mapping is vanilla's own point-of-interest list, which is unambiguous:
`blast_furnace` armorer, `smoker` butcher, `cartography_table` cartographer, `brewing_stand`
cleric, `composter` farmer, `barrel` fisherman, `fletching_table` fletcher, `cauldron` and
`water_cauldron` leatherworker, `lectern` librarian, `loom` shepherd, `smithing_table` toolsmith,
`grindstone` weaponsmith, `stonecutter` mason, `bell` meeting point.

### The catch, and it is the biggest finding in this ticket

**Not one of the 152 vanilla house templates contains both a bed and a work station.**

- 51 have beds and no work station (the small, medium, and big houses)
- 87 have a work station and no bed (every profession building)
- 14 have neither (animal pens, stables, `plains_accessory_1`)

Vanilla separates sleeping from working completely. Our own definitions do not: every one of the
nine files in `data/kithkyn/kithkyn/buildings/` pairs a bed with a work station in the same
structure, and `blacksmith.json` is the canonical example. A converted vanilla armorer's house
would house nobody, and a converted vanilla small house would employ nobody.

That is not a conversion bug. It is a design difference, and it is worth deciding on before any
conversion work starts. Either our buildings become vanilla-shaped (job buildings and dwellings are
separate categories, which the catalog already half assumes with `house` as its own category), or
converted vanilla job buildings need beds added by hand, which means they are no longer "free".

### Two smaller mismatches

**Occupation coverage.** Vanilla's 13 professions map onto only 5 of our current 11 `Occupation`
values. `ARMORER`, `BUTCHER`, `CARTOGRAPHER`, `FISHER`, `FLETCHER`, `TANNER`, `WEAVER`, `MASON`,
`HUNTER`, `MERCHANT`, `INNKEEPER` and the rest of the catalog's worker list do not exist in
`Occupation` yet. Any extractor has to either extend the enum or map several vanilla POIs onto one
of ours.

**Coordinate space.** Extraction gives coordinates in template space, which is correct, but the
runtime lookup does not transform them the way the template placer does. `LocationManager` and
`CoreEvents` both do `BlockPos.of(loc).rotate(building.getRotation())`, which rotates about the
origin. `StructureTemplate` rotates about the template's size box via
`StructureTemplate.calculateRelativePosition(settings, pos)`, giving `(size.z - 1 - z, x)` for a
90 degree turn rather than `(-z, x)`. `InstantBuildStructure.setOriginLocation` patches over some
of this with hardcoded `offset(1, 0, 0)` and `offset(0, 0, 1)` nudges. Any programmatic extraction
should go through `calculateRelativePosition` and the fudges should come out. Adjacent to this
ticket, but it will bite whoever does the conversion first.

### Where extraction cannot help

Two `BuildingInfo` fields have no vanilla source.

- **`gathering_point`.** Only the campfire-bearing meeting points would yield one. Everything else
  needs a human to pick a spot.
- **`cost`.** Nothing in a template says what it should cost. It could be approximated by counting
  non-air blocks per material, which would at least give a starting number to tune, but that is a
  design call rather than an extraction.

## Recommendation

1. **Correct `building-spec.md` and `buildings.md`.** Both currently say vanilla structures "convert
   directly" without any licensing qualifier. Replace with: vanilla structures may be *referenced*
   by `ResourceLocation`, never copied into the jar.
2. **Delete `data/minecraft/structure/village/common/iron_golem.nbt`** and reimplement the intent in
   code. It is both a licence problem and an unintended global worldgen override.
3. **Add `JigsawReplacementProcessor.INSTANCE`** to the place settings in `InstantBuildStructure`
   and `StructureInProgress`. This is needed regardless of the vanilla question: any template a
   builder saves from a structure block next to a jigsaw hits the same problem.
4. **Widen `BuildingInfo.structure` to a `ResourceLocation`** so a definition can point at
   `minecraft:`.
5. **Decide the bed-and-workstation question** before writing a converter. It determines whether
   vanilla job buildings are usable at all.
6. **Do not plan on vanilla for more than about a third of phase 1**, and expect nothing at all for
   levels 2 and 3.

## Method

Reproducible from this repo after one `./gradlew` run has populated the NeoForm caches.

- Village templates: `~/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar` and
  `minecraft_1.21.1_server.jar`. The server jar is a bundler; the unpacked resources are at
  `~/.gradle/caches/neoformruntime/intermediate_results/stripServer_*_resourcesOutput.jar`.
- Decompiled sources: `build/moddev/artifacts/neoforge-21.1.72-minecraft-sources.jar`.
- Template parsing: a minimal gzip plus NBT reader in Python. Templates are gzipped NBT with a
  `size` list, a `palette` list of `{Name, Properties}`, and a `blocks` list of
  `{pos, state, nbt?}`.
- Counts were taken over all 483 files, not sampled.

Vanilla classes referenced, all verified present in NeoForge 21.1.72:

- `net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager`
  (`get`, `getOrCreate`, `onResourceManagerReload`)
- `...templatesystem.JigsawReplacementProcessor.INSTANCE`
- `...templatesystem.BlockIgnoreProcessor.STRUCTURE_BLOCK`, `.STRUCTURE_AND_AIR`
- `...templatesystem.StructurePlaceSettings.addProcessor`
- `...templatesystem.StructureTemplate.calculateRelativePosition`
- `...structure.pools.SinglePoolElement.getSettings`,
  `...structure.pools.LegacySinglePoolElement.getSettings`
- `...structure.pools.StructureTemplatePool.Projection` (`RIGID`, `TERRAIN_MATCHING`)
