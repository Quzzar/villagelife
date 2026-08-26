package com.quzzar.villagelife.entities.genetics;

import java.util.List;
import java.util.Locale;

import com.quzzar.villagelife.Villagelife;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Projects a {@link StatBlock} onto Minecraft attribute modifiers. The
 * contribution tables below are the single source of truth for what feeds
 * what; docs/genetics-and-attributes.md mirrors them in prose. To add or
 * rebalance an effect, edit the table, never call sites.
 *
 * Application is idempotent: every modifier has a stable id derived from its
 * source stat, and each apply removes the previous modifier before adding the
 * recomputed one. Saved entities therefore rebalance automatically when
 * weights change; only the scores in the {@link StatBlock} persist.
 */
public final class StatProjection {

  /** One stat's influence on one attribute: amount = perPoint * (score - 10). */
  private record Contribution(Holder<Attribute> attribute, Stat stat, Operation operation, double perPoint) {
  }

  /** A genetic condition's fixed adjustment to one attribute. */
  private record ConditionEffect(GeneticCondition condition, Holder<Attribute> attribute, Operation operation,
      double amount) {
  }

  // Weight conventions: MAJOR (0.02/point) and MINOR (0.01/point) for
  // percentage-of-base contributions. Attributes whose base value is zero
  // (knockback resistance, attack knockback, oxygen bonus) use ADD_VALUE with
  // absolute per-point amounts instead, since a percentage of zero is nothing.
  private static final double MAJOR = 0.02D;
  private static final double MINOR = 0.01D;

  private static final List<Contribution> MATRIX = List.of(
      new Contribution(Attributes.MAX_HEALTH, Stat.CONSTITUTION, Operation.ADD_MULTIPLIED_BASE, MAJOR),
      new Contribution(Attributes.KNOCKBACK_RESISTANCE, Stat.SIZE, Operation.ADD_VALUE, 0.02D),
      new Contribution(Attributes.KNOCKBACK_RESISTANCE, Stat.CONSTITUTION, Operation.ADD_VALUE, 0.01D),
      // Burning time: lower is better, so constitution reduces it.
      new Contribution(Attributes.BURNING_TIME, Stat.CONSTITUTION, Operation.ADD_MULTIPLIED_BASE, -MINOR),
      new Contribution(Attributes.OXYGEN_BONUS, Stat.CONSTITUTION, Operation.ADD_VALUE, 0.05D),
      new Contribution(Attributes.MOVEMENT_SPEED, Stat.DEXTERITY, Operation.ADD_MULTIPLIED_BASE, MAJOR),
      // Bigger is slightly slower, smaller slightly quicker.
      new Contribution(Attributes.MOVEMENT_SPEED, Stat.SIZE, Operation.ADD_MULTIPLIED_BASE, -MINOR),
      new Contribution(Attributes.ATTACK_DAMAGE, Stat.STRENGTH, Operation.ADD_MULTIPLIED_BASE, MAJOR),
      new Contribution(Attributes.ATTACK_DAMAGE, Stat.SIZE, Operation.ADD_MULTIPLIED_BASE, MINOR),
      new Contribution(Attributes.ATTACK_KNOCKBACK, Stat.STRENGTH, Operation.ADD_VALUE, 0.02D),
      new Contribution(Attributes.ATTACK_SPEED, Stat.DEXTERITY, Operation.ADD_MULTIPLIED_BASE, MINOR),
      new Contribution(Attributes.SAFE_FALL_DISTANCE, Stat.DEXTERITY, Operation.ADD_MULTIPLIED_BASE, MINOR),
      new Contribution(Attributes.JUMP_STRENGTH, Stat.STRENGTH, Operation.ADD_MULTIPLIED_BASE, MINOR),
      new Contribution(Attributes.JUMP_STRENGTH, Stat.SIZE, Operation.ADD_MULTIPLIED_BASE, -MINOR),
      // Scale gets a gentler weight than other majors so villagers stay
      // people-shaped: +-8% across the full 3-18 range.
      new Contribution(Attributes.SCALE, Stat.SIZE, Operation.ADD_MULTIPLIED_BASE, 0.01D),
      new Contribution(Attributes.FOLLOW_RANGE, Stat.EYESIGHT, Operation.ADD_MULTIPLIED_BASE, MAJOR),
      // PF2e maps Perception to WIS; eyesight is the organ, wisdom the awareness.
      new Contribution(Attributes.FOLLOW_RANGE, Stat.WISDOM, Operation.ADD_MULTIPLIED_BASE, MINOR));

  private static final List<ConditionEffect> CONDITION_EFFECTS = List.of(
      new ConditionEffect(GeneticCondition.GIGANTISM, Attributes.SCALE, Operation.ADD_MULTIPLIED_BASE, 0.45D),
      new ConditionEffect(GeneticCondition.GIGANTISM, Attributes.MAX_HEALTH, Operation.ADD_MULTIPLIED_BASE, 0.20D),
      new ConditionEffect(GeneticCondition.GIGANTISM, Attributes.KNOCKBACK_RESISTANCE, Operation.ADD_VALUE, 0.20D),
      new ConditionEffect(GeneticCondition.GIGANTISM, Attributes.MOVEMENT_SPEED, Operation.ADD_MULTIPLIED_BASE, -0.08D),
      new ConditionEffect(GeneticCondition.DWARFISM, Attributes.SCALE, Operation.ADD_MULTIPLIED_BASE, -0.35D),
      new ConditionEffect(GeneticCondition.DWARFISM, Attributes.MAX_HEALTH, Operation.ADD_MULTIPLIED_BASE, -0.10D),
      new ConditionEffect(GeneticCondition.DWARFISM, Attributes.ATTACK_DAMAGE, Operation.ADD_MULTIPLIED_BASE, -0.10D),
      new ConditionEffect(GeneticCondition.DWARFISM, Attributes.MOVEMENT_SPEED, Operation.ADD_MULTIPLIED_BASE, 0.05D));

  private StatProjection() {
  }

  /**
   * Applies (or re-applies) the full projection of the given stat block onto
   * the entity's attributes as permanent modifiers. Attributes the entity does
   * not have are skipped, so the matrix can reference attributes some entity
   * types lack.
   */
  public static void apply(LivingEntity entity, StatBlock statBlock) {
    for (Contribution contribution : MATRIX) {
      AttributeInstance instance = entity.getAttribute(contribution.attribute());
      if (instance == null) {
        continue;
      }
      ResourceLocation id = geneId(contribution.stat());
      instance.removeModifier(id);
      double amount = contribution.perPoint() * (statBlock.get(contribution.stat()) - Stat.AVERAGE);
      if (amount != 0.0D) {
        instance.addPermanentModifier(new AttributeModifier(id, amount, contribution.operation()));
      }
    }

    ResourceLocation conditionId = ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "gene/condition");
    for (ConditionEffect effect : CONDITION_EFFECTS) {
      AttributeInstance instance = entity.getAttribute(effect.attribute());
      if (instance == null) {
        continue;
      }
      // Always clear before conditionally re-adding, so a changed or removed
      // condition never leaves stale modifiers behind.
      instance.removeModifier(conditionId);
    }
    for (ConditionEffect effect : CONDITION_EFFECTS) {
      if (effect.condition() != statBlock.getCondition()) {
        continue;
      }
      AttributeInstance instance = entity.getAttribute(effect.attribute());
      if (instance == null) {
        continue;
      }
      instance.addPermanentModifier(new AttributeModifier(conditionId, effect.amount(), effect.operation()));
    }
  }

  private static ResourceLocation geneId(Stat stat) {
    return ResourceLocation.fromNamespaceAndPath(Villagelife.MODID,
        "gene/" + stat.name().toLowerCase(Locale.ROOT));
  }
}
