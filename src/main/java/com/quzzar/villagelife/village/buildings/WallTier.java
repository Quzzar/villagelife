package com.quzzar.villagelife.village.buildings;

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

  WOOD(Blocks.OAK_LOG, Items.OAK_LOG, 3, false),
  STONE(Blocks.STONE_BRICKS, Items.STONE_BRICKS, 5, true);

  private final Block block;
  private final Item material;
  private final int height;
  private final boolean crenellated;

  WallTier(Block block, Item material, int height, boolean crenellated) {
    this.block = block;
    this.material = material;
    this.height = height;
    this.crenellated = crenellated;
  }

  /** The block a segment is built from. */
  public Block block() {
    return this.block;
  }

  /** The item a segment costs, one per block placed, pulled from village stores. */
  public Item material() {
    return this.material;
  }

  /** Solid courses raised above the ground at each column. */
  public int height() {
    return this.height;
  }

  /** Whether the top is crenellated (a merlon on alternate columns) rather than flat. */
  public boolean crenellated() {
    return this.crenellated;
  }

  /** The tier a wall of this one upgrades into, or null when it is already the top. */
  public WallTier next() {
    return this == WOOD ? STONE : null;
  }
}
