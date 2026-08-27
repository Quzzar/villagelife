# Population and labor: the campfire model

**This is the decided design for how villagers enter the village and get jobs.** The refactor
brings the codebase to this model. It is a direct adoption of the population mechanic from
the Stronghold games (Firefly Studios), adapted to villagelife.

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
*state*, not an occupation. Any idle person can become any profession the moment a slot opens.
While idle, they stroll near the campfire, sit, chat, eat, and sleep like anyone else.

## What caps the reservoir

Two independent caps, checked at arrival time:

| Cap | Rule |
| --- | --- |
| **Idle cap** | At most N people may be idle at the campfire at once (Stronghold uses 24; ours is per-tier, owned by [village-tiers.md](village-tiers.md)'s `idle_cap`, with a config fallback of 2 when no ladder is loaded). No new arrivals while the pool is full, no matter how much housing is free. |
| **Housing cap** | Total population may exceed total beds by up to the idle cap, and no further: the campfire reservoir is exactly where bedless newcomers wait. The village center provides the starting beds; each house adds more. |

Beds are assigned on arrival from `unassignedBeds`, independent of employment — which is
coherent because **no workplace carries a bed** ([#61](https://github.com/Quzzar/villagelife/issues/61));
houses and the village centre are the only sources of beds. A villager keeps the first free
bed they are given and does not move when their job changes, so commutes across a village
are normal and expected. Losing a bed
(house destroyed) does not despawn a person; it makes them homeless, which hurts
attractiveness (below) until rehoused.

## What drives inflow: attractiveness

Stronghold's "popularity" score, renamed **attractiveness** for us: a 0 to 100 score owned by
the village that answers "would anyone want to move here?" **Implemented** as
`VillageAttractiveness` (computed by `Village`, recomputed every 10 seconds and cached between, phase-staggered across
villages), inspectable in-game via `/vldev village attractiveness [pos]`.

- **Above the grow threshold (50)**: new people periodically arrive. The further above, the
  more frequent the arrivals.
- **Below the decline threshold (25)**: people leave — idle campfire people first, then
  employed people abandon their jobs and go. Between the thresholds, population holds.

The decided v1 formula — every number a config tunable in `villagelife-common.toml`,
clamped to 0-100:

| Component | Contribution |
| --- | --- |
| Base | 50 |
| Food per capita in village containers | up to **+25**, full marks at 8 edible items per head |
| Free beds (headroom) | up to **+10**, full marks at 2 free |
| Homeless fraction | up to **-20** |
| Each death (`DeathBookkeepingEvent`) | **-8 x** its decaying impact |
| Each hurt-by-player (`HurtByPlayerBookkeepingEvent`) | **-3 x** impact |
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
  [#64](https://github.com/Quzzar/villagelife/issues/64)). Theft, assault and murder all
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
per check gives up — idle people first, then the employed. Their assignments free exactly
as death frees them; they walk to the village edge and leave as a **wanderer** — a
persistent, unaffiliated person in the world. Growing villages recruit these wanderers
before spawning anyone new, so the same people circulate between settlements.

## What drives outflow: jobs claim people

A workplace building finishing construction registers its work stations as open
`JobAssignment`s (this part already exists: `VillageBrain.processNewBuilding` fills
`unassignedJobs`). From there:

- An open job claims an idle person from the campfire pool automatically. Aptitude is a
  weighted sum over the genetics stat block, with per-occupation weights as datapack JSON
  (`data/villagelife/villagelife/aptitude/`); FIFO breaks ties, and unprofiled occupations
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
  threshold (default 3 points on the 3-18 scale): a markedly better idle candidate takes
  over a job (the displaced worker returns to the pool and remembers it in their personal
  log), or one beneficial two-worker exchange per pass. A per-person cooldown (default 2
  game days) prevents churn. The swap pass stays **purely rule-based**: reorganization is a
  mechanical aptitude optimization, and only the initial claim of a contested post is
  handed to the model.
- The person walks from the campfire to the workplace, takes on the `Occupation` of the
  station, and holds it until the job stops existing.
- **Vacancy refills**: a worker dying or the building being removed puts the
  `JobAssignment` back in `unassignedJobs`, and the next idle person claims it. A building
  with no available worker just sits unstaffed until someone new arrives.
- **Job removal returns the person**: if the building is removed but the person survives,
  they return to the campfire pool and are immediately claimable by other open jobs.

Guards and any future military work the same way: recruiting consumes an idle person.
Equipment is not consumed at recruitment; gear is scavenged opportunistically afterwards.
An empty campfire means no recruiting, which is the natural brake on
militarizing a starving village.

## The loop, in one paragraph

Attractiveness governs inflow. The idle cap and the housing cap govern reservoir size.
Demand (open jobs) governs outflow. The village grows by building houses (raises the
housing cap) and workplaces (creates demand), and stays healthy by keeping people fed and
safe (keeps attractiveness above threshold so the pool refills).

## Implementation map

The campfire model is the current code. Key locations:

- Arrival and emigration: `Village` (the campfire loop; arrivals come in through
  `PersonaSpawner`, so every villager has a persona by construction; see
  [personas.md](personas.md)).
- Attractiveness: `VillageAttractiveness`.
- Idle pool: derived, never stored (`Village.idlePeople()`: population minus employed
  minus mid-walk travelers). Idle behavior anchors to the `villagelife:campfire` POI.
- Job claiming and swaps: `JobClaiming.tick`, called every second from `Village.update`:
  aptitude-based claiming (`JobAptitudes` + `JobAptitudeLoader` datapack profiles), a
  visible commute, a reconciliation pass that returns orphaned workers to the pool, and
  the threshold-gated swap pass on a phase-staggered slow tick (cooldowns persisted in the
  brain's strategy tag). Tunables: `Job swap threshold / interval / cooldown` in config.
- `Occupation.IDLE` replaces NITWIT (kept only as a deprecated alias so old saves decode).

## Tunables

All of these belong in config, not constants buried in `Village`:

| Tunable | Meaning | Starting point |
| --- | --- | --- |
| Idle cap | Max people at the campfire | Owned per-tier by [village-tiers.md](village-tiers.md) (`idle_cap`), not global config |
| Arrival check interval | How often inflow is evaluated | 100 s |
| Attractiveness threshold | Score above which people arrive | 50 |
| Emigration threshold | Score below which people leave | 25 |
| Base beds | Beds the village center itself provides | 4, from its building definition, not config |

## Open questions (not yet decided)

Earlier entries here are now decided and described above: positive player standing lives
in per-villager opinion shaped in conversation, never in attractiveness; emigrants
persist in the world as wanderers; stat-based job matching with threshold-gated swaps
replaced pure FIFO (FIFO remains the tiebreaker). Villages are also named at founding:
the LLM names the settlement from its biome on the low-priority queue (a deterministic
word-list name stands in first and survives if generation fails twice); the name is
permanent, with no rename mechanism by decision.

- ~~Whether a wanderer can later join another village~~ — decided and **implemented**:
  wanderers are a recruitment pool. A growing village that rolls an arrival first looks
  for a loaded wanderer within a config radius and recruits them (they walk in exactly
  like a fresh arrival, keeping their stats, memories, and relationships) before any new
  persona is spawned, so the same souls circulate between villages. A config cap bounds
  loaded wanderers: past it, an emigrant finishing their walk-out moves on beyond the
  horizon instead of lingering. Orphans self-heal: a person pointing at a village whose
  roster dropped them (the old half-departed gap) quietly becomes a wanderer on a slow
  tick. Wanderer-FOUNDED camps stay deferred until site selection can support them.
- What wanderers actively do while roaming (today: default idle goals, no destination).
