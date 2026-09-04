# Village loading: keeping a village awake when no player is near

**Decided, and NOT implemented.** Nothing in this document exists in code yet. Today a
village does no physical work unless a player is standing in it; this design lets a village
keep building, mining, farming, and defending itself while unattended. Treat every
present-tense sentence below as intent.

## The two-speed village today

A village runs at two speeds, and only one of them survives the player walking away.

Every second, [`VillageManagerSaveData.tick`](../src/main/java/com/quzzar/kithkyn/savedata/VillageManagerSaveData.java)
calls `Village.update` for every village in the world, loaded or not. That call does the
village's **bookkeeping**: attractiveness, relationship drift, reflection, the marriage and
labor verdicts, tiering, the population math. All of it is cheap in-memory work that never
touches a block, and it runs whether or not the village's chunks are resident. This is the
rule recorded as "page in to act, not to decide": bookkeeping must never be what forces a
chunk to load.

Everything else is gated on the chunks actually being loaded. `checkCurrentProject` bails if
the town centre chunk is absent; the economy snapshot reads chests only while its market is
loaded and must never be the thing that loads them. And the villagers themselves are ordinary
Minecraft entities: when their chunks unload, they stop ticking entirely. No goals, no
pathfinding, no work. They sit frozen in the region file until a player brings the chunks
back.

So an unloaded village keeps *thinking* but does *nothing*. Leave for a week of real time and
come back to a village that has drifted a few opinions and decided, abstractly, that it would
like a farmhouse, but has not laid a single block. For a mod whose whole promise is villagers
that "build and develop their own villages," the development stops the moment you turn your
back.

The only chunk loading that exists today is transient: `Village.forceLoadCamp` pulls terrain
in for one founding operation and lets it fall away again. There is no persistent ticket
anywhere. This design adds one.

## What "loaded" means

A **loaded village** keeps a set of chunks resident and ticking so its whole simulation runs
with no player present. The set is computed from the village, not fixed, and it grows as the
village does. It is the union of:

1. **Every chunk any building's footprint touches.** The footprint is read from the village's
   own claim grid, one chunk per claimed column.
2. **A 2-chunk perimeter** around those, so edge pathfinding, incoming mobs, and a builder
   working the outer face all have room. The perimeter is also the village's growth headroom:
   a village extends onto the loaded ring, and the footprint expands as it builds.
3. **A 3x3 bubble around every village member**, wherever they roam. A member who leaves the
   footprint (the miner following a shaft past the perimeter, the lumberjack fetching a
   distant tree) carries their own small loading bubble: their chunk plus a one-chunk ring.
   The bubble evaporates the moment they step back inside the footprint, where it is
   redundant.

The member bubble is deliberately the general form of the old transient page-in load. Any
worker who outruns the settled area keeps just enough ground under them to keep working, and
nothing chases them with a permanent claim on the map.

**Roaming wanderers get no bubble.** A person who has left their village, or one the world is
carrying on the road beyond the horizon, is not a member and keeps nothing loaded. Only the
resident roster of a loaded village holds ground.

**Frozen members are woken.** A member who was roaming beyond the footprint when the village
last slept has no live position to bubble around, so the village remembers each resident's
last-known chunk while it ticks. On waking it seeds the bubble there, and the member thaws and
resumes rather than staying asleep off-map.

Every chunk in the set is held at **full entity ticking**, not merely loaded: villagers walk
and work, guards fight, and mobs spawn and path. A loaded village at night is a village in
full danger, defended by whoever it has, whether or not anyone is watching.

## The three modes

Village loading is one setting with three values. It is a world-level choice about how much of
the world stays awake.

- **Off.** No village tickets at all. Exactly today's behavior: a village runs its bookkeeping
  and freezes its world the moment the last player leaves.
- **All.** Every village that exists in the world is loaded, whether or not a player has ever
  seen it. Villages found by wandering settlers in unexplored regions grow on their own. This
  is the most faithful to the fantasy and the most expensive, and it scales with a number the
  player does not control.
- **Hybrid** (default). A village stays loaded while a player has stood in its chunks within
  the last six Minecraft days. Six days after the last visit with no return, it releases its
  ticket and goes dormant, back to bookkeeping-only. The grace window is a code constant, not
  a config field.

Hybrid is the default because it governs itself. The number of simultaneously loaded villages
is bounded by how a person actually plays: only the villages you have recently visited stay
awake, and six Minecraft days is about two hours of real time. Detection costs nothing, since
`Village.update` already runs every second for every village regardless of loading, and player
positions are always known: each tick the village asks whether any player sits inside its
footprint and, if so, stamps the visit. Loaded is then simply "last visit within the window."
The timer resets only on a **player** visit, never an NPC one, so a merchant passing through
cannot keep a village burning forever.

There is deliberately **no cap** on the number of loaded villages and no eviction. A cap's
only honest eviction policy is "unload the least-recently-visited," which is exactly what
Hybrid's window already does gradually and without surprising a player mid-build; and a cap on
All contradicts the one thing All promises. Hybrid self-limits, All is the opt-in cost, Off is
the escape hatch.

## What it changes, named honestly

**Villages develop while you are away.** This is the point. You return to buildings that went
up, fields that grew, shafts that deepened, and newcomers who arrived and were housed, instead
of a village frozen exactly as you left it.

**Cross-village movement stops being player-tethered.** Roaming families and couples,
recruited wanderers, and wandering merchants all currently carry "only near a player" caveats.
Villages that stay loaded let those handoffs happen at range.

**A village can be hurt while unattended.** Full entity ticking means hostile mobs spawn at
night in a loaded village, and its guards must actually defend it. A village can lose residents
to a raid nobody witnessed. Guard combat, death accounting, and the population floor all have
to hold up with no player loading assist, which is behavior the mod has never had to make work
unwatched.

**The LLM runs unattended.** A loaded village keeps making the build and labor decisions that
are model calls. On a cloud provider those are billed for moments no player sees; on the
offline model they are CPU the running game is also using. Hybrid bounds this to recently
visited villages; All does not.

**The loaded footprint grows, and so does the save.** A building village claims more chunks
over time, places more blocks, and grades more ground. This is inherent in letting a village
grow unattended and is accepted.

## Where it lives

- **Config.** One enum in the `general` group of
  [`KithkynConfig`](../src/main/java/com/quzzar/kithkyn/configuration/KithkynConfig.java):
  `VillageLoading` = `OFF` / `ALL` / `HYBRID`, default `HYBRID`, baked into a static mirror
  like every other value. The values live in
  [`VillageLoadingMode`](../src/main/java/com/quzzar/kithkyn/configuration/VillageLoadingMode.java).
  The six-day grace window is a constant on the loader, not a config field.
- **The ticket.**
  [`VillageChunkLoader`](../src/main/java/com/quzzar/kithkyn/village/VillageChunkLoader.java)
  owns one NeoForge `TicketController` (registered from the mod constructor), holding tickets
  keyed per village (a UUID derived from its id) and requested with ticking so entities run.
  On world load a validation callback drops every saved ticket, and the per-second reconcile
  rebuilds only what the active mode wants, so nothing stale survives a restart or a mode
  change.
- **The footprint.** `Village.desiredLoadedChunks` computes the wanted chunk set from the
  claim grid, the perimeter, and the members' bubbles, and `VillageChunkLoader.reconcile`
  diffs it against the tickets held and forces or unforces only the delta. It is empty when
  the village wants nothing, so an off or dormant village holds no chunks.
- **The visit clock.** `Village` persists `lastVisitedTick` (through the `LoadingState` codec
  record), stamped from `Village.update` whenever a player is inside the footprint, and read to
  decide a hybrid village's dormancy.
- **What stays.** Off mode keeps the current transient page-in loads for at-range actions
  unchanged; the bubble replaces them only while a village is loaded. Dormant villages keep
  running the same bookkeeping they run today.
