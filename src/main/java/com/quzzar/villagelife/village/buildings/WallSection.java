package com.quzzar.villagelife.village.buildings;

import java.util.List;

/** One independently claimable and persistently resumable piece of a wall. */
public final class WallSection {

  private final WallSectionKind kind;
  private final List<WallBlockPlan> blocks;
  private final long signature;
  private int cursor;

  public WallSection(WallSectionKind kind, List<WallBlockPlan> blocks) {
    this.kind = kind;
    this.blocks = List.copyOf(blocks);
    this.signature = signature(kind, blocks);
  }

  public WallSectionKind kind() {
    return this.kind;
  }

  public List<WallBlockPlan> blocks() {
    return this.blocks;
  }

  public int cursor() {
    return this.cursor;
  }

  public long signature() {
    return this.signature;
  }

  public boolean isComplete() {
    return this.cursor >= this.blocks.size();
  }

  public WallBlockPlan next() {
    return this.blocks.get(this.cursor);
  }

  void advance() {
    if (!isComplete()) {
      this.cursor++;
    }
  }

  void complete() {
    this.cursor = this.blocks.size();
  }

  void restoreCursor(int savedCursor) {
    this.cursor = Math.clamp(savedCursor, 0, this.blocks.size());
  }

  private static long signature(WallSectionKind kind, List<WallBlockPlan> blocks) {
    long hash = 0xcbf29ce484222325L ^ kind.ordinal();
    for (WallBlockPlan block : blocks) {
      hash = (hash ^ block.position()) * 0x100000001b3L;
      hash = (hash ^ block.piece().ordinal()) * 0x100000001b3L;
      hash = (hash ^ block.role().ordinal()) * 0x100000001b3L;
    }
    return hash;
  }
}
