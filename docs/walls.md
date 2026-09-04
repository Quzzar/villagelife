# Walls

A wall is a route compiled into buildings-sized pieces. It is not one enormous
`Building`, because it has no fixed footprint, and it is no longer a stream of
single columns either. The village chooses and saves a perimeter once, compiles
that route into short sections, and lets builders claim those sections as
independent construction work.

This is the foundation for authored straight runs, diagonal runs, corners,
towers, and gatehouses. The initial catalog is generated in code so the system
is playable before the art pass. A future NBT catalog plugs in at the
`WallSegmentCatalog` boundary and emits the same persistent `WallSection` and
`WallBlockPlan` values, without changing builder AI or saves.

## What the village builds

There are two tiers on one permanent route:

- **Wood palisade:** a narrow three-course barrier with closing doors.
- **Stone wall:** a three-block-wide body with a walkable top course, parapets,
  stair transitions, corner towers, and gatehouse sections.

Stone is an in-place upgrade of wood. It reuses the saved ring and natural
ground profile. Village-owned palisade blocks may be replaced, while unrelated
solid construction is preserved.

## The route

The route wraps the saved claim bounds with eight blocks of breathing room.
Four cardinal runs are joined by broad 45-degree corners. The clipped corners
keep a wall from reading as one giant rectangle and provide real diagonal and
corner sockets for the segment catalog.

The ring is computed once when the first wall begins. It does not join
`claimGrid`, and it is not recomputed as the village grows. Newer neighborhoods
can therefore spill beyond an older defensive core.

Four gates sit at the cardinal midpoints. They stay on the straight runs when
corners are clipped, so roads can meet a gatehouse squarely.

## Terrain: terraces, not terrain noise

The Great Wall is the visual reference for terrain behavior. The deck follows
the broad slope of the land, but it does not copy every grass-block bump.

At project creation, `WallTerraces` groups the captured natural ground into
four-block runs. Each run clears its highest ground. Adjacent runs may differ by
at most one block; a steep hill raises the approach runs before it instead of
creating a cliff in the walkway. This produces long level terraces joined by
deliberate stair blocks. The rule is circular, so the saved ring has no bad seam
where its last block meets its first.

The wall fills from the lowest neighboring ground sample to its deck. This
keeps the defensive shell closed at terrain steps. Trees, brush, player blocks,
and earlier village construction are not sampled as terrain.

## Segment projects

`WallSegmentCatalog` compiles the ring into sections of at most seven route
blocks and classifies them as:

- straight,
- diagonal,
- terrace,
- corner tower,
- gatehouse.

Each section owns an ordered list of construction cells and a saved cursor.
`WallProject` leases different incomplete sections to different builders. A
lease is runtime-only and expires if its builder disappears, while the cursor
is persistent. An unreachable section waits briefly and releases its builder;
all other sections remain available.

This makes a wall behave like a continued structure. One swing places one
construction cell. A builder stays with a short section, several builders can
raise visibly different parts of the perimeter, and a restart resumes each
piece at its exact next cell.

## Occupancy and materials

Construction cells have semantic roles:

- **Barrier:** any collidable block satisfies the cell. Air, fluid, vegetation,
  and other passable states are filled.
- **Exact:** the catalog prefers its stair, parapet, or door state. An unrelated
  collidable block is still preserved, because closing the defensive shell is
  more important than forcing a palette over player or terrain construction.

The deliberate stone upgrade is the exception. A village-owned oak wall block
does not satisfy a stone plan and can be replaced.

Walls remain priced at one material item per ten placed construction cells.
The affordability check uses the same compiled plan and occupancy policy as the
builder, so existing solid cells cost nothing and the estimate matches the work.
Credit left from an item carries across cells in that builder's pack.

## Gatehouses and defense

The starter catalog reserves five route blocks for each gatehouse. The center
has a three-high passage through the stone wall, closed by a wooden door that
villagers already know how to operate. Compact towers flank the passage. Corner
sections also receive raised 3x3 towers.

The normal barrier invariant remains simple: after a wall cell is decided,
that cell must be solid and collidable. Gate passage cells are the intentional
exception and are closed by the door.

Ground mobs are stopped by the continuous shell and closing gates. Spiders can
still climb a flush wall. A lipped authored parapet is the intended answer for
the art pass.

## Authoring contract

The code-generated catalog is an adapter, not the permanent source of wall art.
Authored pieces should be captured as small NBT structures and compiled through
`WallSegmentCatalog` into the same semantic cells. Every authored template will
need:

- an entry and exit socket on the route centerline,
- a declared section kind and supported tier,
- a deck socket elevation,
- barrier cells that may accept any existing collision,
- exact detail cells for stairs, rails, doors, and decoration,
- foundation cells that can extend down to the captured terrain,
- clearance cells for the walkable deck and gate passage.

Straight and diagonal pieces should share a seven-block maximum length. Corner,
tower, and gatehouse pieces may be shorter. Terrain adaptation belongs at the
sockets: the planner selects a level or one-block-rise variant, while the
authored interior stays coherent.

The existing structure capture loop in [structure-authoring.md](structure-authoring.md)
is used to create the NBT files. The NBT catalog loader and a wall-segment
gallery are the next art-pipeline slice; they do not require another builder or
save-system rewrite.

## Planning and developer preview

Walls are safety projects. An established village starts wood after sufficient
growth or danger, then later upgrades that same route to stone. While incomplete,
the wall holds normal village project selection just as a building project does.

`/vldev village wall <wood|stone>` compiles the same project and places all of
its cells immediately. It is the fast geometry check for the route, terraces,
walkway, towers, and gatehouses. Ordinary builders use that identical plan one
cell at a time.

## Current limits

- The starter catalog is procedural. NBT-backed segment art and its gallery are
  the next layer at the catalog boundary.
- The stone walkway is structurally continuous but has no internal stairway
  from the village ground up to each tower yet.
- Spiders still need an overhanging authored parapet.
- Existing worlds with an in-progress legacy column wall should be reset. A
  completed legacy wall remains complete, but partially completed legacy
  cursor positions cannot map exactly onto the new multi-cell sections.
