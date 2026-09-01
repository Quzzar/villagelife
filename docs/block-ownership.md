# block-ownership.md

Who owns the block at a position. The rule is the simple one it sounds like: **a block a
player placed is the player's, a block the village placed is the village's, and a block
neither placed is nobody's.** Both facts are recorded at placement time and dropped when the
block is broken. Nothing is derived from geometry at query time.

The first caller is tree-clearing, through `village/TreeFelling.java` (see
[worker-loops.md](worker-loops.md) for the workers that swing the axe and
[site-selection.md](site-selection.md) for the felling a new building does over its own
roof): a feller may bring down a log that nobody placed. That protects a mine's timber
supports and a player's cabin block by block, while a natural tree - wherever it stands,
village ground included - is fellable.

## The store: `PlacedBlockStore`

One store per level (`savedata/PlacedBlockStore.java`), holding two compact sets of packed
positions: player-placed and village-placed.

- Positions are `long` keys in primitive `LongOpenHashSet`s, eight bytes each, saved as
  `LongArrayTag`s. A whole village is tens of kilobytes; compactness was the design worry,
  and this is the answer to it.
- Any block break removes the position from both sets, so build-and-tear churn nets to
  nothing.
- Per level because `BlockPos.asLong` does not encode the dimension.

## Who writes it

**Players** (`events/BlockPlacementEvents.java`): every place event records, every break
event prunes. **Except plants.** A sapling, crop, or flower is put down to grow, not to
stand; recording it would carry over to whatever grows from it. This was learned live: a
planted sapling's position stayed "the player's" after the oak grew, so a guard felled the
whole tree and left exactly one block standing - the base, on the sapling's old position.
Plants are nobody's, and what grows from them is fellable.

**Villages**, at each place a village physically sets blocks down:

| Writer | What |
| --- | --- |
| `InstantBuildStructure.buildInstantly` | every non-air block a building template stamps |
| `StructureInProgress.progressMiddlePhase` | every non-air block the builder lays, one per swing |
| `WallRaiser.place` / `placeGateDoor` | wall segments and gate doors (the wood tier is logs) |
| `Village.placeCampfireIfMissing` | the gathering-point campfire |

Not recorded on purpose: saplings a lumberjack stand replants (a planted tree is meant to be
cut), crops, ground-shaping fill (dirt is not a structure), and paths. If a new feature
places structural village blocks, it records them; that is the contract.

## The query: `BlockOwnership`

`village/BlockOwnership.java`:

- `query(level, pos)` returns `Ownership(village, building, playerPlaced, villagePlaced)`.
  The village/building fields are best-effort context from the ground claim and building
  circles; the two booleans are the stored facts.
- `mayFell(level, pos)`: nobody placed it. The felling verdict. `TreeFelling` asks it for
  every log of a tree it brings down, and once more up front (with the natural-canopy test)
  before a log is offered as a tree at all.
- `isPlayerPlaced` / `isVillagePlaced`: the stored facts individually.

## No backfill

Worlds whose buildings predate the store are not migrated: the store starts recording from
the placement events forward, and that is the whole story (decided 2026-09-01; the one dev
world affected was simply retired). If an old world ever matters, the building templates and
origins are persisted, so a replay migration could be written; it deliberately does not
exist today.

## Known imprecisions, all bounded, all over-protecting

- Non-player removals (explosions, pistons, fluids) fire no break event, so a destroyed
  block's record lingers until something is placed and broken there. Lingering records can
  only spare a block, never doom one.
- A building upgrade stamps the new template over the old; replaced positions that the new
  template leaves air keep their old record until broken.

## Still open

- **Per-player attribution.** The player set is a boolean today: it knows a player placed a
  block, not which player. A use that needs the placer turns the set into a map.
- **More callers.** Anything that edits the world near a village should ask `mayFell` or
  `query` rather than re-deriving ownership.
