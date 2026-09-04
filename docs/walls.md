# Walls

A wall is a route compiled into buildings-sized pieces. It is not one enormous
`Building`, because it has no fixed footprint, and it is no longer a stream of
single columns either. The village chooses and saves a perimeter once, compiles
that route into short sections, and lets builders claim those sections as
independent construction work.

Wooden walls combine authored and procedural geometry at the
`WallSegmentCatalog` boundary. Small NBT captures define the local silhouette
of straight runs, diagonal runs, terraces, watchtowers, and gatehouses. The
catalog rotates and joins those pieces along a procedural route, then emits the
same persistent `WallSection` and `WallBlockPlan` values used by builder AI and
saves. Stone currently keeps its fully procedural, walkable structure.

## What the village builds

There are two tiers on one permanent route:

- **Wood palisade:** a narrow three-course defensive shell decorated with
  authored uneven posts, beams, fences, lanterns, watchtowers, and gatehouses.
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

Four gates prefer the cardinal midpoints. Before the plan is saved, each one
moves to the nearest dry socket on the same straight run when its full authored
footprint would touch water. A side with no dry socket gets no gate rather than
a gatehouse standing in a pond. Corner watchtowers use the same full-footprint
check and are omitted when their site is wet.

## Terrain: terraces, not terrain noise

The Great Wall is the visual reference for terrain behavior. The deck follows
the broad slope of the land, but it does not copy every grass-block bump.

At project creation, `WallTerraces` groups the captured natural ground into
four-block runs. Each run clears its highest ground and smooths toward its
neighbors while the local terrain allows it. A deck is never raised more than
five blocks above the ground in its own column. At a severe cliff the planner
therefore accepts a sharper terrace transition instead of turning the downhill
side into a giant palisade. The rule is circular, so the saved ring has no bad
seam where its last block meets its first.

Open water is a separate floor for the deck, not a replacement for terrain.
The deck keeps the wall's full normal height above the waterline, while the
saved natural-ground profile still extends its foundation down to the seabed.
This prevents a lake crossing from becoming a one-block barrier that can be
swum over.

The wall fills from the lowest neighboring ground sample to its deck. This
keeps the defensive shell closed at terrain steps. Trees and brush are not
sampled as terrain, and natural vegetation intersecting a planned wall cell is
replaced by that wall cell. Player blocks and earlier village construction are
still preserved.

When construction finishes, its last builder first opens a three-block tree
line around the complete footprint using the same `TreeFelling` verdict as a
lumberjack. Only natural trees come down, never player-owned or village-owned
timber, and the logs drop into the world for villagers to collect. The pass is
route-shaped, so it does not clear the whole interior of the perimeter.

After the trunks are felled, the wall clears tagged vegetation through its own
columns and one horizontal block on both the village and wilderness sides. The
clearance follows gates and towers as well as ordinary runs, removing leaves
above the palisade along with adjacent saplings, brush, vines, and other
foliage down to real ground. Player-owned and village-owned construction stays
protected. This leaves no canopy over the wall and no vegetation close enough
to give mobs a step onto it.

Large authored pieces remain coherent instead of shearing with every terrain
sample. Their vertical posts extend to the live natural ground in each post's
exact column. A watchtower's low fence, trapdoor, and ladder shaft uses the same
foundation rule, so a raised terrace or off-route downhill leg cannot leave the
tower or its access hanging above the ground. Ordinary authored runs keep no
more than two courses of decorative silhouette above their local deck, which
preserves uneven posts without allowing one terrain step to become a tall mast.

After the final wall cell is placed, structural foundations are checked against
their completed surroundings. When a natural dirt course has a side exposed to
air, the foundation replaces that one course. Buried dirt and player-owned or
village-owned ground remain untouched. This makes an edge wall read as sunk
into the bank instead of balanced on its visible dirt face.

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

Structural cells are ordered before ladders, trapdoors, campfires, and
lanterns. In particular, the beam above a hanging lantern is placed first, so
the lantern cannot pop off during construction. Ordinary runs retain one
lantern on alternating sections. Feature-overlap cleanup discards any lantern
whose support was removed. Authored gatehouses and towers keep the outward roof
lights and omit the visually busy pair facing into the village.

This makes a wall behave like a continued structure. One swing places one
construction cell. A builder stays with a short section, several builders can
raise visibly different parts of the perimeter, and a restart resumes each
piece at its exact next cell.

## Occupancy and materials

Construction cells have semantic roles:

- **Barrier:** any collidable block satisfies the cell. Air, fluid, and other
  passable states are filled. Natural logs, leaves, and other clearable
  vegetation are deliberately cut through rather than accepted as part of the
  defensive shell.
- **Exact:** the catalog prefers its stair, parapet, or decorative state. An unrelated
  collidable block is still preserved, because closing the defensive shell is
  more important than forcing a palette over player or terrain construction.
- **Foundation:** an exact authored state that repeats down to the live terrain
  in its own column, used by off-route posts and watchtower access shafts.
- **Clearance:** an internal planning cell removes lower-priority generated
  geometry from an authored tower or gatehouse's empty volume. It is discarded
  before construction, so builders never place an "air block."

The deliberate stone upgrade is the exception. Village-owned oak, spruce, or
acacia wall blocks do not satisfy a stone plan and can be replaced.

Walls remain priced at one material item per ten placed construction cells.
The affordability check uses the same compiled plan and occupancy policy as the
builder, so existing solid cells cost nothing and the estimate matches the work.
Credit left from an item carries across cells in that builder's pack.

## Gatehouses and defense

The catalog reserves seventeen route blocks for each gatehouse. A wooden
gatehouse is an open passage with no door; its authored deck, ladder, correctly
supported standing and hanging lanterns, beams, and flanking posts rotate onto
any cardinal run. Authored feature volumes replace the ordinary palisade rather
than being layered through it. One watchtower owns each clipped corner, rather
than placing overlapping towers at both ends of the same chamfer. Stone keeps a
three-high open center passage and compact procedural towers until it receives
its own art pass.

The normal barrier invariant remains simple: after a wall cell is decided,
that cell must be solid and collidable. Open gate passage cells and the authored
interiors of towers are intentional exceptions.

A completed wall also derives guard workstations from that exact compiled
geometry. These are not separate buildings and do not add wall beds. Each gate
has two ground-level sword posts and one elevated crossbow post; each non-gate
watchtower has one elevated crossbow post. Elevated stations are selected from
real walkway or roof support cells with two blocks of headroom, so wood, stone,
and terrain-following walls share the same job model.

Open wall posts are registered in four village-wide staffing tiers:

1. one stone-sword guard at every gate,
2. one crossbow guard at every non-gate watchtower,
3. one crossbow guard above every gate,
4. the second stone-sword guard at every gate.

All four are ordinary `GUARD` occupations with a wall-post specialization. This
keeps guard aptitude, housing, rations, shields, armor, threat selection, and
equipment upgrades in one system. Sword guards may leave the base to fight and
return afterward. Crossbow guards hold their elevated station instead of
pathing off the wall for a blocked shot. Better swords, crossbows, armor,
shields, and special arrows come from the same physical village inventory rules
as the existing guard and hunter equipment. A wall under construction or being
upgraded publishes no posts; the completed geometry registers the new set.

Ground mobs are stopped by the continuous shell outside its open gates. The wooden
silhouette includes authored overhangs, while spider-proof behavior remains a
separate gameplay test.

## Authoring contract

The five canonical wooden templates live under
`data/kithkyn/structure/wall/wood/`. They were captured from the in-world
wall lab and are loaded as semantic cells instead of stamped directly into the
world. One oak-authored geometry therefore resolves through the village style
as oak for plains, spruce for taiga and snowy villages, or acacia for desert and
savanna villages.

Each authored template needs:

- an entry and exit socket on the route centerline,
- a declared section kind and supported tier,
- a deck socket elevation,
- barrier cells that may accept any existing collision,
- exact detail cells for stairs, rails, and decoration,
- foundation cells that can extend down to the captured terrain,
- clearance cells for the walkable deck and gate passage.

Straight, diagonal, and terrace captures contribute one vertical slice per route
column and therefore follow the terrain-owned sockets. Corner towers and
gatehouses are rigid authored interiors with foundation posts that grow down to
their exact live terrain columns. New palette blocks must map to a semantic
`WallBlockPlan.Piece`; unknown decorative blocks are ignored instead of leaking
a fixed biome palette into every village.

The existing structure capture loop in [structure-authoring.md](structure-authoring.md)
is used to revise the NBT files. The wall lab remains the visual authoring
gallery. Replacing a captured piece changes the catalog without another builder
or save-system rewrite.

## Planning and developer preview

Walls are safety projects. An established village starts wood after sufficient
growth or danger, then later upgrades that same route to stone. While incomplete,
the wall holds normal village project selection just as a building project does.

`/kkdev village wall <wood|stone>` compiles the same project and places all of
its cells immediately. It is the fast geometry check for the route, terraces,
walkway, towers, and gatehouses. Ordinary builders use that identical plan one
cell at a time.

`/kkdev village wall-area <wood|stone> <from> <to> [style]` runs the planner
around any two opposite x/z corners without changing a village's saved wall.
Both spans must be 16 to 128 blocks. Omitting `style` derives the wood family
from the biome at the rectangle center. This is the live integration check for
flat ground, rolling terrain, slopes, and cliffs.

## Current limits

- Wooden wall art is authored. Stone wall art remains procedural.
- The stone walkway is structurally continuous but has no internal stairway
  from the village ground up to each tower yet.
- Spider-proof behavior still needs a focused gameplay test.
- Existing worlds with an in-progress legacy column wall should be reset. A
  completed legacy wall remains complete, but partially completed legacy
  cursor positions cannot map exactly onto the new multi-cell sections.
