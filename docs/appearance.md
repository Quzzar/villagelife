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
  (below). This is where all the genetic variation lives.

### Model by gender

`MALE -> wide`, `FEMALE -> slim`, `NONBINARY -> seeded pick` (deterministic from the
appearance seed, so it is stable and unbiased). Vanilla ships only these two humanoid
geometries; a distinct female silhouette (chest, hips) does not exist in vanilla and would
be custom model work — deferred. Femininity in v1 is carried by the slim body plus the
skin texture, the same way vanilla does it.

Mechanically: register a second baked layer alongside `PERSON` in `PersonClientEvents`
built from `PlayerModel.createMesh(CubeDeformation.NONE, true)` (the `true` is the slim
flag; `PersonModel.createMesh` currently hardcodes `false`), keep both baked models on the
renderer, and set `this.model` from gender at the top of `render()`. Client render is
single-threaded, so the per-entity swap is safe — this is how vanilla serves Steve vs Alex.
The armor layer is unaffected: worn armor is always the wide humanoid armor model, exactly
as vanilla Alex wears wide armor.

## Composited skins, not curated whole skins

The complaint that player-model villagers look wrong is a *skin* problem, not a *model*
problem. Random Steve variants wandering a medieval village look like players. The fix is
content — but rather than hand-draw one whole skin per villager, the base skin is
**composited at runtime from a small set of interchangeable parts**, so a handful of
textures yields combinatorial variety and, later, genetic expression.

**This is a client-only pipeline.** A dedicated server has no GPU — `NativeImage` and
`DynamicTexture` are client classes — so pixels can only be baked on the client. Nothing
about a villager's appearance needs to live on the server: the whole look is derived from
data every client already has.

### The seam that makes it work

```
AppearanceInputs  ->  SkinRecipe  ->  baked DynamicTexture
```

- **`SkinRecipe`** is the flat list of "which part + which tint" per dimension. The
  compositor reads only this.
- **`AppearanceInputs`** fills the recipe. **Today** it is `{ seed, gender }` and every
  field is rolled from a seeded PRNG. **Later** it grows `{ seed, gender, appearanceGenes,
  condition, age }` and specific fields are read from genetics instead of rolled. The
  compositor never changes; genetics arrives one field at a time by swapping where a value
  comes from.

The seed is the existing `skinVariant` ([Person.java](../src/main/java/com/quzzar/villagelife/entities/Person.java),
`SKIN_INDEX_RANGE = 100_003`), reinterpreted from a pool index into an appearance seed fed
to a per-villager PRNG. It is already rolled at spawn, persisted, and synced, so v1 needs
no new save data and no new networking.

## v1 layer set

The parts composite bottom-to-top. A tint layer is authored grayscale and multiplied by a
color (infinite variety, cheap); a swap layer is one of N authored parts.

| # | Layer | Type | v1 |
| --- | --- | --- | --- |
| 1 | **Skin** (whole body incl. default face features) | tint | Grayscale body multiplied by **skin tone**. Bottom layer, covers everything. |
| 2 | **Clothing** (torso/arms/legs garb) | swap | One default peasant garb in v1 so nobody is bare. Occupation and regional dress extend this later. |
| 3 | **Eyes** (iris, left and right separable) | tint | Grayscale iris multiplied by **eye color**. Split L/R so heterochromia is later a pure data flag. |
| 4 | **Hair** | swap + tint | **Hairstyle** selects the part; grayscale hair multiplied by **hair color**. Top layer, overhangs the collar. |

There is no separate accessory dimension. A ribbon, flower, headband, hat, hood, or other
head adornment is part of its hairstyle. Finished skin textures do not retain a reliable
hair-versus-accessory boundary, and separating one creates ambiguous leftover pixels rather
than a useful genetic dimension.

Part sheets are complete independent renderings, not a disjoint partition of the visible
source pixels. Skin continues underneath clothing, and clothing continues underneath hair,
so removing or swapping an upper layer never reveals a transparent hole. Each eye is an
arbitrary per-skin texel mask rather than one fixed pixel or coordinate; left and right masks
may differ in size, shape, and face position. Hidden skin uses an authored two-tone pattern
across every base UV face rather than a flat bucket fill, then the source's exposed-skin detail
is restored above it.

So v1's four configurable dimensions are **skin tone**, **hairstyle**, **hair color**, and
**eye color**. Face shape is a single baked default in v1 (the "face" swap dimension,
facial hair, and skin marks are deferred). The tintable skin and hair layers are grayscale
so the multiply reads true; full-color layers (clothing) are drawn as-is.

### One part set, both models

A 64x64 skin renders on either model, exactly as a downloaded Minecraft skin works on
whichever model you pick. The two geometries differ only in arm width (slim 3px, wide 4px):
a part authored at one width shows at most a one-pixel column difference on the arm of the
other, which is why Mojang offers a Classic/Slim toggle for the same skin file. For skin
tone and simple garb that difference is imperceptible, so **v1 uses a single part set for
both models**, with no per-model art. Pixel-perfect arm columns per width are optional
polish, not a requirement.

## The recipe

v1 `SkinRecipe` fields:

| Field | Type | Source in v1 |
| --- | --- | --- |
| `model` | `WIDE` \| `SLIM` | gender (NB seeded) |
| `skinTone` | packed RGB | palette roll |
| `hairstyle` | index into the gender's hairstyle set | roll |
| `hairColor` | packed RGB | palette roll |
| `eyeColorL`, `eyeColorR` | packed RGB | palette roll (equal in v1) |

Derivation is one deterministic function `AppearanceInputs -> SkinRecipe`: seed a PRNG from
`skinVariant`, then roll each field, drawing tints from curated palettes (a natural
skin-tone ramp, a hair-color set, an eye-color set) so results read as people, not
confetti. Determinism is what makes every client agree without syncing the recipe.

## Genetics readiness

The derivation function is the only thing genetics touches — each `roll()` becomes a gene
read when the gene exists. Two rules keep it clean:

1. **Appearance genes are their own record, not part of `StatBlock`.** `StatBlock` is
   mechanical; it projects through the attribute matrix. Eye color and hairstyle project to
   nothing mechanical and would pollute it. A sibling `AppearanceGenes` inherits the same
   way virtues do (child roughly the parents' blend plus mutation) but stays cosmetic.
2. **Conditions become recipe overrides.** The reserved visual conditions in
   [genetics-and-attributes.md](genetics-and-attributes.md) map directly:
   *heterochromia* sets `eyeColorL != eyeColorR`; *albinism* forces a pale skin tone, white
   hair, and pink/red eyes. `Size` already shows through the `SCALE` attribute and the
   model, so appearance does not touch build.

## Assets and manifest

Parts live under `textures/entity/person/parts/<dimension>/`, all authored on the shared
64x64 UV. Tintable layers (skin, hair) are grayscale. Every part is a single 64x64 texture
that renders on both the wide and slim models the way a downloaded skin does, so there are
no per-model variants. A generated manifest (the `PersonSkins` pattern — a class listing the
available parts per dimension per gender, built by a script under `scratchpad/`) is the
selection table the derivation rolls against.

Gender compatibility belongs to each part, not only to the original whole skin. Every skin,
clothing, hairstyle, left eye, and right eye asset is tagged `MALE`, `FEMALE`, or
`NONBINARY`. `NONBINARY` is the shared compatibility pool: male recipes draw from `MALE +
NONBINARY`, female recipes draw from `FEMALE + NONBINARY`, and non-binary recipes may draw
from all three. Skin and eye parts are shared by default. Clothing and hairstyles are
classified independently and conservatively from their visible design; anything ambiguous
stays shared. These tags describe recipe compatibility, not biological traits or the gender
identity of the source character.

The existing curated whole-skin gender pools are replaced by this part set; per the
project's no-backwards-compat rule the world is wiped rather than migrated. The wandering
merchant keeps its curated whole-skin robe until the occupation layer lands, at which point
the robe becomes an occupation garb over a composited villager.

## The compositor and its cache

`getTextureLocation` stops returning a file and returns a composited texture instead:

- **Build** (client render thread): copy the base skin `NativeImage`, and for each layer
  either multiply (tint) or alpha-over (overlay) its pixels, then register the result as a
  `DynamicTexture` under a `ResourceLocation`. A 64x64 composite is a few thousand pixel
  ops — microseconds.
- **Cache** globally, keyed by a stable hash of the recipe's texture fields (**not**
  `model` — the same composited texture renders on both geometries). Identical recipes share
  one texture. Bound the
  cache with an LRU and `close()` evicted `DynamicTexture`s — they hold native memory, and
  that disposal is the one thing that bites if forgotten. Even hundreds of distinct
  villagers loaded at once is a few MB.

## Sync

v1 syncs nothing new: `skinVariant` (the seed) and `GENDER` are already synced entity data
and already read client-side in `PersonRenderer`. When genetics later drives appearance,
only the gene-derived fields that cannot be reproduced from the seed get synced — a compact
`AppearanceGenes` payload or a few accessors — never the baked pixels.

## The three conceptual layers, revisited

The earlier framing of base / regional dress / occupation still holds, re-expressed through
compositing:

| Layer | Now |
| --- | --- |
| **Base skin** | No longer one curated skin — composited from the genetic part layers above (skin tone, hair, eyes, face), rolled once as a recipe and persisted via the seed. |
| **Regional dress** | Per-region palettes and part-sets (matched to the village's variant family: plains, taiga, snowy, desert, savanna) rather than per-region whole-skin pools. Same axis as building variants. Future. |
| **Occupation** | The deferred **clothing** dimension as a swap layer — apron on the baker, hood on the hunter — churning with the job under the campfire model ([population-and-labor.md](population-and-labor.md)) without changing the person underneath. Future. |

## Current state and the gap

| | State |
| --- | --- |
| Model and renderer | Working. Single wide player model, armor layers, arm poses, custom eating animation. |
| Model by gender | Not built. One boolean plus a second baked layer and a render-time `this.model` swap. |
| Skin pool | Content-hash gender pools in `PersonSkins` (`MALE`/`FEMALE`/`NONBINARY`, plus curated merchant pools). Placeholder-quality whole skins, to be replaced by the part set. |
| Skin selection | Working as a pool index. `SKIN_VARIANT` is rolled in `Person.finalizeSpawn`, persisted, and wrapped into range on read. To be reinterpreted as an appearance seed. |
| Compositing pipeline | Not built. The `SkinRecipe` type, the seeded derivation, the part assets + manifest, and the `getTextureLocation` compositor/cache. |
| Regional dress, occupation | Not built. Follow-on layers over v1. |

The remaining v1 work is the compositing pipeline plus a starter part set: one grayscale
base body (wide + slim), a default clothing garb, a few hairstyles, an eye-iris overlay,
and the tint palettes.

## Deferred and open

- **Custom female geometry** (a real silhouette beyond wide/slim) — custom model work, its
  own UV; deferred in favor of wide/slim.
- **Higher-resolution parts** (128x128 for finer faces/eyes) — possible since this is our
  own entity texture, not a player skin; v1 stays 64x64 to reuse the existing art scale.
- **`AppearanceGenes`** exact fields and inheritance spread — designed with the family
  system, alongside the `StatBlock` inheritance in [genetics-and-attributes.md](genetics-and-attributes.md).
- **Face, facial hair, and skin marks** as swap/overlay dimensions — after v1.
