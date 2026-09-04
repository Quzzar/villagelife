package com.quzzar.kithkyn.entities.ai.goals.work;

import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.entities.Person;
import com.quzzar.kithkyn.entities.RealPerson;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.Vec3;

/**
 * The cleric's round: an APPLY step whose target is a person rather than a
 * place, and whose act is thrown rather than touched.
 *
 * The first step that is not {@link BlockWorkStep}, and the reason
 * {@link WorkStep} asks where a target IS rather than being handed a position.
 * A hurt villager walks about while being treated, so the cleric follows them.
 *
 * Reach here is throwing distance, not arm's length: the cleric closes to
 * within three quarters of their range, crouches to steady the throw, waits out
 * a charge that is longer the further away the patient is, and lobs a splash
 * potion - healing if the patient is nearly dead, regeneration otherwise.
 */
public final class HealStep implements WorkStep<LivingEntity> {

  private static final double SEARCH_RANGE = 10.0D;

  private final int minChargeTicks;
  private final int maxChargeTicks;
  private final float throwRange;

  private boolean charging;
  private int chargeLeft;

  public HealStep(int minChargeTicks, int maxChargeTicks, float throwRange) {
    this.minChargeTicks = minChargeTicks;
    this.maxChargeTicks = maxChargeTicks;
    this.throwRange = throwRange;
  }

  @Override
  @Nullable
  public LivingEntity select(RealPerson person) {
    if (person.isSleeping()) {
      return null;
    }
    List<LivingEntity> nearby = person.level().getEntitiesOfClass(LivingEntity.class,
        person.getBoundingBox().inflate(SEARCH_RANGE, 3.0D, SEARCH_RANGE));
    for (LivingEntity candidate : nearby) {
      if (candidate != null && !candidate.hasEffect(MobEffects.REGENERATION) && needsTending(person, candidate)) {
        return candidate;
      }
    }
    return null;
  }

  @Override
  public BlockPos positionOf(LivingEntity target) {
    return target.blockPosition();
  }

  @Override
  public boolean act(RealPerson person, LivingEntity target) {
    if (!needsTending(person, target)) {
      return false; // mended, or dead, or somebody else got there
    }
    // A potion thrown at a wall helps nobody. Hold the charge and close up.
    if (!person.getSensing().hasLineOfSight(target)) {
      this.charging = false;
      person.setPose(Pose.STANDING);
      person.getNavigation().moveTo(target, 0.5D);
      return true;
    }
    if (!this.charging) {
      this.charging = true;
      person.setPose(Pose.CROUCHING);
      this.chargeLeft = (int) Mth.lerp(person.distanceTo(target) / this.throwRange,
          this.minChargeTicks, this.maxChargeTicks);
      return true;
    }
    if (this.chargeLeft-- > 0) {
      return true;
    }
    throwPotion(person, target);
    this.charging = false;
    person.setPose(Pose.STANDING);
    return false;
  }

  @Override
  public void released(RealPerson person, LivingEntity target) {
    this.charging = false;
    this.chargeLeft = 0;
    person.setPose(Pose.STANDING);
  }

  @Override
  public String describe() {
    return "anyone who needs tending";
  }

  @Override
  public String activity() {
    return "tending the hurt";
  }

  /** Close enough to throw, not close enough to touch. */
  @Override
  public double reachSqr(RealPerson person) {
    double range = this.throwRange * 0.75D;
    return range * range;
  }

  /** The charge is counted in seconds, as it was. */
  @Override
  public int actEveryTicks() {
    return 20;
  }

  private boolean needsTending(RealPerson person, LivingEntity candidate) {
    if (candidate == person || !candidate.isAlive()
        || candidate.getHealth() >= candidate.getMaxHealth()) {
      return false;
    }
    if (candidate instanceof Person) {
      return true;
    }
    return candidate instanceof Player player && !player.getAbilities().instabuild;
  }

  private void throwPotion(RealPerson healer, LivingEntity target) {
    Vec3 drift = target.getDeltaMovement();
    double dx = target.getX() + drift.x - healer.getX();
    double dy = target.getEyeY() - 1.1F - healer.getY();
    double dz = target.getZ() + drift.z - healer.getZ();
    float flat = Mth.sqrt((float) (dx * dx + dz * dz));

    Holder<Potion> potion = target.getHealth() <= 4.0F ? Potions.HEALING : Potions.REGENERATION;
    ItemStack bottle = new ItemStack(Items.SPLASH_POTION);
    bottle.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));

    ThrownPotion thrown = new ThrownPotion(healer.level(), healer);
    thrown.setItem(bottle);
    thrown.setXRot(-20.0F);
    thrown.shoot(dx, dy + flat * 0.2F, dz, 0.75F, 8.0F);
    healer.level().playSound((Player) null, healer.getX(), healer.getY(), healer.getZ(),
        SoundEvents.SPLASH_POTION_THROW, healer.getSoundSource(), 1.0F,
        0.8F + healer.getRandom().nextFloat() * 0.4F);
    healer.level().addFreshEntity(thrown);
  }

}
