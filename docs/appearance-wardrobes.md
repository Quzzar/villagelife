# Occupational wardrobes

The clothing layer is a projection of a person's current occupation. It is not a gene and it
is not inherited. Skin, eyes, and hair identify the person; clothing identifies what they are
doing now.

## Integration state

The game has mechanical `StatBlock` genetics, marriage, independent `AppearanceGenes`, runtime
texture compositing, and occupation-driven clothing. `PersonRenderer` derives one recipe from
synced person data and uses the client texture cache; the old complete-skin `PersonSkins` pool
has been removed.

The Skin Splice Lab remains the audited source pipeline. Its 36 full packs and 29 clothing-only wardrobes carry a
`clothingProfile` with life-stage and occupation compatibility. The viewer applies life stage
before occupation while preserving the existing gender, face-profile, eye-visibility, and
model-geometry rules. Clothing-only assets never enter the skin, hair, eye, or starting-skin
pools. The runtime exporter reduces that catalog to the fields and 208 PNG layers the game uses.

## Runtime seam

```text
AppearanceGenes + person seed  -> skin + eyes + hair structures and pigments
current Occupation + same seed -> clothing
                                  --------
                                  SkinRecipe -> client texture cache
```

- `AppearanceGenes` is persisted and synced on the person. Its recombination API is ready for
  the child-creation path when that separate gameplay system exists.
- Adult clothing is selected from the current occupation's compatible pool. Changing jobs changes
  only this field of the recipe.
- Child clothing is selected from the child commonwear pool. Children never draw from an adult
  occupation, even when their parents have jobs.
- A deterministic hash of `(person appearance seed, occupation, life stage)` chooses a garment.
  Switching away from a job and back restores that person's familiar version of the uniform
  without new save data. Regional style is not an input yet.
- `WANDERER` is ordinary resident/commonwear. `WANDERING_MERCHANT` is a separate travel-and-trade
  wardrobe with one shared uniform derived directly from the supplied Trader Guy 3 skin.
- Guard armor continues to render through Minecraft's equipment layers. Guard clothing should be
  a tunic, gambeson, or tabard underneath it, not baked iron armor.
- Children inherit appearance, never a parent's job clothes. Their eight age-specific garments
  are a commonwear pool rather than an occupation pool.

## Occupation implementation state

Sixteen occupations occur in current building data: baker, blacksmith, builder, butcher, cleric,
farmer, fisher, guard, herder, hunter, innkeeper, lumberjack, mason, merchant, miner, and
quartermaster. `WANDERER` is the live idle-resident state and `WANDERING_MERCHANT` is a live
traveller path.

Five enum values are only partial:

- `LIBRARIAN` has signature book gear but no current workstation or work loop.
- `TANNER` has a work loop but no current building definition that assigns it.
- `BREWER` has neither a current building definition nor a work loop.
- `INNKEEPER` has tavern workstations but no productive work loop.
- `LEADER` has special-case behavior but no current workstation assignment.

The wardrobe catalog includes all 22 enum values so future work does not require reclassifying
the art, but texture generation should prioritize roles villagers can currently hold.

## Current coverage

The useful floor is three male-compatible and three female-compatible garments per resident
occupation. A shared/non-binary garment contributes to both pools. Wandering merchant is an
intentional singleton uniform with a target of one shared garment. This is compatibility, not
the source character's identity.

| Occupation | State | Total | Male-compatible | Female-compatible | Shared | Generation gap |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| Wanderer | live | 9 | 7 | 8 | 6 | covered |
| Guard | live | 10 | 9 | 8 | 7 | covered |
| Blacksmith | live | 4 | 4 | 3 | 3 | covered |
| Farmer | live | 5 | 5 | 3 | 3 | covered |
| Cleric | live | 5 | 3 | 4 | 2 | covered |
| Librarian | partial | 6 | 3 | 6 | 3 | covered |
| Merchant | live | 9 | 4 | 7 | 2 | covered |
| Lumberjack | live | 3 | 3 | 3 | 3 | covered |
| Builder | live | 6 | 6 | 4 | 4 | covered |
| Miner | live | 4 | 4 | 3 | 3 | covered |
| Mason | live | 5 | 5 | 3 | 3 | covered |
| Tanner | partial | 6 | 3 | 6 | 3 | covered |
| Hunter | live | 7 | 6 | 7 | 6 | covered |
| Fisher | live | 4 | 3 | 3 | 2 | covered |
| Baker | live | 5 | 3 | 4 | 2 | covered |
| Butcher | live | 5 | 5 | 3 | 3 | covered |
| Brewer | partial | 4 | 3 | 3 | 2 | covered |
| Herder | live | 5 | 5 | 4 | 4 | covered |
| Innkeeper | partial | 7 | 4 | 5 | 2 | covered |
| Quartermaster | live | 5 | 3 | 3 | 1 | covered |
| Leader | partial | 5 | 3 | 3 | 1 | covered |
| Wandering merchant | live | 1 | 1 | 1 | 1 | covered |

`node tools/skin-lab/report-wardrobe-gaps.mjs` regenerates this analysis from the canonical
catalog. The exact garment-to-role assignments live in
`tools/skin-lab/wardrobe-catalog.mjs` and are visible through the viewer filter.

The former provisional wandering-merchant assignments remain redistributed to ordinary roles
where each garment actually belongs. Trader Guy 3 Clothing is the only garment assigned to the
special wandering-merchant role.

## Completed adult texture batch

Twenty shared occupational clothing layers close every current coverage gap, including the
partial/future librarian, tanner, and brewer roles. A shared garment contributes to both the
male-compatible and female-compatible pools without forcing an identity onto the wearer:

1. Coal-dust miner — miner, mason, builder.
2. Deep-shaft worker — miner, mason, builder.
3. Stone-yard cutter — miner, mason, builder.
4. Village smith apron — blacksmith.
5. Foundry apron — blacksmith.
6. Fieldhand tunic — farmer, herder.
7. Shepherd's coat — farmer, herder.
8. Parish cleric — cleric.
9. Travelling healer — cleric.
10. River fisher oilskin — fisher.
11. Dock fishmonger — fisher, butcher.
12. Smokehouse butcher — butcher.
13. Bakehouse apron — baker, butcher, innkeeper.
14. Guild baker — baker, innkeeper.
15. Archive librarian — librarian.
16. Field scholar — librarian.
17. Bark tanner apron — tanner.
18. Leather currier — tanner.
19. Cellar brewer — brewer.
20. Guild brewer — brewer.

## Wandering-merchant wardrobe

Trader Guy 3 Clothing is the only wandering-merchant option. It is a shared, clothing-only
asset derived from `trader-guy-3.png`, so male, female, and non-binary recipes all wear the same
recognizable uniform. The source's skin, eyes, and hairstyle are not part of the selectable
library.

The hood and cloak that the general extractor had placed in hair are moved into the clothing
sheet. Opaque headwear texels mask inherited hair only on the base-head and hat UVs. Hair remains
visible through genuine openings, and long hair still renders above body clothing. The retained
source hash and zero-mismatch extraction are audited on every rebuild.

## Child commonwear

Eight original garments form a separate child life-stage pool:

1. Playday smock, shared.
2. Patchwork child tunic, shared.
3. Young apprentice, shared.
4. Winter child coat, shared.
5. Sunday child tunic, masculine.
6. Page's doublet, masculine.
7. Market-day pinafore, feminine.
8. Festival child dress, feminine.

This yields six male-compatible and six female-compatible child outfits: each side gets its
two specific garments plus the four shared garments. Child textures use the same 64x64 player
UV as adult clothing. Entity age controls the rendered body scale; the texture itself does not
need a second UV layout.

The source PNGs are exact 64x64 clothing-only layers under
`tools/skin-lab/assets/<wardrobe-slug>/clothing.png`. Their source of truth is
`tools/skin-lab/authored-wardrobes.mjs` for original wardrobes and
`tools/skin-lab/source-wardrobes.mjs` for the trader uniform. Rerunning `extract-skins.mjs`
reproduces them and their manifests deterministically.

`scripts/export-appearance-assets.mjs` promotes the audited catalog into
`assets/villagelife/appearance/catalog.json` and
`textures/entity/person/parts/<asset-id>/<layer>.png`, normalizing every shipped layer to binary
alpha. Regional plains, taiga, snowy, desert, and savanna variants remain the next broader art
axis.
