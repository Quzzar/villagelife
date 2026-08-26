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

## What "villager skins, not Steve skins" means

The complaint that player-model villagers look wrong is a *skin* problem, not a *model*
problem. Random Steve variants wandering a medieval village look like players. The fix is
content, in three layers:

| Layer | Carries | Source |
| --- | --- | --- |
| **Base skin** | Face, hair, body, peasant clothing | A curated skin pool, rolled once per person at spawn |
| **Regional dress** | Which pool the skin is rolled from, matched to the village's variant family (plains, taiga, snowy, desert, savanna) | The village's biome, same axis as building variants |
| **Occupation** | Apron on the baker, hood on the hunter, coif on the smith | A render layer over the base skin, the way armor already layers |

Base skin is rolled once and persists. Occupation is a layer precisely because jobs churn
under the campfire model ([population-and-labor.md](population-and-labor.md)): a person who
loses their job stops wearing the apron without becoming a different-looking person.

## Current state and the gap

| | State |
| --- | --- |
| Model and renderer | Working. Player model, armor layers, arm poses, custom eating animation. |
| Skin pool | 7 skins at `textures/entity/person/person_0..6.png`. Placeholder art: these are Steve variants, not peasants. |
| Skin selection | Working. `SKIN_VARIANT` is rolled in `Person.finalizeSpawn`, persisted as `"SkinVariant"`, and wrapped into range on read so a shrunken pool cannot point at a missing texture. A person saved without the key is rolled a face on load rather than defaulting to skin 0. |
| Regional dress | Not built. Needs the variant families from [buildings.md](buildings.md) and a pool per family. |
| Occupation layer | Not built. Should layer over the base skin the way `HumanoidArmorLayer` does. |

The remaining work is content: a curated peasant skin pool per variant family, and
occupation overlays. The selection machinery is done and will pick up new skins by raising
`Person.SKIN_VARIANT_COUNT`.
