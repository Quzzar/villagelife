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
| **Idle cap** | At most N people may be idle at the campfire at once (Stronghold uses 24; ours is a config value, default much smaller). No new arrivals while the pool is full, no matter how much housing is free. |
| **Housing cap** | Total population (workers + idle) can never exceed total beds. The town center provides a few base beds; each house adds more. No beds free means no arrivals. |

Beds are assigned on arrival from `unassignedBeds`, independent of employment. Losing a bed
(house destroyed) does not despawn a person; it makes them homeless, which hurts
attractiveness (below) until rehoused.

## What drives inflow: attractiveness

Stronghold's "popularity" score, renamed **attractiveness** for us: a 0 to 100 score owned by
the village that answers "would anyone want to move here?" **Implemented** as
`VillageAttractiveness` (computed by `Village`, cached per tick, phase-staggered across
villages), inspectable in-game via `/villagelife attractiveness [pos]`.

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
- **Player misbehavior feeds the score through the hurt and death events; positive player
  standing deliberately does not** (decided after playtesting, superseding an earlier
  gift-event design). A thrown-item pickup is only a memory: the villager's personal log
  records what was picked up and who threw it, and whether that was a gift is the
  villager's own judgment, made in conversation, where it can move their personal opinion
  of the thrower (the conversation map owns that opinion tool). There is no village-wide
  positive standing, no gift event, and no Hero of the Village aura: in Aaron's words, no
  gift mechanism, just a mechanism to like someone more.
- Expected feel: a fed, housed village idles ~60-85 and steadily draws people; a single
  death dips without stalling growth; famine alone stalls; famine plus a massacre
  collapses below the decline threshold and people walk out.

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
persistent, unaffiliated person in the world (recruitment of wanderers by villages is a
future step).

## What drives outflow: jobs claim people

A workplace building finishing construction registers its work stations as open
`JobAssignment`s (this part already exists: `VillageBrain.processNewBuilding` fills
`unassignedJobs`). From there:

- An open job claims an idle person from the campfire pool automatically. The brain picks
  the **best-suited** camper: aptitude is a weighted sum over the genetics stat block, with
  per-occupation weights as datapack JSON (`data/villagelife/villagelife/aptitude/`); FIFO
  breaks ties, and unprofiled occupations stay effectively FIFO.
- A slow-tick **swap pass** reorganizes only when the improvement clears the configured
  threshold (default 3 points on the 3-18 scale): a markedly better idle candidate takes
  over a job (the displaced worker returns to the pool and remembers it in their personal
  log), or one beneficial two-worker exchange per pass. A per-person cooldown (default 2
  game days) prevents churn. Rules place; the journal narrates; the LLM never picks.
- The person walks from the campfire to the workplace, takes on the `Occupation` of the
  station, and holds it until the job stops existing.
- **Vacancy refills**: a worker dying or the building being removed puts the
  `JobAssignment` back in `unassignedJobs`, and the next idle person claims it. A building
  with no available worker just sits unstaffed until someone new arrives.
- **Job removal returns the person**: if the building is removed but the person survives,
  they return to the campfire pool and are immediately claimable by other open jobs.

Guards and any future military work the same way: recruiting consumes an idle person (plus
equipment). An empty campfire means no recruiting, which is the natural brake on
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
| Base beds | Beds the town center itself provides | 2 |

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
