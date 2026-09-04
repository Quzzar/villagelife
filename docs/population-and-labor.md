# Population and labor: the campfire model

**This is the decided design for how villagers enter the village and get jobs.** The refactor
brings the codebase to this model. It is a direct adoption of the population mechanic from
the Stronghold games (Firefly Studios), adapted to kithkyn.

## The core principle: demand-driven labor

Nobody, not the player and not the village AI, ever assigns a specific person to a specific
job. The village creates *demand* (a workplace with an open station), and an idle-labor pool
satisfies it automatically. The simulation stays legible with zero micromanagement:

1. **Inflow**: new people arrive at the village and idle at the campfire.
2. **Reservoir**: they hang out there, uncommitted, until something claims them.
3. **Outflow**: an open job pulls a person from the pool; they walk to the workplace and
   become that worker.

## The campfire

The town center has a campfire gathering point. Every person who is not currently employed
belongs at the campfire: newcomers who just arrived, and workers whose job disappeared.

Idle people are not `NITWIT`s in the old sense (a permanent do-nothing occupation). Idle is a
*state*, not an occupation; the title a player sees on such a person is **Wanderer**
(`Occupation.WANDERER`), a wanderer of the village rather than an idler. Any idle person can
become any profession the moment a slot opens.
While idle, they stroll near the campfire, sit, chat and eat. The housed sleep in their own
beds; an idle person without a bed stays up in a tight huddle by the fire all night, drawing
closer than the daytime campfire crowd for safety (bedless campfire dozing
existed once, put villagers to sleep against the lit fire, glitched endlessly, and was
removed: nobody sleeps rough). They are civilians, and their reaction to danger comes from
personality rather than the Wanderer title. A resolute idle resident can fight back when struck;
a fearful one flees. They do not receive a free campfire-defense bonus or an unpaid hunting patrol.

The village fire offers a small refuge to every resident, not only Wanderers. A person below
one-third health and already within thirty blocks may walk up and interact with the lit center
campfire for ten seconds of Regeneration I, at most once per minute. Food remains the primary
recovery: a carried meal is eaten first, and someone without one tries the village stores before
the fire. The cooldown belongs to the person and survives saving and job changes.

Idle hands also tend the fire. An idle resident who finds raw food in the village stores takes
it to the campfire, cooks anything a campfire can cook (read from the vanilla recipe set, so
modded food joins in for free), and returns the cooked food to storage. It is the campfire twin
of the farmer's idle composter chain: a light, early-camp source of prepared food that needs no
building, and one that quietly matters less once a butchery exists to cook at scale
(`entities/ai/goals/work/CookStep`). It is the lowest-priority thing an idle person does, so
defence, eating and sleep always pull them off it.

Ground-item pickup is personal behavior, not labor. Every person, including every age,
occupation, roaming wanderer, and wandering merchant, occasionally looks for the nearest visible
dropped item within eight blocks and walks over to pocket it when their pack has room. The pickup
goal is deliberately low priority: work, village travel, danger, conversation, sleep, and other
purposeful movement all outrank it. Once a free person chooses an item, however, they continue the
short walk rather than abandoning it on the next random check. Awake people may tidy at night;
sleeping people do not wake for litter. Player-thrown pickups continue to create only a personal
memory, never an automatic gift or village-attractiveness event.

## What caps the reservoir

Two independent caps, checked at arrival time:

| Cap | Rule |
| --- | --- |
| **Idle cap** | At most N people may be idle at the campfire at once (Stronghold uses 24; ours is per-tier, owned by [village-tiers.md](village-tiers.md)'s `idle_cap`, with a config fallback of 2 when no ladder is loaded). No new arrivals while the pool is full, no matter how much housing is free. |
| **Housing cap** | Total population may exceed total beds by up to the idle cap, and no further: the campfire reservoir is exactly where bedless newcomers wait. The village center provides the starting beds; each house adds more. |

Beds are assigned on arrival, ahead of any employment. Houses and the
village centre are the main sources of beds, and some workplaces carry a live-in bed as well
(the lumberjack hut, the watchtower, the upper blacksmith, the church), superseding the
older no-workplace-beds reading of [#61](https://github.com/Quzzar/kithkyn/issues/61).

A workplace's live-in bed is **reserved for whoever staffs that workplace**, not thrown into
general housing: a bed whose building carries a work station and is not the village centre is
held back, so a newcomer or an idle camper is never handed the empty lumberjack hut's only
bed for a plain night's sleep (`Village.isReservedWorkplaceBed`, `takeGeneralBed`). Without
this the one bed that breaks the founding wood deadlock (a house costs logs, logs need a
lumberjack, a lumberjack needs a bed) gets slept in by someone who is not the lumberjack, and
the post can never be filled. When a worker is assigned to a building with a free bed they
move in (`Village.preferWorkplaceBed`), releasing whatever bed they held; a worker whose
workplace has no live-in bed is housed from general housing at assignment, so an employed
person always has a bed. A general bed follows its resident when their job changes, so commutes
across a village are normal. A reserved workplace bed does not: leaving that workplace releases
its bed for the next person who staffs it. The per-tick reconciliation repairs older saves where
a former worker still occupies another trade's live-in bed.

The claiming and swap gates read this correctly: a bedless camper is claimable for a post when
the village can house them *for that post*, meaning a free general bed or a free bed in that
post's own building, not a free bed reserved to some other workplace
(`Village.hasFreeGeneralBed`/`hasFreeBedIn`, `JobClaiming`).

**Employment requires nighttime accommodation** (expanded 2026-09-03). An Adult worker needs
an assigned bed. A Teenager may work while dependently housed in a resident parent's home;
Toddler and Kid cannot work. Claiming and the swap pass reject everyone outside those rules,
and a worker whose accommodation is gone with no replacement stands down, their post reopening
for someone housed (`JobClaiming.releaseUnhousedWorkers`). An unhoused Adult is part of the
campfire reservoir: they idle by the fire, take no work, and do not sleep until the village
builds them a home, which is what makes housing a genuine construction need. The planner does not score this
into a decision: it states the facts in the brain's briefing and lets the model weigh them.
Those facts include how many idle people have nowhere to sleep, which buildings already stand,
which professions their open posts belong to, and that a workshop without beds does not house
the people waiting at the fire. The model still chooses whether that makes a home the right next
project. An employed Teenager without a distinct future adult bed also creates a persisted
save-for goal for an ordinary house, so the village prepares for the moment dependent housing
expires. Losing a bed
(house destroyed) still does not despawn a person; it makes them homeless, which hurts
attractiveness (below) and idles them until rehoused.

## What drives inflow: attractiveness

Stronghold's "popularity" score, renamed **attractiveness** for us: a 0 to 100 score owned by
the village that answers "would anyone want to move here?" **Implemented** as
`VillageAttractiveness` (computed by `Village`, recomputed every 10 seconds and cached between, phase-staggered across
villages), inspectable in-game via `/kkdev village attractiveness [pos]`.

- **Above the grow threshold (50)**: new people periodically arrive. The further above, the
  more frequent the arrivals.
- **Below the decline threshold (25)**: people leave, idle campfire people first, then
  employed people abandon their jobs and go, but never past the **population floor** (4,
  `Minimum village population`): a village never empties itself, and below the floor the
  unhappy stay put however low the score reads. Between the thresholds, population holds.

The **population floor is two-sided** (Aaron, 2026-09-03). It is not only the line below which
nobody leaves; a village pushed *under* it, a bad night of mob deaths on Hard, must climb back
to it whatever its mood or means. So a village below the floor is always in a growing state: it
draws one newcomer per population check regardless of attractiveness, and the forced refill
ignores the caps and even the cold-ledger hold, since the floor check that fires it already
counts walkers and stops at the floor. This is the growth-side mirror of the no-emigration
rule, and it means deaths can no longer strand a settlement below its floor with nobody coming
(a village mauled to two by skeletons that then sat empty, because the deaths had dropped its
attractiveness out of the growing band). Above the floor, growth is attractiveness-gated as
usual.

The decided v1 formula — every number a config tunable in `kithkyn-common.toml`,
clamped to 0-100:

| Component | Contribution |
| --- | --- |
| Base | 50 |
| Food per capita in village containers | up to **+25**, full marks at 8 edible items per head |
| Free beds (headroom) | up to **+10**, full marks at 2 free |
| Homeless fraction | up to **-20** |
| Each death (`DeathBookkeepingEvent`) | **-8 x** its decaying impact |
| Each hurt-by-player (`HurtByPlayerBookkeepingEvent`) | **-3 x** impact |
| Each witnessed theft (`TheftBookkeepingEvent`) | **-1 x** impact |
| Each resource shortage (`NoResourceBookkeepingEvent`) | **-2 x** impact |

Mechanism notes:

- Event penalties ride the bookkeeper's impact decay (1%/10s, forgotten below 0.01): a
  death hurts hard for ~10 minutes and fades from memory in about an hour; a massacre
  stacks. The village remembers, then forgives.
- Shortage events carry the missing item and count. They are emitted when the planner
  can't afford any project (rate-limited by a config cooldown so a poor village complains
  steadily, not constantly), when paying for a building comes up short, and when a guard
  turns in for the night with no rations.
- **Wrongdoing is witnessed, or it did not happen** (decided on
  [#64](https://github.com/Quzzar/kithkyn/issues/64)). Theft, assault and murder all
  work the same way: a villager must actually see it — awake, within roughly sixteen
  blocks, with line of sight. Steal from a chest with nobody around, or kill someone alone
  in the woods, and the village genuinely does not know: it simply has someone missing.
  This is deliberately ruthless consistency rather than a special case for violence.
  (Deaths that the world causes are unaffected: a villager taken by a zombie is discovered
  as usual. The witness rule is about blame, not about mortality.)
- **The village's mood moves by fixed weights; what a person thinks of you is judged.** A
  witnessed offence emits the ordinary bookkeeping event, which costs attractiveness on a
  set scale — killing worst, assault next, theft least. Separately, each witness records
  what they saw as a fact and decides for themselves what it meant, which lands on their
  personal opinion of you. Two mechanisms that already exist, each doing what it is good
  at, and it means the same crime can leave one villager unforgiving and another shrugging.
- **Standing escalates all the way to outlawry.** Prices rise as a village sours on you and
  trade eventually closes, and past that the village turns against you outright: residents
  flee rather than talk, and guards treat you as an enemy. Being run out of a settlement you
  robbed is a legitimate end state.
- **Everything fades, including murder.** Village mood decays on the bookkeeper's existing
  schedule, and a player's personal standing with individual villagers drifts back toward
  neutral over time as well. Consequences are real but never permanent: stay away, behave,
  and a village will take you back. There is always a road back from outlaw.
- **Positive player standing deliberately does not touch attractiveness.** A thrown-item
  pickup is only a memory: the villager's personal log records what was picked up and who
  threw it, and whether that was a gift is the villager's own judgment, made in
  conversation. No village-wide positive standing, no gift event: in Aaron's words, no gift
  mechanism, just a mechanism to like someone more.
- Expected feel: a fed, housed village idles ~60-85 and steadily draws people; a single
  death dips without stalling growth; famine alone stalls; famine plus a massacre
  collapses below the decline threshold and people walk out.

**The score must never be confidently wrong.** `checkPopulation` acts on a single sample
with no hysteresis: one reading below the decline threshold emigrates a person, and no
later correction brings them back. So every input to attractiveness carries an obligation
that the rest of the codebase does not — it may be *stale*, but it may not be *low for the
wrong reason*. A pass that cannot see what it is counting must say so or report what it
last knew, never zero.

This bit us for real (#65). The food count opened every village container every ten
seconds, and `getBlockEntity` on a `ServerLevel` loads the chunk synchronously off disk
when it is not resident: villages nobody was near were paging their own storage in from
disk six times a minute to answer a question about mood. It was 96% of the entire village
tick — 684 ms per minute across 22 villages, against 4.4 ms for every job and traveler
pass combined.

The obvious fix is the trap. Guarding on `hasChunkAt` and skipping what you cannot read is
*worse than the spike*: an unseen chest counts as empty, food per capita goes to zero, the
food component collapses, and villagers emigrate out of a well-stocked village because
nobody happened to be standing in it. The bug is silent, it looks like famine, and nothing
downstream can distinguish it from real famine. A performance fix wearing a correctness
bug's clothing.

What we do instead: **the village keeps books.** A container it can see is counted and the
figure recorded against that position; a container it cannot see reports what it held when
anyone last looked. Exact whenever the village is observed, merely stale when it is not,
and self-correcting on every visit. Between server boot and the first time any of its
storage is resident the ledger is genuinely empty, and a village in that window holds
rather than deciding at all — `VillageBrain.hasReadStores`.

Measured over the same one-minute window at 22 villages, before and after: the
attractiveness pass went from **684.2 ms to 0.3 ms**, its worst single call from 11.75 ms
to 0.01 ms, and server tick P99 from **43.8 ms to 3.8 ms** against a 50 ms budget.

Anyone adding a new input to attractiveness inherits this rule. The next person to find a
synchronous chunk load in a tick loop will reach for the guard first; the guard is only
half the fix.

Arrival itself (**implemented**): on a periodic, per-village phase-staggered check, if
attractiveness clears the grow threshold and both caps have room (counting people still
mid-walk), the persona pipeline runs first — generate-before-spawn, a failed generation
skips the arrival — then the newcomer spawns at the village edge on the surface and walks
to the campfire (`VillageTravelGoal`). They only count as population, and only take a bed,
once they arrive; walkers persist across restarts as pending travelers, with a timeout
that snaps stragglers to the fire. Unloaded edge chunks quietly skip the cycle. Founding
works the same way: a new village spawns nobody — its first residents walk in.

Emigration (**implemented**): while the score sits below the decline threshold, one person
per check gives up, idle people first, then the employed. Their assignments free exactly
as death frees them and they walk to the village edge. At the edge, whatever they were,
they become a **wanderer**: the title changes there, the job's kit (hands and armour) stays
with the village, and they keep their pack. There is nothing else to pack: whatever they kept
in a chest of their own stays with the house, for whoever moves in next. Then they take to
the road. The floor gates emigration here: a village at or below it loses nobody to mood,
whatever the score reads, so a founding camp of four is never emptied by its own first hungry
morning (the floor's other, growth-side half is above: below the floor the village force-grows
back to it). `/kkdev village emigrate` ignores the floor, so the road can still be watched from
a small village.

An emigrating adult takes their resident spouse and dependent children. The floor is tested
against the complete departure group, so no spouse or child is stranded to satisfy a one-person
calculation. Dependents travel without consuming a bed or pre-work idle slot when another village
considers admitting the household. If a child's parents die instead, the child remains an unhoused
Wanderer and grows normally; adoption is not part of this version. See
[families.md](families.md).

The road (**implemented**, reshaped 2026-09-02): at the edge a leaver becomes a **roaming
wanderer**, a real person with no village (`RealPerson.isRoamingWanderer`), and stays one
until a village takes them in. They walk a heading every day: the day they leave, straight
away from the village; every dawn after, a fresh one, aimless by design, leg by leg and
turning when the ground blocks them (`RoamGoal`). Nobody sleeps rough, but a wanderer carrying three
logs camps for the night: at dusk a fire of their own goes down beside them, out of the pack
(three logs, sticks and coal waived like every road recipe), whatever they carry raw is roasted
on it through the same tending the idle camper uses at the village fire (`CampfireRoast`), and
they sit beside it until dawn, when the fire is put out and left where it stood, nothing of it
back in the pack, and the walk goes on (`CampStep`). One with fewer logs walks the night
through. They still eat from the pack when hurt, and scatter from monsters like anyone else. They live off the land as they go, at the work loops' priority so a find outranks the
walk: game met within a dozen blocks is taken with whatever is in hand while the pack holds
fewer than four bites (`ForageHuntStep`, the hunter's own rule that farmed stock is never
game), a tree by the road is brought down whole, bare-handed and slowly or quicker with an
axe, while the pack holds fewer than sixteen logs, a reserve of fires (`ForageChopStep`, the
shared `TreeFelling`,
so nothing anyone placed comes down), and a wanderer with an empty hand and a log makes
themselves a wooden axe out of it, three planks with a log standing in at the recipes' rate,
through the same recipe path and best-tier-first order a village's bedtime tool-making uses
(`JobTool.makeFromPack`). Nothing is conjured on the road: the axe comes out of felled logs,
the meat out of the animal, and the drops are pocketed standing over it. Nobody vanishes for
walking, either: a wanderer who walks out of the ticking world freezes where they stand,
like every other entity, and resumes when it comes back. Two earlier designs crossed them
into the pool below at a fixed distance, then at the edge of the loaded world, and both put
the crossing where a player could watch a wanderer blink out, or never saw the walk at all as
chunks came and went (Aaron, 2026-09-02). **Beyond the horizon** is now only what happens at
the village edge past the wanderer cap: the whole person is saved into the server-wide
`WandererPool` (persona, memories, pack, everything) and the entity is discarded.

Recruitment (**implemented**): a growing village that rolls an arrival fills it in this
order, and only the last step conjures anyone: a loaded wanderer within the recruit radius
(they walk in from wherever they are, pack, axe and all), then the person longest on the road beyond the
horizon (restored at the village edge and walking in, stats and memories intact), then a
new persona. So the same souls circulate between settlements however far apart they stand,
and a village that collapses seeds the ones that grow. Two caps bound this: the wanderer
cap on people walking the loaded world (past it a leaver passes beyond the horizon straight
from the edge) and the pool cap on the road itself (past it the longest-gone is forgotten).
Orphans self-heal: a person pointing at a village whose roster dropped them becomes a
wanderer on a slow tick and takes to the road from where they stand.

## What drives outflow: jobs claim people

A workplace building finishing construction registers its work stations as open
`JobAssignment`s (this part already exists: `VillageBrain.processNewBuilding` fills
`unassignedJobs`). From there:

- Open posts are filled each trade once before any trade is doubled: the first open post for
  an occupation nobody holds goes first, else the first in registration order
  (`JobClaiming.nextOpening`). The town centre registers three builder posts at founding
  (worker-loops.md), but the second and third open only with population, one more per six
  people (`Village.PEOPLE_PER_BUILDER`); a locked post is not claimable and is not counted as
  open. Without both rules a camp's first hires would be three builders.
- An open job claims a **housed** idle person from the campfire pool automatically (the
  employment-requires-housing rule above; a bedless camper is not claimable). Aptitude is a
  weighted sum over the genetics stat block, with per-occupation weights as datapack JSON
  (`data/kithkyn/kithkyn/aptitude/`); FIFO breaks ties, and unprofiled occupations
  stay effectively FIFO.
- **The rules gate to competence; the model picks within it.** When one camper is clearly
  best suited, they take the post on the spot, no model call spent. When two or more are
  near-equally suited (within `PICK_DELTA`, 2 points on the 3-18 scale) and the brain is
  ready, those near-equals are offered to `decide()`, which picks among them on character
  (each is described by name and persona blurb) and gives its reason. Competence is never
  traded away: the shortlist is only ever the near-equally suited, so the character pick
  cannot seat someone materially worse. An absent, slow, or unusable answer falls straight
  to the aptitude best, and one such decision is in flight per village at a time (the
  project planner's discipline, `JobClaiming` + `Village.jobDecisionPending`). This is the
  first job-facing consumer of the LLM brain; see [llm-brain.md](llm-brain.md).
- A slow-tick **swap pass** reorganizes only when the improvement clears the configured
  threshold (default 3 points on the 3-18 scale): a markedly better housed idle candidate
  takes over a job (the displaced worker returns to the pool and remembers it in their personal
  log), or one beneficial two-worker exchange per pass. A per-person cooldown (default 2
  game days) prevents churn. The swap pass stays **purely rule-based**: reorganization is a
  mechanical aptitude optimization, and only the initial claim of a contested post is
  handed to the model.
- **Reprioritizing to a shortage** (`LaborPlanner`). Claiming and the swap pass both work
  from the idle pool; neither ever takes a settled worker off a job the village can spare and
  moves them to one it cannot. So a village that raised a farm but never grew a farmer starves
  beside it, and the starving is itself what stops it drawing the newcomer who would farm: a
  deadlock it cannot break from inside. On a slow tick, then, a village that is **short of
  food** (stored food below the per-capita target that `VillageAttractiveness` reads) with a
  **food post open** (farmer, fisher, or hunter), its building standing, and **no one idle**
  to take it, asks the brain who to move onto the field, or whether to leave the crew be. The
  facts are laid out and the model chooses, the same way it picks a build; competence is not
  consulted here, because need, not aptitude, is the point. Only loaded workers can be moved
  (a reassignment rebuilds their goals in the world), one decision is in flight per village
  (`Village.laborDecisionPending`), and a brain that leaves the crew as it is sits the
  question out a while before it is asked again. **The last builder is off the table while a
  build is in progress** (2026-09-03): the crew offered to the field never includes a village's
  only builder when a construction project stands, since moving them leaves no one to finish it,
  the build stalls, and the village sits stuck on it, never advancing to houses (a farm that
  hung half-built with its builder gone to the field, while every villager's briefing kept
  saying "building a farm"). A second builder, if there is one, may still move.
- The person walks from the campfire to the workplace, takes on the `Occupation` of the
  station, and holds it until the job stops existing. Taking the job is the one moment a
  bare starting kit appears from nothing: the mark of the trade, a stone axe, sword, pickaxe or
  hoe, a plain bow, or a crossbow for the trades that work with a tool, or a token for the rest, a builder's crafting
  table, a quartermaster's ledger, a blacksmith's ingot (`RealPerson.issueStartingKit`, the tokens
  in `entities/SignatureGear`). Nothing is conjured after that; a worker may give a mark away like
  anything else, and by day draws it back into hand from pack or stores when it has strayed, and a
  tool given away or lost is also replaced at bedtime from the stores or made from real materials
  ([worker-loops.md](worker-loops.md), "Nothing is conjured after the starting kit").
- **Vacancy refills**: a worker dying or the building being removed puts the
  `JobAssignment` back in `unassignedJobs`, and the next idle person claims it. A building
  with no available worker just sits unstaffed until someone new arrives.
- **Job removal returns the person**: if the building is removed but the person survives,
  they return to the campfire pool and are immediately claimable by other open jobs.

Guards and any future military work the same way: recruiting consumes an idle person.
Equipment is not consumed at recruitment beyond that bare kit; better gear is drawn from the
village's own stores at bedtime, and a lost tool is made from what the stores hold, the best
tier they have the makings of, never conjured.
An empty campfire means no recruiting, which is the natural brake on
militarizing a starving village.

## The loop, in one paragraph

Attractiveness governs inflow. The idle cap and the housing cap govern reservoir size.
Demand (open jobs) governs outflow. The village grows by building houses (raises the
housing cap and unlocks employment, since only the housed may hold jobs) and workplaces
(creates demand), and stays healthy by keeping people fed and safe (keeps attractiveness
above threshold so the pool refills).

## Implementation map

The campfire model is the current code. Key locations:

- Arrival and emigration: `Village` (the campfire loop; arrivals come in through
  `PersonaSpawner`, so every villager has a persona by construction; see
  [personas.md](personas.md)).
- The road: `RoamGoal` (the daily walk), `ForageHuntStep` and `ForageChopStep` (living off
  the land), `CampStep` (the night's fire), `JobTool.makeFromPack` (the road's axe), `RealPerson.crossHorizon` (the crossing
  at the edge past the cap), and `WandererPool` in `VillageManagerSaveData` (everyone beyond
  the horizon, one list for the server). `/kkdev village emigrate` sends one person out of the
  nearest village to watch it, and `/kkdev village wanderers` lists who is beyond the horizon.
- Attractiveness: `VillageAttractiveness`.
- Idle pool: derived, never stored (`Village.idlePeople()`: work-eligible population minus employed
  minus mid-walk travelers). Toddler and Kid are not labor; an idle Teenager is. Idle behavior
  anchors to the `kithkyn:campfire` POI.
- Job claiming and swaps: `JobClaiming.tick`, called every second from `Village.update`:
  aptitude-based claiming (`JobAptitudes` + `JobAptitudeLoader` datapack profiles), a
  visible commute, reconciliation passes that return orphaned workers to the pool and
  stand down workers left without a bed, and
  the threshold-gated swap pass on a phase-staggered slow tick (cooldowns persisted in the
  brain's strategy tag). Tunables: `Job swap threshold / interval / cooldown` in config.
- `Occupation.WANDERER` is the idle state's title. It replaced `IDLE` (and before that
  `NITWIT`) with a world wipe rather than a migration: saves under the old names do not load.

## Tunables

All of these belong in config, not constants buried in `Village`:

| Tunable | Meaning | Starting point |
| --- | --- | --- |
| Idle cap | Max people at the campfire | Owned per-tier by [village-tiers.md](village-tiers.md) (`idle_cap`), not global config |
| Arrival check interval | How often inflow is evaluated | 100 s |
| Attractiveness threshold | Score above which people arrive | 50 |
| Emigration threshold | Score below which people leave | 25 |
| Minimum village population | Two-sided floor: emigration never drops below it, and a village below it force-grows back up to it | 4 |
| Wanderer recruit radius | How far a growing village looks for a loaded wanderer | 128 |
| Wanderer cap | Wanderers walking the loaded world at once | 8 |
| Wanderer pool cap | People the world remembers on the road | 64 |
| Base beds | Beds the village center itself provides | 4, from its building definition, not config |
| Days per child stage | Time spent as Toddler, Kid, and Teenager | 8 days each |
| First family talk delay | Days an eligible housed couple waits before first discussing a child | 1 day |
| Family talk retry | Days after no decision or "not now" before asking again | 4 days |
| Family birth cooldown | Days after a successful birth before asking again | 8 days |
| Twin birth chance | Chance an agreed birth produces identical twins | 3% |
| Triplet birth chance | Chance an agreed birth produces identical triplets | 0.25% |

## Open questions (not yet decided)

Earlier entries here are now decided and described above: positive player standing lives
in per-villager opinion shaped in conversation, never in attractiveness; emigrants become
wanderers, take to the road, and come back in at whichever village grows next; stat-based
job matching with threshold-gated swaps replaced pure FIFO (FIFO remains the tiebreaker). Villages are also named at founding:
the LLM names the settlement from its biome and natural terrain on the low-priority queue.
Temporary founding structures such as the gathering-point campfire are not part of the naming
context. The name is
requested before the camp is placed and founding waits the moment it takes to land, so
a village only ever has one name (a word-list name stands only if generation fails
twice); the name is permanent, with no rename mechanism by decision.

- Wanderer-FOUNDED camps stay deferred until site selection can support them.
- Relationships do not travel: a village drops a leaver's pairs from its brain when they go,
  and the village that takes them in generates fresh ones. Memories and chat history ride on
  the entity and do travel.
