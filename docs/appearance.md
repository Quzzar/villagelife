# Villager appearance

**Decided: villagers use the player model, not the vanilla villager model.** They are
people, and they look like people.

## The decision

`PersonModel` extends `PlayerModel<Person>` on a 64x64 player skin sheet, rendered by
`PersonRenderer` (a `HumanoidMobRenderer`) with the vanilla player inner and outer armor
layers, at a base scale of `0.9375`.

The vanilla villager model was considered and rejected. Three reasons:

1. **Equipment has to be visible.** Guards already carry a full armor set and a weapon
   (`PersonLootTables` covers helmet, chest, legs, feet, main hand, off hand) and have an
   inventory GUI. Vanilla villagers have no armor layer and do not render held items, so
   that model means rebuilding equipment rendering from scratch or shipping soldiers who
   look unarmed.
2. **The thesis is people.** Personas ([personas.md](personas.md)), names, gender,
   marriage, virtues, and relationships read as a person. On the vanilla villager face
   they read as a joke.
3. **Genetics need a body to show on.** `SCALE` from Size, plus the gigantism and dwarfism
   conditions ([genetics-and-attributes.md](genetics-and-attributes.md)), are visible
   variation on a humanoid silhouette and invisible on a villager one.

**Overhauling villages is achieved by replacing the buildings, not by copying the villager
model.** See [buildings.md](buildings.md). A village can be entirely rebuilt and still have
people living in it.

## Two axes: model and skin

A villager's look is two independent choices, both keyed off `getGender()`:

- **Model geometry** — the wide (Steve, 4px arms) or slim (Alex, 3px arms) `PlayerModel`.
  This is the *body shape*, chosen at render time. It is not painted into the texture.
- **Skin texture** — the pixels on that body, composited at runtime from layered parts
  (below). Inherited identity and replaceable occupation clothing meet here.

### Model by gender

`MALE -> wide`, `FEMALE -> slim`. A non-binary person deterministically chooses one coherent
shared, masculine, or feminine part expression; a shared expression makes a stable wide/slim
choice from its skin gene. Vanilla ships only these two humanoid geometries; a distinct female
silhouette (chest, hips) does not exist in vanilla and would be custom model work — deferred.
Femininity in v1 is carried by the slim body plus the skin texture, the same way vanilla does it.

Mechanically, `PersonClientEvents` registers wide and slim baked layers and
`PersonRenderer` selects the model from the same recipe inputs used by the texture
compositor. The armor layer is unaffected: worn armor uses the vanilla humanoid armor
models, exactly as it does for player skins.

## Composited skins, not curated whole skins

The complaint that player-model villagers look wrong is a *skin* problem, not a *model*
problem. Random Steve variants wandering a medieval village look like players. The fix is
content — but rather than hand-draw one whole skin per villager, the base skin is
**composited at runtime from a small set of interchangeable parts**, so a handful of
textures yields combinatorial variety and, later, genetic expression.

**Pixel baking is client-only; identity is authoritative entity data.** A dedicated server
has no GPU — `NativeImage` and `DynamicTexture` are client classes — so the 64x64 result is
baked on each client. The server persists and syncs the compact facts needed to derive it:
appearance seed, four structural selectors, four packed pigment genes, gender, genetic
condition, occupation, and child/adult state. Pixels are never sent over the network.

### The seam that makes it work

```
AppearanceInputs  ->  SkinRecipe  ->  baked DynamicTexture
```

- **`SkinRecipe`** is the flat list of selected semantic layer ids, rendered model, gender
  expression, and the garment's hair-occlusion flag. The compositor reads only this.
- **`AppearanceInputs`** fills the recipe from `{ seed, appearanceGenes, gender,
  occupation, lifeStage, condition }`. Genetics selects the person's stable parts;
  occupation and life stage select clothing independently.

The stable seed retains the old `SkinVariant` NBT key for existing saves, but it is no longer
a whole-skin pool index. `AppearanceGenes` is saved separately; people from older saves derive
the structural selectors and pigment genes deterministically from their legacy data.

## v1 layer set

The parts composite bottom-to-top on exact 64x64 PNGs with binary alpha. Structure and detail
remain authored pixels. A calibrated list on each skin, hair, and eye layer marks only its
pigment texels; the compositor substitutes those exact colors with an inherited base tone and
one inherited shadow tone. Clothing, eye whites, outlines, mouths, ribbons, and other details
are never blanket-tinted. Output remains flat, hard-edged pixel art with no filtering,
interpolation, or gradients.

| # | Layer | Type | v1 |
| --- | --- | --- | --- |
| 1 | **Skin** (whole body and face underlayer) | swap | Complete authored skin under clothing and hair. Bottom layer, covers the full player UV. |
| 2 | **Clothing** (garb and occupation headwear) | swap | Adult occupation wardrobes or child commonwear, selected independently from inherited appearance. |
| 3 | **Eyes** (left and right separable) | swap | Arbitrary authored eye masks. Both sides normally use one source; heterochromia chooses a compatible second source for the right eye. |
| 4 | **Hair** | swap | Complete authored hairstyle. Top layer, except where explicit clothing headwear occludes it. |

There is no separate accessory dimension. A personal ribbon, flower, or headband remains part
of its hairstyle because finished skin textures do not retain a reliable accessory boundary.
Occupation-bound headwear belongs to clothing instead. It may carry an explicit occlusion mask
so a hood covers inherited hair without moving that hood into the person's genetics.

Part sheets are complete independent renderings, not a disjoint partition of the visible
source pixels. Skin continues underneath clothing, and clothing continues underneath hair,
so removing or swapping an upper layer never reveals a transparent hole. Each eye is an
arbitrary per-skin texel mask rather than one fixed pixel or coordinate; left and right masks
may differ in size, shape, and face position. Hidden skin uses an authored two-tone pattern
across every base UV face rather than a flat bucket fill, then the source's exposed-skin detail
is restored above it.

Skin, hairstyle, and eye parts also carry a **face profile**. The profile records the eye rows
the face was authored around, while each hairstyle records the exact front-face texels it
occludes. Recipe selection chooses a skin profile first, limits hair and eyes to that profile,
and rejects any eye mask the chosen hairstyle would cover. Clothing has no face profile and
remains freely interchangeable. Newly authored parts should use one canonical profile so their
full cross-product stays valid; retained source parts may use their original profile without
forcing every face onto one geometry.

The independently inherited structural dimensions are **skin pattern**, **hairstyle**,
**eyes**, and an **alternate eye source** used only by heterochromia. Skin tone, hair color,
eye color, and alternate-eye color are separate continuous pigment dimensions. Clothing is
configurable but not genetic. Face shape, facial hair, and skin marks are not separate
dimensions yet; details that survive extraction remain in the nearest semantic layer.

### One part set, both models

A 64x64 player sheet can be presented on either geometry, but the base skin underlayer is
selected for the recipe's exact source model so its arm texels remain correct. Garments are
deliberately reusable across wide and slim models, matching both Minecraft's UV convention
and the combinations validated in the Skin Splice Lab. Hair and eyes are governed by face
compatibility rather than arm geometry.

## The recipe

v1 `SkinRecipe` fields:

| Field | Type | Source in v1 |
| --- | --- | --- |
| `model` | `WIDE` \| `SLIM` | gender (NB seeded) |
| `expression` | `MALE` \| `FEMALE` \| `NONBINARY` | gender; one coherent seeded expression for NB |
| `skin` | asset id | skin gene |
| `clothing` | asset id | occupation or child pool + stable seed |
| `leftEye`, `rightEye` | asset ids | eye genes + condition |
| `hair` | asset id | hair gene |
| `skinPigment` | two RGB shades | diploid skin-pigment gene |
| `hairPigment` | two RGB shades | diploid hair-pigment gene |
| `leftEyePigment`, `rightEyePigment` | two RGB shades each | eye-pigment genes + condition |
| `headwearOccludesHair` | boolean | selected garment metadata |

Derivation is one deterministic function `AppearanceInputs -> SkinRecipe`. It uses stable
rendezvous hashing rather than list indices, so adding an unrelated asset causes minimal
recipe churn. It selects a model-compatible skin, then same-profile hair and eye masks that
do not collide. Clothing is selected separately from current occupation and life stage.
Determinism makes every client agree without syncing the full recipe.

## Genetics readiness

The derivation function is the only thing genetics touches. Two rules keep it clean:

1. **Appearance genes are their own record, not part of `StatBlock`.** `StatBlock` is
   mechanical; it projects through the attribute matrix. Eye color and hairstyle project to
   nothing mechanical and would pollute it. The sibling `AppearanceGenes` record stores four
   structural selectors plus four packed pigment genes. A structural selector comes from one
   parent, with a 1-in-32 mutation chance. Each pigment gene contains two diploid loci: depth
   and warmth/hue. A child takes one allele per locus from each parent, with a bounded 1-in-64
   allele mutation, and expresses their midpoint. This allows light and dark parents to have
   a medium-toned child without interpolating the texture geometry.
2. **Conditions become recipe overrides.** The reserved visual conditions in
   [genetics-and-attributes.md](genetics-and-attributes.md) map directly:
   *heterochromia* selects a different right-eye source with the same normalized geometry and
   expresses the alternate eye-pigment gene; no mismatched eye shapes are possible. If two
   inherited iris colors round too closely, the palette projection chooses the most distant
   natural anchor so the rare condition remains visible. Albinism remains a future
   palette/content condition. `Size` already shows through the `SCALE` attribute and model,
   so appearance does not touch build.

## Assets and manifest

Runtime parts live under `textures/entity/person/parts/<asset-id>/<layer>.png`. The data-driven
selection table is `assets/villagelife/appearance/catalog.json`; no Java registry changes are
needed when the catalog grows. `scripts/export-appearance-assets.mjs` reproducibly exports
the audited Skin Splice Lab catalog, normalizes every layer to exact binary alpha, and refuses
non-64x64 inputs.

Gender compatibility belongs to each part, not only to the original whole skin. Every skin,
clothing, hairstyle, left eye, and right eye asset is tagged `MALE`, `FEMALE`, or
`NONBINARY`. `NONBINARY` is the shared compatibility pool: male recipes draw from `MALE +
NONBINARY`, female recipes draw from `FEMALE + NONBINARY`, and a non-binary person first
chooses one coherent shared, masculine, or feminine expression. One recipe may never contain
both a definitively `MALE` and a definitively `FEMALE` part; `NONBINARY` parts can accompany
either expression. Skin and eye parts are
shared by default. Clothing and hairstyles are classified independently and conservatively
from their visible design; anything ambiguous stays shared. These tags describe recipe
compatibility, not biological traits or the gender identity of the source character.

Clothing also carries a life-stage category. Adult garments may name one or more compatible
occupations. Child garments name no occupation and live in a separate commonwear pool. Toddler
and Kid always use that pool; an idle Teenager does too, while a Teenager with a real job uses
that occupation's clothing.

Face compatibility is a separate manifest concern. Skin, hair, and both eye parts must share
one face profile, and each selected eye must avoid the chosen hairstyle's front-face occlusion
mask. The generated selection table carries both fields so invalid combinations are excluded
before a recipe reaches the compositor.

Pigment compatibility is explicit manifest data as well. Every selectable skin, hair, and
left/right eye layer lists the exact source RGB colors that represent its biological pigment.
The exporter verifies those colors survive in the shipped layer after binary-alpha
normalization. This is what keeps a hair ribbon, beard clasp, mouth, sclera, or garment strap
out of the recoloring path even when its color happens to resemble nearby hair or skin.

The former curated whole-skin PNG pools and `PersonSkins` registry have been removed. The
`SkinVariant` NBT value is retained only as the stable appearance seed. Wandering merchants
use the single shared Trader Guy 3 clothing layer over an ordinary genetic appearance; the
hood's opaque head texels suppress inherited hair beneath it.

## The compositor and its cache

`getTextureLocation` stops returning a file and returns a composited texture instead:

- **Build** (client render thread): copy opaque texels in the order skin, clothing, left eye,
  right eye, hair; replace only manifest-calibrated pigment colors with the recipe's base or
  shadow shade; skip hair beneath an occluding garment's opaque head texels; register the exact
  result as a nearest-filtered `DynamicTexture`.
- **Cache** globally by the complete immutable `SkinRecipe`. The access-order cache is bounded
  at 128 textures, releases evicted native textures, and clears/releases all entries on a
  resource reload. A failed catalog or layer load falls back to the default player texture
  and logs once.

## Sync

`Person` syncs the appearance seed, four structural integer selectors, four packed pigment-gene
integers, condition name, four-way age stage, and its existing gender/occupation state. Each field is
persisted. The client converts those facts to a recipe and texture locally; baked pixels never
enter entity data or save data.

## Live development audit

When the developer-command config is enabled, `/vldev appearance` exercises the same synced
entity fields and pure recipe factory used by the renderer. The audit path never calls client
rendering classes, so it runs on an integrated or dedicated server.

| Command | Purpose |
| --- | --- |
| `/vldev appearance audit` | Background-audit the built-in catalog, all 208 packaged 64x64 PNG layers, and 2,112 deterministic recipes across every gender, occupation, life stage, and condition. |
| `/vldev appearance audit <targets>` | Validate live villagers, including recipe contracts, NBT round-trip stability, and agreement between stored and synced conditions. |
| `/vldev appearance show <target>` | Print mechanical stats plus the selected structures, garment, model, expression, raw pigment alleles, expressed percentages, and final two-shade RGB colors. |
| `/vldev appearance reroll <target>` | Roll a new appearance seed and founder appearance genes without changing occupation or condition. |
| `/vldev appearance inherit <child> <firstParent> <secondParent>` | Recombine both parents' mechanical and appearance genes onto the target, record its parentage, and make it a Toddler. |
| `/vldev appearance child <firstParent> <secondParent>` | Run `ChildCreationService` once to create a new Toddler near the parents with inherited mechanical stats, recombined appearance genes, and persistent parentage. Same-village parents register the child as a resident. Repeating the command produces distinct siblings. |
| `/vldev appearance pigment <target> <skin\|hair\|eyes\|alternate-eyes> <depth> <warmth>` | Set a homozygous 0–255 pigment pair for exact visual boundary testing. For eyes, the second value is hue. |
| `/vldev appearance condition <target> <none\|gigantism\|dwarfism\|heterochromia>` | Replace and persist the condition, then reapply visual and mechanical projections. |
| `/vldev appearance stage <target> <toddler\|kid\|teenager\|adult>` | Switch the physical/social stage immediately and restart that stage's growth clock. |
| `/vldev appearance occupation <target> <occupation>` | Change the live occupation and therefore clothing. This visual test override deliberately does not rewrite the village job ledger. |

For example, the nearest loaded person is
`@e[type=villagelife:person,sort=nearest,limit=1]`. Mutating commands persist on the target;
use them on a disposable development world. The read-only `audit` and `show` commands are safe
to run without changing people.

## The three conceptual layers, revisited

The earlier framing of base / regional dress / occupation still holds, re-expressed through
compositing:

| Layer | Now |
| --- | --- |
| **Base skin** | No longer one curated skin — composited from discrete inherited skin, hair, and eye structures plus continuous inherited pigment ranges. |
| **Regional dress** | Per-region palettes and part-sets (matched to the village's variant family: plains, taiga, snowy, desert, savanna) rather than per-region whole-skin pools. Same axis as building variants. Future. |
| **Occupation** | A **clothing** swap layer — apron on the baker, workwear on the miner — selected live from the current job under the campfire model ([population-and-labor.md](population-and-labor.md)) without changing the person underneath. |

## Current state and the gap

| | State |
| --- | --- |
| Model and renderer | Working. Wide and slim player models, armor layers, arm poses, custom eating animation. |
| Model by gender | Working. Male uses wide, female uses slim, and non-binary uses a stable seeded choice. |
| Appearance identity | Working. Structural selectors and diploid skin, hair, and eye pigment genes are rolled, persisted, synced, inherited independently, and upgraded deterministically from old saves. |
| Part assets and lab | Working. 65 catalog assets ship as 208 semantic PNG layers: 36 full packs plus 29 clothing-only wardrobes. Disabled source parts, including Stormy's clothing and all Trader Guy 3 non-clothing parts, do not ship. |
| Compositing pipeline | Working. A validated data catalog drives deterministic recipes and a bounded client texture cache with nearest-neighbor output. |
| Occupation clothing | Working. Every resident role meets the compatibility floor; a job change changes only clothing. Wandering merchant has exactly one shared, hood-aware Trader Guy 3 uniform. See [appearance-wardrobes.md](appearance-wardrobes.md). |
| Children | Working. Toddler, Kid, and Teenager use distinct stage multipliers over Minecraft's naturally smaller young-player model; those multipliers are relative values, not meter heights. Entity bounds and nameplates follow the resulting display height. Idle pre-adults use commonwear, while an employed Teenager uses occupation clothing. See [families.md](families.md). |
| Heterochromia | Working. A 1% genetic condition selects distinct but geometry-compatible left/right eye sources and visibly distinct inherited iris pigments; it is also described in persona prompts. |
| Regional dress | Not built. Future palette/part-set axis after occupational coverage. |

The appearance v1 integration is complete. Automated tests exhaust representative seeds across
every gender, occupation, wardrobe stage, and condition, then verify all shipped PNG dimensions and
binary alpha.

## Deferred and open

- **Custom female geometry** (a real silhouette beyond wide/slim) — custom model work, its
  own UV; deferred in favor of wide/slim.
- **Higher-resolution parts** (128x128 for finer faces/eyes) — possible since this is our
  own entity texture, not a player skin; v1 stays 64x64 to reuse the existing art scale.
- **Albinism** — add curated compatible assets or a deliberate palette transform before adding
  the condition; do not recolor the current full-color layers blindly.
- **Face, facial hair, and skin marks** as swap/overlay dimensions — after v1.
