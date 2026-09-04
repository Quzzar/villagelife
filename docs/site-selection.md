# Site selection and ground clearing

[village-tiers.md](village-tiers.md) names space as a first-class build constraint and
defers the design to its own doc. This is that doc. It covers how a site is found, how
local placement decisions form streets and blocks, whether villagers may reshape the
ground to make a site, and what that costs at runtime.

Implementation state: **the prepare phase is built** as of
[#69](https://github.com/Quzzar/kithkyn/issues/69). `SitePreparation.planWork` returns
the actual positions to break and to fill, a project carries that work in its own persisted
queues, and the BUILDER performs it as the first phase of construction: one block per swing,
cleared blocks going into village storage rather than onto the ground, fill paid for out of
the village's own dirt. A site that needs work enters `PREPARING` and places nothing until
the ground is ready. When fill runs out the village emits the ordinary shortage event and
the builder says so in their own log, rather than spinning.

**Candidates are snapped to the real surface before scoring**, which is mitigation 1 below
and was for a long time simply absent. The snap reads the footprint's most common ground
height, not one column's (2026-09-01): seated at its origin column, a house went into the
world a block low whenever that column was a dip in an otherwise level plain, and the prepare
phase would then have dug the whole footprint down to meet it; now the odd columns are the
ones levelled. Sites used to be scored at the village centre's
elevation plus a blind vertical offset, and the levelling budget then rejected anything that
was not within about a block of the actual terrain — so in a live world every candidate came
back impossible and no village could build anything at all. Snapping also removes the
nine-elevation loop, so a search reads roughly a ninth of the blocks it used to, and
candidates in unloaded chunks are skipped before any scan rather than being scored as
impossible one at a time.

The planner takes ground that needs work. It first tries edge-aligned frontage slots beside
completed buildings, with one clear block between footprints as a shared lane. A corner or
row that continues more existing frontage wins, then frontage beside an already worn path,
then preparation cost and distance. If no frontage slot works, the nearest-first terrain
sweep is the fallback. Heightmap-first screening is built, and a refused search leaves the
village knowing where its room ran out. Still unbuilt: the resumable budgeted search and the
site cache.

**Where a village looks.** `LocationValidator` starts from buildings, then falls back to a
nearest-first sweep. The village once threw a handful of random candidates at a square ring
and took the first free one, which put buildings a long way from the fire: open ground far
out is free, while near ground is often claimed or wants a little levelling.

The planner now enumerates the beginnings, centres and ends of each completed footprint's
four edges. It puts the candidate one lane away in every offered rotation, then prefers a
site that lines more than one edge because that is natural infill. Repeating this local
relationship produces rows, narrow streets and small courtyards without forcing the village
onto a global grid. Later growth starts by asking how it relates to the town already standing,
not only how far it is from the fire.

When none of those exact slots fits, the sweep walks a grid outward from the fire, nearest
first, and takes the best ground in a short distance band. It steps over ground too steep to
level and slots too tight to hold the footprint, so the fallback molds itself to the terrain.
The sweep reaches 32 blocks past the ring (never beyond 96); the ring's outer edge grows with
the village, one search radius plus another for every four buildings, and never shrinks to
nothing. Candidates stand off the centre rather than starting on it.

Every candidate is tried in each rotation the caller offers (the planner offers all four),
and the facing that fits the slot is the one kept: a long building turns to fit a gap its
other facing could not, and among equally free facings the pick is random, which is where a
village's variety of orientation comes from now that placement no longer scatters. A clear
gap of `MIN_GAP` blocks is held between a new footprint and everything already claimed, so
lanes stay walkable and the cluster reads as planned rather than piled; a candidate whose
footprint, grown by that gap, touches a claim is passed over. A worn dirt path is public
space: a building may line it and gains a placement preference for doing so, but may not
cover it.

Before any candidate is scored, the heights of the whole search square are read once from the
chunk heightmaps (`MOTION_BLOCKING_NO_LEAVES`, the real ground under a canopy) into a grid,
so every candidate's flatness is arithmetic: its plane is the height most of its columns
share, and ground with more than one column in eight past the per-column budget, or averaging
past the levelling budget across the rest, is refused without a block scan. A few tall columns
are let through because a tree reads as a tall column and is cleared, not levelled; one outlier
is always allowed through even on a small footprint. The exact scan also uses that allowance for
a compact low corner, but not for high ground or a broad depression. Only
survivors get the volume scan. In the fallback, the first candidate that is free or
preparable settles a band and the sweep reads 8 blocks further out. Relationship to claimed
edges and paths breaks up the old first-grid-point behavior, followed by preparation cost
and distance. Taking the cheapest ground in the whole reach (2026-09-02)
put Wildflower Downs' lumberjack 90 blocks from its fire, 78 blocks of work there against 211
within 50; a village that sprawls has a wall ring it cannot afford and ground it cannot finish
grading. The grid's stride is 2, fine enough to pack the ring in snugly, and a footprint is
wider than that, so a slot with room to spare cannot fall between grid points; a slot that
fits only exactly can, and a refusal means "no site with a little room to spare". Unloaded
chunks read as no ground and are never loaded by the search: a refusal records how far out
the village actually read ground, and with nobody near, that is roughly the founding
forceload rather than the full sweep.

A village only searches while its ground is loaded. Nothing can be sited in a chunk nobody
is near, and asking the brain to choose would spend a model call on an answer no site search
could act on, so an unwatched village waits rather than planning.

Every search leaves one debug line saying what it saw: how many candidates were scored, how
many sat on claimed ground, how many covered a path, and how many were in unloaded chunks.
The selected site's claimed frontage, adjacent sides, path frontage and preparation cost are
logged too. A search that skips every
candidate before scoring is otherwise indistinguishable from one where every site was
genuinely bad, and that ambiguity hid the zero-radius bug above for as long as it existed.

**What the village knows when it finds nothing.** A refused search is remembered in
`SiteMemory` for 900 village seconds, and dropped the moment anything is built: the
footprint that found no ground, which rules out every footprint at least as large in both
dimensions; how far out the search read ground; and the nearest thing to a site it saw,
meaning the refused ground with the least earth standing off level, with the fact that ruled
it out ("22 of its 99 columns stand more than 3 blocks off level"). Water, claimed ground and
someone's chest are never a near miss, since no levelling makes them a site.
`Village.describeRoom` turns that into one sentence of facts, and both briefings carry it:
the planner's situation, so the brain knows why a building is missing from its options
instead of choosing around a gap, and every villager's chat, so a builder asked why nothing
is going up can say that nothing 9 by 11 or larger has found ground within about 40 blocks
of the fire and that the nearest thing to a site was 18 blocks east. The sentence ends with
the rule, that villagers level ground only lightly and never reshape a hill, and says
nothing about what to do: building smaller, waiting, or a player levelling the slope by hand
are the reader's calls. `/kkdev village place` with no position records a refusal the same way
a real project does, which is the on-demand way to see the sentence; giving it a position
forces the building onto that exact spot instead, bypassing the search, so authoring a
schematic in-world is never blocked by the room the planner would refuse. That division of
labour is deliberate. The village holds the
surface-not-shape line; a player is free to break it, and a slope they level is found on the
next search once the refusal expires. The refusal is logged at INFO with the same sentence.

`SitePreparation.score` prices any candidate
footprint in blocks moved (clear, cut, fill, or impossible) using the
`kithkyn:clearable` whitelist tag, the per-column and average levelling budgets below,
the block-entity and claim protections, and the never-scan-unloaded rule. The planner's
site search accepts free and preparable sites within those budgets; rejected candidates log
their price at debug, and
`/kkdev village score-site <pos> <sizeX> <sizeZ>` prints any site's bill, and
`/kkdev village start-project <building> <pos>` begins a real project on ground you choose,
which is the direct way to watch preparation run. The resumable budgeted search and the site
cache are later slices.

## The problem this replaced

`LocationValidator.isValidLocation` answered a yes-or-no question: is this block position
buildable. `isValidPlacement` then sampled a footprint's perimeter plus five interior points
and rejected the site if any sample failed. **Both methods have since been deleted** in
favour of the cost model below; this section records why.

That model cannot express the most common real case. Two saplings and a dirt hummock in an
otherwise perfect meadow fail the check exactly as hard as a ravine does. The village then
reports "we don't have enough room" while standing in a field.

## Site validity is a cost, not a gate

**A site is not valid or invalid. It has a preparation cost, measured in blocks moved.**

That one change collapses space and resources into a single comparable number, which is what
the brain needs to choose between options:

| Site | Preparation | Reads as |
| --- | --- | --- |
| Flat meadow | 0 blocks | free |
| Meadow with two trees | ~40 blocks removed, yields ~30 logs | cheap, and it pays for itself |
| Gentle slope | ~120 blocks cut, ~80 filled | expensive, needs dirt from storage |
| Hillside, ravine edge, deep water | beyond budget | not a site |

Preparation cost is its own quantity, counted in blocks moved, and it is deliberately not
folded into the building's cost. A building's cost is a recipe of items
([building-spec.md](building-spec.md)); a site's preparation is the separate question of
whether the ground can take the building's dimensions and what it takes to make it. Fill
consumes real material from storage, so a site that needs levelling has a bill; clearing
mostly does not.

Clearing yields go into village containers. A forested site is not purely a cost: clearing it
is a lumber harvest that happens to also make room, which is exactly the kind of thing the
village should notice and the journal should mention.

## How far a village may reshape the ground

Three tiers of preparation, and the third one does not exist.

| Tier | What it does | Allowed |
| --- | --- | --- |
| **0. Clear** | Remove vegetation and non-solid cover: grass, flowers, snow layers, leaves, trees, loose surface litter | Always |
| **1. Level** | Cut solid terrain above the build plane and fill below it, within a budget | Within budget |
| **2. Excavate** | Remove a hill, fill a ravine, drain a lake | **Never** |

The budget for tier 1 is a per-column average, not a total: roughly 1.5 blocks off the build
plane averaged across the footprint, with an ordinary per-column maximum of 3. One compact
low depression may reach 6 blocks below the plane: at most one such column per eight footprint
columns (with one allowed even on a smaller footprint), and no connected patch more than three
blocks across either axis. That makes a dipped corner fillable without letting half a footprint,
a trench, high ground, or a ravine become a site. Fill is placed from the bottom up and consumes
dirt or the local ground material from village storage, so levelling a slope is a real expense
the brain can weigh against building somewhere else.

The rule this encodes:

> **A village changes the surface of the land, never its shape.**

**That rule governs the surface, and only the surface** (decided on
[#54](https://github.com/Quzzar/kithkyn/issues/54)). Underground is the miner's
business: a mine may sink shafts and drive tunnels as deep and as far as it likes, because
nobody is looking at the skyline from down there. One sentence covers both halves — do not
reshape what people see, dig what you like beneath it.

That line is the entire aesthetic difference between a settlement that grew into its terrain
and a player's flat dirt platform. It is also what keeps a village from eventually turning
its valley into a plateau over a hundred hours of unattended simulation.

### What may never be broken

Site preparation removes blocks. Getting this wrong once, on a player's house, poisons the
whole mod. The rule is a whitelist, not a blacklist:

- **Only blocks matching the `kithkyn:clearable` block tag may be removed.** Natural
  terrain and vegetation. Anything not in the tag makes the site invalid rather than
  becoming a target.
- Never break a block entity, ever. Not chests, not spawners, not signs.
- Never break inside another village's claim (`Village.hasClaimed` answers this). **Today
  the scorer consults only the building village's own claim**, so a neighbouring village's
  ground is not yet protected: a real gap, not a design choice.
- Never touch a vanilla or modded structure's bounds.
- A config switch turns terrain modification off entirely, leaving tier 0 clearing only.
  (Proposed; no such config key exists yet.)

The whitelist is datapack content, so a pack author decides what "natural" means in a
modded world without touching Java.

## Who does the clearing

**The BUILDER, as the first phase of construction. Not a new occupation.**

Clearing is bursty: it happens for a few minutes before a build and then not at all. Under
the campfire model ([population-and-labor.md](population-and-labor.md)) an occupation that
sits idle most of the time is a wasted job slot and a wasted bed, and the idle cap makes
worker slots genuinely scarce. Site prep is part of building, so it belongs to the builder.

Construction becomes three phases instead of one:

1. **Prepare**: walk the footprint, break tier 0 and tier 1 blocks, deposit yields in village
   storage, place fill.
2. **Build**: the existing `StructureInProgress` block-by-block placement.
3. **Finish**: register beds, work stations, and containers (already `processNewBuilding`).

A `NoResourceBookkeepingEvent` fires when fill is short, exactly as it does for build
materials, so a village that cannot afford to level a site complains in the way it already
complains about everything else.

## What stands over the roof

Ground clearing stops at the footprint's headroom: founding cuts each building's own columns,
and the prepare phase clears `CLEARANCE_HEIGHT` blocks above the plane. A tall tree outreaches
both, and a neighbour's branch can hang over the footprint without a single log in it at ground
level. So the moment a building is added to the village (`Village.addBuilding`, and
`replaceBuilding` for an upgrade) it fells whatever tree still has a log in or over its volume:
`TreeFelling.fellOver` reads each footprint column from the world surface down to the
building's floor and brings every fellable tree it meets down whole, under the same canopy and
ownership guards the lumberjack's axe uses ([block-ownership.md](block-ownership.md)), which is
also what keeps it off the building's own timber. That covers every way a building arrives:
founding, `/kkdev village place`, the builder finishing a project, and an upgrade. The wood
goes to village storage like any clearing yield; what will not fit drops where the tree stood.
A canopy with no log over the footprint is left alone: a tree beside a house is scenery, not
an obstruction.

## What it costs at runtime

The expensive part is **finding** sites, not clearing them. Clearing is a villager breaking a
few hundred blocks over several minutes, which is nothing. Scoring candidate sites is a
volume scan, and that is where a naive implementation eats a tick.

Rough shape of the cost, to be measured rather than trusted:

- A 16x16 footprint scored across an 8-block height band is ~2000 block reads. On a loaded
  chunk a read is a few array lookups, so a single site is a fraction of a millisecond.
- The danger is candidate count. Scoring 200 candidates the naive way is ~400,000 reads,
  which is a whole tick, multiplied by every village in the world.

Four mitigations, in order of how much they buy:

1. **Heightmap first, blocks second.** A footprint's flatness comes from the chunk heightmap
   in ~256 reads with no volume scan. Reject most candidates on height variance alone, then
   volume-scan only the survivors. This is the difference between viable and not. Built:
   one grid read per search, then arithmetic per candidate.
2. **Budget reads per tick.** Site search becomes a resumable job with a read budget per
   village per tick, not a synchronous loop inside the planner. `Village` already
   phase-staggers attractiveness across villages, so the pattern exists to copy.
3. **Cache scored sites.** Terrain barely changes. Keep a small ring of the best known sites
   per village and let the planner pick from it, rescoring lazily.
4. **Never scan unloaded chunks.** Already the rule on the arrival path; it applies here for
   the same reason.

## How the brain sees a site

Sites reach the LLM as description, not coordinates, the same way every other option does:

- *"a clear meadow east of the well"*
- *"a wooded rise north, three trees to fell"*
- *"a slope by the river, would need levelling"*

The planner has already filtered to sites within budget, so the model is choosing among legal
moves and cannot ask for the ravine. When there is no site, the brain hears that too, as the
room sentence above rather than as a silent gap in its options.

## Open questions

- **Does the preparation yield count toward the building's cost**, or just land in storage as
  ordinary income? Counting it makes forested sites feel cleverer; not counting it is simpler.
- **How is player-placed detected**, if at all. The `kithkyn:clearable` tag is a good
  approximation and needs no bookkeeping, but a player's dirt hut is made of clearable blocks.
- **Should tier 1 levelling be visible over time**, with the builder actually digging, or
  applied in the same block-by-block pass the structure already uses.
