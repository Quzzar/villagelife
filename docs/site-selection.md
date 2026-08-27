# Site selection and ground clearing

**Cost model implemented; preparation work not yet.** [village-tiers.md](village-tiers.md)
names space as a first-class build constraint and defers the design to its own doc. This
is that doc. It covers how a site is found, whether villagers may reshape the ground to
make one, and what that costs at runtime.

Implementation state: **the prepare phase is built** as of
[#69](https://github.com/Quzzar/villagelife/issues/69). `SitePreparation.planWork` returns
the actual positions to break and to fill, a project carries that work in its own persisted
queues, and the BUILDER performs it as the first phase of construction: one block per swing,
cleared blocks going into village storage rather than onto the ground, fill paid for out of
the village's own dirt. A site that needs work enters `PREPARING` and places nothing until
the ground is ready. When fill runs out the village emits the ordinary shortage event and
the builder says so in their own log, rather than spinning.

**Candidates are snapped to the real surface before scoring**, which is mitigation 1 below
and was for a long time simply absent. Sites used to be scored at the village centre's
elevation plus a blind vertical offset, and the levelling budget then rejected anything that
was not within about a block of the actual terrain — so in a live world every candidate came
back impossible and no village could build anything at all. Snapping also removes the
nine-elevation loop, so a search reads roughly a ninth of the blocks it used to, and
candidates in unloaded chunks are skipped before any scan rather than being scored as
impossible one at a time.

The planner now takes ground that needs work: a free site always wins, and otherwise the
cheapest preparable site is chosen. Still unbuilt from the sections below: the resumable
budgeted search, the site cache, and heightmap-first screening.

**Where a village looks.** Candidates are drawn from a square ring around the town centre.
The ring's outer edge grows with the village — one search radius, plus another for every
four buildings standing — but it never shrinks to nothing, and candidates always stand off
the centre rather than starting on it. Both halves of that rule were learned by breaking
them: a radius proportional to the building count alone is zero for a young village, so
every candidate lands on the town centre's own footprint, is skipped as claimed ground, and
the village decides there is nowhere to build. A candidate that does land on the centre is
pushed out along one axis only, so a building can sit due north or east of the fire instead
of only on the diagonals.

A village only searches while its ground is loaded. Nothing can be sited in a chunk nobody
is near, and asking the brain to choose would spend a model call on an answer no site search
could act on, so an unwatched village waits rather than planning.

Every search leaves one debug line saying what it saw: how many candidates were scored, how
many sat on claimed ground, how many were in unloaded chunks. A search that skips every
candidate before scoring is otherwise indistinguishable from one where every site was
genuinely bad, and that ambiguity hid the zero-radius bug above for as long as it existed.

Earlier slice: `SitePreparation.score` prices any candidate
footprint in blocks moved (clear, cut, fill, or impossible) using the
`villagelife:clearable` whitelist tag, the per-column and average levelling budgets below,
the block-entity and claim protections, and the never-scan-unloaded rule. The planner's
site search uses it gated on cost zero, so behavior is unchanged until the builder's
prepare phase exists; rejected candidates log their price at debug, and
`/vldev village score-site <pos> <sizeX> <sizeZ>` prints any site's bill, and
`/vldev village start-project <building> <pos>` begins a real project on ground you choose,
which is the only way to watch preparation run: a village's own search always prefers a free
site, so in ordinary terrain it never picks ground that needs clearing. The resumable
budgeted search, the site cache, and the prepare phase itself are later slices.

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

The budget for tier 1 is a per-column average, not a total: roughly 1.5 blocks of cut or fill
averaged across the footprint, with a per-column maximum of 3. A site needing more is not a
site. Fill consumes dirt or the local ground material from village storage, so levelling a
slope is a real expense the brain can weigh against building somewhere else.

The rule this encodes:

> **A village changes the surface of the land, never its shape.**

**That rule governs the surface, and only the surface** (decided on
[#54](https://github.com/Quzzar/villagelife/issues/54)). Underground is the miner's
business: a mine may sink shafts and drive tunnels as deep and as far as it likes, because
nobody is looking at the skyline from down there. One sentence covers both halves — do not
reshape what people see, dig what you like beneath it.

That line is the entire aesthetic difference between a settlement that grew into its terrain
and a player's flat dirt platform. It is also what keeps a village from eventually turning
its valley into a plateau over a hundred hours of unattended simulation.

### What may never be broken

Site preparation removes blocks. Getting this wrong once, on a player's house, poisons the
whole mod. The rule is a whitelist, not a blacklist:

- **Only blocks matching the `villagelife:clearable` block tag may be removed.** Natural
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
   volume-scan only the survivors. This is the difference between viable and not.
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
moves and cannot ask for the ravine.

## Open questions

- **Does the preparation yield count toward the building's cost**, or just land in storage as
  ordinary income? Counting it makes forested sites feel cleverer; not counting it is simpler.
- **Do roads and paths exist?** Once sites are scored, connecting them is the obvious next
  thing, and it is a different problem (linear, not footprint) that `wall` shares.
- **How is player-placed detected**, if at all. The `villagelife:clearable` tag is a good
  approximation and needs no bookkeeping, but a player's dirt hut is made of clearable blocks.
- **Should tier 1 levelling be visible over time**, with the builder actually digging, or
  applied in the same block-by-block pass the structure already uses.
