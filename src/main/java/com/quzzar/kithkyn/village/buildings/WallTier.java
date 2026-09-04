package com.quzzar.kithkyn.village.buildings;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The two rungs of the wall ladder (docs/walls.md): a wooden palisade and a
 * stone brick wall. A tier is just the material a wall is raised from plus how
 * tall it stands, so a {@code WallStep} swaps one for the other on the same ring
 * and the stone wall is an in-place upgrade of the wood.
 */
public enum WallTier {

  WOOD(Blocks.OAK_LOG, Items.OAK_LOG, 3),
  STONE(Blocks.STONE_BRICKS, Items.STONE_BRICKS, 5);

  /**
   * Wall blocks one item of the material raises. A wall is village-scale work,
   * hundreds of segments round even a modest village, and at a block per log no
   * village ever afforded one: Wildflower Downs wanted 3700 logs and waited
   * forever. Aaron priced walls at a tenth instead (2026-09-02). The bill the
   * village checks and the draw the builder makes both use this rate.
   */
  public static final int BLOCKS_PER_ITEM = 10;

  private final Block block;
  private final Item material;
  private final int height;

  WallTier(Block block, Item material, int height) {
    this.block = block;
    this.material = material;
    this.height = height;
  }

  /** The block a segment is built from. */
  public Block block() {
    return this.block;
  }

  /** The item a segment costs at {@link #BLOCKS_PER_ITEM}, pulled from village stores. */
  public Item material() {
    return this.material;
  }

  /** Solid courses raised above the ground at each column. */
  public int height() {
    return this.height;
  }

  /** The tier a wall of this one upgrades into, or null when it is already the top. */
  public WallTier next() {
    return this == WOOD ? STONE : null;
  }
}
