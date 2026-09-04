# Genetics and entity attributes

**This is the decided design for per-villager variation, and it is implemented.** Every
person carries a small "stat block" in the D&D mold. The stats are the stored genetic
truth; Minecraft attribute modifiers are derived from them through a fixed projection
matrix. No two villagers are mechanically identical.

Code lives in `entities/genetics/`: `Stat` (the score enum), `StatBlock` (rolling and
NBT persistence), `GeneticCondition` (rare conditions), and `StatProjection` (the matrix;
its contribution tables are the source of truth that this doc mirrors in prose).
Integration is in `Person`: rolled in the constructor server-side, saved/loaded under the
`StatBlock` NBT key, and re-projected on every load so weight rebalances reach existing
villagers.

## The stat block

Per person:

| Field | Kind | Notes |
| --- | --- | --- |
| Strength (STR) | ability score | |
| Dexterity (DEX) | ability score | |
| Constitution (CON) | ability score | |
| Intelligence (INT) | ability score | |
| Wisdom (WIS) | ability score | |
| Charisma (CHA) | ability score | |
| Size | physical trait | Same numeric treatment as an ability score |
| Eyesight | physical trait | Same numeric treatment as an ability score |
| Personality / virtues | already exists | The five `Virtue` floats generating `Personality` |
| First name, last name | already exists | |
| Title | partial | Optional honorific slot ("the Brave"). A per-villager string now exists on `RealPerson` (`getTitle`/`setTitle`, persisted) and is surfaced on the name tag in place of the occupation when set (`getRoleLabel`). Nothing grants titles yet, so it is empty for everyone; the granting system is still TBD |

Ability scores use the D&D scale: integers, 10 is average, typical rolls land 8 to 14,
hard floor 3 and ceiling 18. The scale is the point: it is instantly legible to players,
compact to store and inherit, and every point off 10 converts to a small percentage in the
projection below.

## Stat inheritance

A child does not receive a fresh unrelated stat block. Each score starts from the midpoint of
both parents, then blends in a smaller fresh 3d6 population roll. `Size` uses an 80% parental
weight and every other score uses 70%. This keeps siblings visibly related while preserving
ordinary sibling variation and regression toward the population mean. Values remain clamped to
3-18.

The 80% Size weight is informed by the estimate that inherited DNA variation explains roughly
80% of human height variation, but it is deliberately a gameplay model rather than a literal
interpretation of population heritability. Heritability is a population statistic, not a promise
that a particular child's height is 80% predetermined. See
[MedlinePlus on height](https://medlineplus.gov/genetics/understanding/traits/height/) and
[MedlinePlus on heritability](https://medlineplus.gov/genetics/understanding/inheritance/heritability/).

Health, scale, speed, damage, and the other Minecraft attributes are never inherited separately.
They are recomputed from the inherited scores through the projection matrix. This prevents Size
from being inherited once as a score and again as a final scale modifier.

## Why a stat layer instead of per-attribute genes

One stat feeds several Minecraft attributes, and one Minecraft attribute is fed by several
stats. That many-to-many projection is deliberate:

- **Correlation is what makes it feel genetic.** A high-CON villager is tougher in several
  small ways at once (health, knockback, fire, breath), the way a "hardy" person would be.
  Independent per-attribute genes produce statistical noise, not character.
- **Legibility.** "STR 14, CON 8" tells a player (and the LLM, see below) who this person
  is. A list of twelve raw modifier percentages tells nobody anything.
- **Rebalancing is free.** Tuning how much CON matters edits matrix weights, never saved
  villagers.

## The projection matrix

Each Minecraft attribute's genetic modifier is the sum of its listed contributions, applied
as permanent modifiers with stable ids of the form `kithkyn:gene/<stat>` — one per
contributing STAT, so an attribute fed by two stats carries two modifiers. Most use
`ADD_MULTIPLIED_BASE`; knockback resistance, oxygen bonus and attack knockback use
`ADD_VALUE`, because a percentage of zero is zero. Starting weights: a **major**
contribution is 2% per
point off 10, a **minor** is 1% per point. All weights are tunables.

| Minecraft attribute | Major | Minor |
| --- | --- | --- |
| `MAX_HEALTH` | CON | |
| `KNOCKBACK_RESISTANCE` | Size | CON |
| `BURNING_TIME` (less is better) | | CON |
| `OXYGEN_BONUS` | | CON |
| `MOVEMENT_SPEED` | DEX | Size (inverse: bigger is slightly slower) |
| `ATTACK_DAMAGE` | STR | Size |
| `ATTACK_KNOCKBACK` | STR | |
| `ATTACK_SPEED` | | DEX |
| `SAFE_FALL_DISTANCE` | | DEX |
| `JUMP_STRENGTH` | | STR, Size (inverse) |
| `SCALE` | Size | |
| `FOLLOW_RANGE` | Eyesight | WIS |

`SCALE` is Size's headline output and the one gene players see at a glance: it resizes
hitbox, eye height, and render together. Keep its range tight, roughly +-8% at the
extremes, so villagers read as people, not gnomes and giants.

Notice both of the crossovers this matrix encodes: knockback resistance comes from being
tough (CON) *and* from being big (Size); attack damage comes from strength *and* mass.
When adding a new contribution, add it to this table first. The matrix is the single
source of truth for what feeds what, which is also what prevents accidental
double-stacking.

### Mental stats: INT, WIS, CHA

Vanilla has almost no mob-side hooks for these, and faking them through combat attributes
would be wrong. They project onto our own systems instead:

| Stat | Consumers |
| --- | --- |
| INT | Learning/skill growth (future), work speed on skilled crafts (custom `kithkyn:work_speed` attribute when a goal reads it) |
| WIS | Awareness: its one numeric projection is follow range. (`RunAwayGoal` does NOT read stats; its thresholds are hardcoded, so "flees earlier" is not implemented.) |
| CHA | Trading prices, gift reception, reputation and marriage systems |

They also all feed the **LLM persona**, through `PersonaPrompts` (there is no per-decision
situation prompt carrying stats; decisions the model makes today are building choices, and
they are village-level rather than personal). The old wording described `LlmService`
building a situation prompt per
decision, and the stat block belongs in it ("You are strong but slow-witted and very
charming..."). That makes INT/WIS/CHA behaviorally real through dialogue and choices even
before any numeric system consumes them.

Register a custom NeoForge attribute for one of these only when a goal actually reads it.
An attribute nothing consumes is dead weight (core guideline 1 applies to stats too).

## How vanilla attributes work (1.21.1), briefly

Every `LivingEntity` has an attribute map: base values set in `Person.createAttributes()`,
plus modifiers `(id, amount, operation)`. Operations: `ADD_VALUE`, `ADD_MULTIPLIED_BASE`
(percentage of base; what genes use), `ADD_MULTIPLIED_TOTAL`. Modifiers added with
`addPermanentModifier` persist in entity NBT; transient ones (like the existing
`USE_ITEM_SPEED_PENALTY`) do not. Everything in the matrix above except `ATTACK_KNOCKBACK`
and `ATTACK_SPEED` is already registered on `Person` via the living-entity defaults; those
two need an explicit `.add(...)` in `createAttributes()`.

Deliberately excluded from the matrix: `GRAVITY` (reads as a bug, not a trait),
`FALL_DAMAGE_MULTIPLIER`, `ARMOR`/`ARMOR_TOUGHNESS` (stacks confusingly with worn armor),
`LUCK` (vanilla mobs barely consume it). Player-only attributes (mining speed,
interaction ranges) do not exist on mobs.

Mental stats stay projection-light on purpose: INT and CHA currently project onto
nothing, and that is fine. They are not given fake numeric uses; they wait for the
systems that genuinely read them (dialogue, trading, the LLM persona). WIS earns its one
projection through the Pathfinder 2e precedent that Perception is WIS-based.

## Rare genetic conditions

Beyond the smooth stat curve, a person can carry at most one rare condition, rolled once
at generation (`GeneticCondition`). Mechanical conditions are fixed attribute adjustments
layered on top of the stat projection; visual conditions can instead alter the derived
appearance recipe.

| Condition | Founder chance | One affected parent | Two affected parents | Effects |
| --- | ---: | ---: | ---: | --- |
| Gigantism | 1% | 12.5% | 18.75% | Scale +20%, max health +20%, knockback resistance +0.2, speed -8% |
| Dwarfism | 1% | 50% | 66.7% of represented live births | Scale -15%, max health -10%, attack damage -10%, speed +5% |
| Heterochromia | 1% | 50% | 75% | No stat effect; left and right eyes use different inherited pigments and compatible source geometry |

The scale swings are deliberately modest: a giant reads as a tall adult (about six-fifths
height) and a dwarf as a short one (about four-fifths), not an ogre or a child. The other
effects carry the drama.

These percentages map the broad game labels onto explicit clinical models:

- The saved `DWARFISM` condition models achondroplasia because generic dwarfism has no single
  inheritance rule. Achondroplasia is autosomal dominant: one affected and one average-stature
  parent have a 50% affected-child chance. Two affected parents have 25% average stature, 50%
  achondroplasia, and 25% homozygous achondroplasia, which is life-limiting. The game does not
  simulate nonviable births, so its spawned-child pool conditions the represented outcomes to
  2/3 affected and 1/3 unaffected. See
  [GeneReviews](https://www.ncbi.nlm.nih.gov/books/NBK1152/).
- The saved `GIGANTISM` condition models an AIP-related familial pituitary predisposition rather
  than claiming all gigantism is directly inherited. Familial isolated pituitary adenoma is
  autosomal dominant but only 20-30% penetrant. The game uses the 25% midpoint, producing 12.5%
  expression from one affected parent and 18.75% from two. See
  [MedlinePlus Genetics](https://medlineplus.gov/genetics/condition/familial-isolated-pituitary-adenoma/).
- A parent's `HETEROCHROMIA` represents the congenital familial form, for which autosomal-dominant
  inheritance has been reported. One affected parent therefore gives 50% and two give 75%. The
  1% founder chance also covers the many sporadic and developmental cases. See
  [NCBI Bookshelf](https://www.ncbi.nlm.nih.gov/books/NBK574499/).

The condition name is persisted and synced. Heterochromia is consumed by the client appearance
recipe and included in persona descriptions; albinism remains a future visual condition. Unknown
condition names in old save data degrade to none. The save format intentionally retains at most
one condition per person. If separate inheritance rolls express more than one, one is selected
uniformly rather than stacking effects the current entity model cannot represent.

## Lifecycle

1. **Generation**: a first-generation villager rolls 3d6 per score (bell curve, 3-18,
   mean 10.5), then the 1%-per-condition founder roll.
2. **Projection**: scores project through the matrix into permanent attribute modifiers.
   Projection is idempotent: each modifier has a stable id (`kithkyn:gene/<stat>`)
   and is recomputed and replaced, never stacked. It reruns on every load.
3. **Inheritance**: `ChildCreationService` creates the child's StatBlock from both parents and
   immediately reprojects every Minecraft attribute. It separately recombines `AppearanceGenes`:
   skin pattern, hairstyle, eye structure, and alternate-eye structure are inherited independently
   from either parent. Skin, hair, eye, and alternate-eye color each carry two diploid loci
   (pigment depth plus warmth/hue); a child receives one allele per locus from each parent and
   expresses the midpoint. Virtues and their derived personality remain freshly randomized, as do
   gender and given name; the surname comes from the parents' household. Twins and triplets reuse
   one exact inherited stat block, appearance gene set, and appearance seed for the whole birth,
   while each child still receives an independently generated name and personality.

## Open questions (not yet decided)

<!-- Stat-based job matching: decided and implemented (JobAptitudes; see population-and-labor.md). -->

- Whether the stat block is surfaced to the player directly (inspection UI) or only hinted
  through dialogue, appearance, and behavior.
- Whether Size generation correlates weakly with CON/STR at roll time, or stays fully
  independent.

- The title system (reserved field only).
