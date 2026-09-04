package com.quzzar.kithkyn.village.buildings;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Exact, saved proposal: reloading never recomputes victims, prices, or salvage. */
public record RedevelopmentPlan(UUID id, String target, ConstructionMode mode, Optional<UUID> source,
    long ground, Rotation rotation, int targetFingerprint, List<Building> removed, List<RemovalBlock> blocks,
    List<MaterialAmount> required, List<MaterialAmount> salvage, List<Long> prepBreak,
    List<Long> prepFill) {

  public static final Codec<RedevelopmentPlan> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      UUIDUtil.CODEC.fieldOf("id").forGetter(RedevelopmentPlan::id),
      Codec.STRING.fieldOf("target").forGetter(RedevelopmentPlan::target),
      com.quzzar.kithkyn.utils.KithkynCodecs.forEnum(ConstructionMode.class)
          .fieldOf("mode").forGetter(RedevelopmentPlan::mode),
      UUIDUtil.CODEC.optionalFieldOf("source").forGetter(RedevelopmentPlan::source),
      Codec.LONG.fieldOf("ground").forGetter(RedevelopmentPlan::ground),
      Rotation.CODEC.fieldOf("rotation").forGetter(RedevelopmentPlan::rotation),
      Codec.INT.fieldOf("target_fingerprint").forGetter(RedevelopmentPlan::targetFingerprint),
      Building.CODEC.listOf().fieldOf("removed").forGetter(RedevelopmentPlan::removed),
      RemovalBlock.CODEC.listOf().fieldOf("blocks").forGetter(RedevelopmentPlan::blocks),
      MaterialAmount.CODEC.listOf().fieldOf("required").forGetter(RedevelopmentPlan::required),
      MaterialAmount.CODEC.listOf().fieldOf("salvage").forGetter(RedevelopmentPlan::salvage),
      Codec.LONG.listOf().fieldOf("prep_break").forGetter(RedevelopmentPlan::prepBreak),
      Codec.LONG.listOf().fieldOf("prep_fill").forGetter(RedevelopmentPlan::prepFill)
  ).apply(instance, RedevelopmentPlan::new));

  public RedevelopmentPlan {
    removed = List.copyOf(removed);
    blocks = List.copyOf(blocks);
    required = List.copyOf(required);
    salvage = List.copyOf(salvage);
    prepBreak = List.copyOf(prepBreak);
    prepFill = List.copyOf(prepFill);
  }

  /** Expected block identity catches edits while the model or builder is busy. */
  public record RemovalBlock(long position, BlockState state) {
    public static final Codec<RemovalBlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.LONG.fieldOf("position").forGetter(RemovalBlock::position),
        BlockState.CODEC.fieldOf("state").forGetter(RemovalBlock::state)
    ).apply(instance, RemovalBlock::new));
  }

  /** What builders gather before demolition; anticipated salvage stays inside this project. */
  public List<MaterialAmount> netRequired() {
    return MaterialAmount.fromStacks(Materials.shortfall(MaterialAmount.tally(salvage),
        MaterialAmount.stacks(required)));
  }
}
