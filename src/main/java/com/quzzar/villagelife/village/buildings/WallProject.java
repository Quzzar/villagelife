package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;

/**
 * A persistent wall construction project made from independently claimable sections.
 *
 * The perimeter and terrain profile are the wall's permanent identity. A segment
 * catalog compiles them into explicit construction cells once, and each section
 * keeps its own cursor. Builders can therefore work on different pieces at the
 * same time, and a restart resumes the exact block each piece had reached.
 */
public final class WallProject {

  private static final long CLAIM_TIMEOUT_TICKS = 20 * 60;
  private static final long RETRY_DELAY_TICKS = 20 * 30;

  public static final Codec<WallProject> CODEC = RecordCodecBuilder.create(inst -> inst.group(
      Codec.LONG.listOf().fieldOf("ring").forGetter(wall -> wall.ring),
      Codec.LONG.listOf().fieldOf("gates").forGetter(wall -> List.copyOf(wall.gates)),
      Codec.INT.listOf().optionalFieldOf("ground", List.of()).forGetter(wall -> wall.ground),
      Codec.STRING.fieldOf("tier").forGetter(wall -> wall.tier.name()),
      Codec.INT.optionalFieldOf("cursor", 0).forGetter(WallProject::legacyCursor),
      Codec.INT.listOf().optionalFieldOf("deferred", List.of()).forGetter(wall -> List.of()),
      Codec.INT.listOf().optionalFieldOf("deck", List.of()).forGetter(wall -> wall.deck),
      SavedSection.CODEC.listOf().optionalFieldOf("sections", List.of()).forGetter(WallProject::savedSections)
  ).apply(inst, WallProject::fromCodec));

  private final List<Long> ring;
  private final Set<Long> gates;
  private final List<Integer> ground;
  private final List<Integer> deck;
  private final WallTier tier;
  private final List<WallSection> sections;

  /** Runtime leases only. A crashed or unloaded builder cannot strand saved work. */
  private final transient Map<UUID, Claim> claimsByBuilder = new HashMap<>();
  private final transient Map<Integer, UUID> buildersBySection = new HashMap<>();
  private final transient Map<Integer, Long> retryAfter = new HashMap<>();

  public WallProject(List<Long> ring, Set<Long> gates, List<Integer> ground, WallTier tier) {
    this(ring, gates, ground, tier,
        WallTerraces.deckProfile(ground, tier.height()), List.of(), false);
  }

  private WallProject(List<Long> ring, Set<Long> gates, List<Integer> ground, WallTier tier,
      List<Integer> deck, List<SavedSection> savedSections, boolean complete) {
    this.ring = List.copyOf(ring);
    this.gates = Set.copyOf(gates);
    this.ground = List.copyOf(ground);
    this.tier = tier;
    this.deck = deck.size() == ring.size()
        ? List.copyOf(deck)
        : WallTerraces.deckProfile(ground, tier.height());
    List<WallSection> compiled = WallSegmentCatalog.builtIn()
        .compile(this.ring, this.gates, this.ground, this.deck, tier);
    this.sections = new ArrayList<>(compiled);
    restoreProgress(savedSections);
    if (complete) {
      this.sections.forEach(WallSection::complete);
    }
  }

  /** A wall recorded as fully built, used by the instant dev preview. */
  public static WallProject completed(List<Long> ring, Set<Long> gates, List<Integer> ground,
      WallTier tier) {
    return new WallProject(ring, gates, ground, tier,
        WallTerraces.deckProfile(ground, tier.height()), List.of(), true);
  }

  private static WallProject fromCodec(List<Long> ring, List<Long> gates, List<Integer> ground,
      String tierName, int legacyCursor, List<Integer> legacyDeferred, List<Integer> deck,
      List<SavedSection> sections) {
    WallTier tier = WallTier.valueOf(tierName);
    boolean oldSaveWasComplete = sections.isEmpty()
        && legacyCursor >= ring.size()
        && legacyDeferred.isEmpty();
    return new WallProject(new ArrayList<>(ring), new HashSet<>(gates), new ArrayList<>(ground),
        tier, new ArrayList<>(deck), new ArrayList<>(sections), oldSaveWasComplete);
  }

  /** Restores cursors only when the catalog still describes the same section sequence. */
  private void restoreProgress(List<SavedSection> savedSections) {
    if (savedSections.size() != this.sections.size()) {
      return;
    }
    for (int i = 0; i < this.sections.size(); i++) {
      if (savedSections.get(i).kind() != this.sections.get(i).kind()
          || savedSections.get(i).signature() != this.sections.get(i).signature()) {
        return;
      }
    }
    for (int i = 0; i < this.sections.size(); i++) {
      this.sections.get(i).restoreCursor(savedSections.get(i).cursor());
    }
  }

  private List<SavedSection> savedSections() {
    return this.sections.stream()
        .map(section -> new SavedSection(section.kind(), section.cursor(), section.signature()))
        .toList();
  }

  /** True once every authored construction cell in every section has been visited. */
  public boolean isComplete() {
    return this.sections.stream().allMatch(WallSection::isComplete);
  }

  /**
   * Leases the nearest free section to a builder, preserving an active lease so
   * one builder reads as continuing a structure rather than hopping around it.
   */
  public int claimSection(UUID builder, BlockPos from, long gameTime) {
    expireClaims(gameTime);
    Claim existing = this.claimsByBuilder.get(builder);
    if (existing != null && !this.sections.get(existing.section()).isComplete()) {
      this.claimsByBuilder.put(builder, new Claim(existing.section(), gameTime));
      return existing.section();
    }
    release(builder);

    int best = -1;
    long bestDistance = Long.MAX_VALUE;
    for (int i = 0; i < this.sections.size(); i++) {
      WallSection section = this.sections.get(i);
      if (section.isComplete() || this.buildersBySection.containsKey(i)
          || this.retryAfter.getOrDefault(i, 0L) > gameTime) {
        continue;
      }
      BlockPos target = section.next().pos();
      long dx = (long) target.getX() - from.getX();
      long dz = (long) target.getZ() - from.getZ();
      long distance = dx * dx + dz * dz;
      if (distance < bestDistance) {
        best = i;
        bestDistance = distance;
      }
    }
    if (best >= 0) {
      this.claimsByBuilder.put(builder, new Claim(best, gameTime));
      this.buildersBySection.put(best, builder);
    }
    return best;
  }

  /** Advances one builder's section after a cell was placed or found already satisfied. */
  public void advance(UUID builder, int sectionIndex) {
    Claim claim = this.claimsByBuilder.get(builder);
    if (claim == null || claim.section() != sectionIndex) {
      return;
    }
    WallSection section = this.sections.get(sectionIndex);
    section.advance();
    if (section.isComplete()) {
      release(builder);
    }
  }

  /** Defers an inaccessible section without serializing every other builder behind it. */
  public void defer(UUID builder, int sectionIndex, long gameTime) {
    Claim claim = this.claimsByBuilder.get(builder);
    if (claim == null || claim.section() != sectionIndex) {
      return;
    }
    this.retryAfter.put(sectionIndex, gameTime + RETRY_DELAY_TICKS);
    release(builder);
  }

  /** Whether this builder still owns this section's current cell. */
  public boolean owns(UUID builder, int sectionIndex, WallBlockPlan block) {
    Claim claim = this.claimsByBuilder.get(builder);
    return claim != null
        && claim.section() == sectionIndex
        && !this.sections.get(sectionIndex).isComplete()
        && this.sections.get(sectionIndex).next().equals(block);
  }

  public WallSection section(int index) {
    return this.sections.get(index);
  }

  public int sectionCount() {
    return this.sections.size();
  }

  public int remainingBlocks() {
    return this.sections.stream().mapToInt(section -> section.blocks().size() - section.cursor()).sum();
  }

  private void expireClaims(long gameTime) {
    List<UUID> expired = this.claimsByBuilder.entrySet().stream()
        .filter(entry -> gameTime - entry.getValue().heartbeat() > CLAIM_TIMEOUT_TICKS)
        .map(Map.Entry::getKey)
        .toList();
    expired.forEach(this::release);
  }

  private void release(UUID builder) {
    Claim removed = this.claimsByBuilder.remove(builder);
    if (removed != null && builder.equals(this.buildersBySection.get(removed.section()))) {
      this.buildersBySection.remove(removed.section());
    }
  }

  private int legacyCursor() {
    return isComplete() ? this.ring.size() : 0;
  }

  public WallTier getTier() {
    return this.tier;
  }

  public List<Long> getRing() {
    return this.ring;
  }

  public Set<Long> getGates() {
    return this.gates;
  }

  public List<Integer> getGround() {
    return this.ground;
  }

  /** Whether the saved wall carries one natural-ground sample per route column. */
  public boolean hasGround() {
    return this.ground.size() == this.ring.size();
  }

  public List<Integer> getDeck() {
    return this.deck;
  }

  /** Number of explicit construction cells already processed across every section. */
  public int getCursor() {
    return this.sections.stream().mapToInt(WallSection::cursor).sum();
  }

  public int size() {
    return this.ring.size();
  }

  private record Claim(int section, long heartbeat) {
  }

  /** The compact saved form; deterministic block plans are regenerated from the ring. */
  private record SavedSection(WallSectionKind kind, int cursor, long signature) {

    private static final Codec<SavedSection> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        WallSectionKind.CODEC.fieldOf("kind").forGetter(SavedSection::kind),
        Codec.INT.fieldOf("cursor").forGetter(SavedSection::cursor),
        Codec.LONG.fieldOf("signature").forGetter(SavedSection::signature)
    ).apply(inst, SavedSection::new));
  }
}
