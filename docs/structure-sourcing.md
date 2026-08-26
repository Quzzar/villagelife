# Structure sourcing: where buildings can legally come from

**Survey, not a decision.** [building-spec.md](building-spec.md) needs roughly 173 structure
files at full coverage, 81 for phase 1, and 11 for a minimum playable village. This is the
inventory of where those could come from and what each source permits.

**Every license below was read from the source's own LICENSE file or license field, not from a
summary.** That matters: the most promising candidate is described as ShareAlike in search
results and on aggregator pages, and its actual LICENSE file says NoDerivatives, which is the
difference between usable and not.

## The short version

There is **no drop-in, legally clean source of village buildings.** Every source is one of:
permissively licensed but not villages, villages but not permissively licensed, or unclear
per-item. Whatever we do involves either asking authors directly or building our own.

## The sources

| Source | Content | License | Can we adapt it? |
| --- | --- | --- | --- |
| **Vanilla Minecraft** | 483 village templates, 5 biome families | Minecraft EULA | **No copy, reference only.** Loading by `ResourceLocation` at runtime is legal; shipping the `.nbt` is not. See [the research](https://github.com/Quzzar/villagelife/issues/53). |
| **ChoiceTheorem's Overhauled Village** | 23 village variants, 14 pillager outposts | **CC BY-NC-ND 4.0** | **No.** NoDerivatives forbids distributing an adapted version, and converting to our format is exactly that. Needs the author's direct permission. |
| **Towns and Towers** | 837 structures, villages and outposts | **CC BY-NC-ND 4.0** (see below) | **No.** Modrinth's license field says ShareAlike; the bundled LICENSE file says NoDerivatives and forbids changes beyond config. |
| **Better Villages** (jTorleon) | Houses in plains, snow, savanna, desert, taiga | All Rights Reserved | **No.** Needs the author's direct permission. |
| **YUNG's mods** (14 structure mods) | Mineshafts, strongholds, dungeons, temples, bridges. **No village mod exists.** | LGPL v3 | **Yes, with conditions.** Attribution and source availability; LGPL applied to assets is legally murky but the intent is clearly permissive. |
| **minecraft-schematics.com, Planet Minecraft, MinecraftSchem** | Thousands of community builds | Per-item, usually unstated, effectively All Rights Reserved | **Per-build permission from each author.** Viable for a handful of hero buildings, not for 173. |

## What this means per source

### Vanilla: reference, never copy

Settled by the research ticket. Covers 27 to 30 of the 81 phase 1 files, all level 1, and 22
of our categories have no vanilla equivalent at all. Also: not one of the 152 vanilla house
templates contains both a bed and a work station, which is its own open question
([#61](https://github.com/Quzzar/villagelife/issues/61)).

### CTOV: the best fit, and the one we cannot touch

It is the closest thing to what the catalog wants, and NoDerivatives closes it completely.
Note the discrepancy: Modrinth and general search describe it as ShareAlike. The repository's
LICENSE file is `Attribution-NonCommercial-NoDerivatives 4.0 International`. **The file wins.**
Worth asking the author, since a specific grant would unlock the single best source.

### Towns and Towers: the same trap as CTOV, and it caught us

Downloaded and checked. The datapack build carries **837 structure files** and a one-line
`LICENSE`:

> You may use this mod in a modpack as long as you give credit (Linking back to this page), you
> do not make any changes to the mod aside from config files, and you follow all of the other
> terms listed in the license.
> Full License: https://creativecommons.org/licenses/by-nc-**nd**/4.0/

**Modrinth's license field for the same project says `CC-BY-NC-SA-4.0`.** The two disagree, the
bundled file is NoDerivatives, and "no changes aside from config files" is about as explicit as a
grant of no-derivative-rights gets. Converting these structures into our building definitions is
exactly the prohibited act.

The files were downloaded, inspected, and deleted. **This doc previously recommended adopting
Towns and Towers on the strength of the Modrinth field. That recommendation was wrong**, made
without checking the file, in a document whose own opening rule is that the file wins.

### YUNG's: permissive, and no villages

The premise that YUNG has a village mod is wrong. The full 24-repo list contains no village
project; the closest is YUNGs-Roads, which generates paths *between* villages. Everything is
LGPL v3.

What is still useful to us, since the catalog is not only houses:

| YUNG's mod | Our category |
| --- | --- |
| Better Mineshafts | `mine`, all three levels |
| Better Strongholds | `watchtower_*_3` (keep tower), `gatehouse_*_3` (barbican) |
| Better Dungeons | `barracks`, `armoury` |
| Better Desert Temples | desert variants of civic buildings |
| YUNG's Bridges | infrastructure, once roads and perimeters exist |

### Community schematic sites: hero buildings only

Almost nothing carries an explicit license. Uploading to a gallery is not a grant of reuse
rights. Realistic for a small number of standout buildings where we ask the individual author,
and not realistic at catalog scale.

## The realistic paths

1. **Ask.** CTOV and Better Villages are both single-author projects with contact channels. A
   specific written grant for villagelife is the highest-value outcome and costs a message.
2. **Adopt Towns and Towers** and accept CC BY-NC-SA on derived structures. Available today,
   and it makes a licensing decision for the project.
3. **Build our own**, seeded by YUNG's LGPL content where categories match, and by vanilla
   referenced at runtime for the biome families it covers.
4. **Commission.** Minecraft builders take commissions, and 173 structures is a real budget.

These are not exclusive. The likely answer is 1 plus 3, with 2 as the fallback if nobody
answers.

## Reviewing candidates

Whatever the source, we need to look at buildings before choosing them. That wants a **gallery
world**: a superflat world where every loaded structure is placed in a labeled grid, walkable
end to end.

This is worth building regardless of sourcing, because it also serves the content pass and any
future structure review. It needs no third-party assets: it works on whatever definitions are
loaded, which today is the nine we already have.

**Built and verified:** `/villagelife gallery [pos]` (`StructureGallery.java`) places every loaded
definition on labelled plinths, grouped by category, then variant, then level. Signs carry the id,
the level, and the bed and job counts. It skips any definition whose structure file is missing and
reports how many it placed.

### Known defect in the existing structure files

The first gallery run surfaced something that had been silently true for a long time: three
`Block-attached entity at invalid position` errors fired during placement, at garbage coordinates.
The old structure `.nbt` files, most likely the church and the blacksmith, **contain item frames or
paintings whose entity coordinates do not survive placement, so that decor silently vanishes** in
every village ever built. It never logged anywhere anyone was looking.

Strip or re-place those entities when re-authoring. It is a content bug, not a placement bug.

## Settled: villagelife is never commercial

**Decided (Aaron): the mod is free to the public, permanently.** That removes the NonCommercial
obstacle from every source here.

It unlocks less than hoped, because **NonCommercial was never the binding constraint.**
NoDerivatives is, it is independent of NonCommercial, and both large village mods carry it.

## The conclusion, after checking every file

**There is no legally usable source of village buildings.** Not one. The two mods that have what
the catalog wants are both NoDerivatives; vanilla may be referenced but never shipped; community
schematics are unlicensed by default.

That leaves exactly two paths, and they are not really alternatives:

1. **Ask.** CTOV and Towns and Towers are both small-team projects with contact channels, and a
   specific written grant costs a message. This is the only route to reusing existing work.
2. **Build our own.** Which is now more attractive than it was, because the tooling landed anyway:
   `/villagelife gallery` reviews a whole catalog at a glance, and `/villagelife save-structure`
   captures a build in-world into a structure file. Together those are a content pipeline, and
   they turn "author 173 structures" from a wall into a repeatable process.

**Looking is not deriving.** Installing any of these mods and walking their villages for reference
is entirely legitimate and is how design normally works. The line is what enters our repo.

## Open questions

- **Are we willing to ShareAlike our structure files?** Adopting Towns and Towers means yes: our
  derived structures would carry CC BY-NC-SA. It affects the structure files, not the mod's code.
- **Who asks CTOV and jTorleon**, and what exactly are we requesting? A blanket grant, or
  permission for named structures? Worth doing anyway, since CTOV is the best fit that exists.
