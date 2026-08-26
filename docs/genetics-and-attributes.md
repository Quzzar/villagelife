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
| Title | future, TBD | Optional honorific slot ("the Brave"); reserved, not designed |

Ability scores use the D&D scale: integers, 10 is average, typical rolls land 8 to 14,
hard floor 3 and ceiling 18. The scale is the point: it is instantly legible to players,
compact to store and inherit, and every point off 10 converts to a small percentage in the
projection below.

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
as permanent modifiers with stable ids of the form `villagelife:gene/<stat>` — one per
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
| INT | Learning/skill growth (future), work speed on skilled crafts (custom `villagelife:work_speed` attribute when a goal reads it) |
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
at generation (`GeneticCondition`). Conditions are fixed attribute adjustments layered on
top of the stat projection, so they stack with the rolled Size score: a high-Size giant is
the tallest thing in the village.

| Condition | Chance | Effects |
| --- | --- | --- |
| Gigantism | 0.5% | Scale +45%, max health +20%, knockback resistance +0.2, speed -8% |
| Dwarfism | 0.5% | Scale -35%, max health -10%, attack damage -10%, speed +5% |

The enum is the extension point: future conditions (visual ones like albinism or
heterochromia, temperament ones) are new entries plus their effect rows, nothing more.
Unknown condition names in old save data degrade to none.

## Lifecycle

1. **Generation**: a first-generation villager rolls 3d6 per score (bell curve, 3-18,
   mean 10.5), then the rare-condition roll.
2. **Projection**: scores project through the matrix into permanent attribute modifiers.
   Projection is idempotent: each modifier has a stable id (`villagelife:gene/<stat>`)
   and is recomputed and replaced, never stacked. It reruns on every load.
3. **Inheritance** (future, with the family system): children roll each score near the
   parents' average with a small mutation spread; conditions become heritable with a
   boosted chance when a parent carries one. Virtues inherit the same way. Scores stay
   the only thing inherited; modifiers are always recomputed.

## Open questions (not yet decided)

<!-- Stat-based job matching: decided and implemented (JobAptitudes; see population-and-labor.md). -->

- Whether the stat block is surfaced to the player directly (inspection UI) or only hinted
  through dialogue, appearance, and behavior.
- Whether Size generation correlates weakly with CON/STR at roll time, or stays fully
  independent.

- The title system (reserved field only).
