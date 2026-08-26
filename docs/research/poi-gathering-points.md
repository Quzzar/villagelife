# POI types and gathering points on NeoForge 1.21.1

Research for the campfire gathering point (issue #11). Everything below was read from the
actual game code this project compiles against: the mojmap sources in
`build/moddev/artifacts/neoforge-21.1.72-minecraft-sources.jar` (NeoForge 21.1.72 /
Minecraft 1.21.1, NeoForge patches included). Class and method names are as they exist in
that jar. The one secondary source is
[docs.neoforged.net 1.21.1, Registries](https://docs.neoforged.net/docs/1.21.1/concepts/registries/)
for the `DeferredRegister` idiom, cross-checked against `DeferredRegister.java` in the same jar.

## TL;DR and recommendation

**Register a custom POI type (`villagelife:campfire`) over the vanilla campfire's
blockstates. Do not reuse `minecraft:meeting`. Goal-based claiming is viable and clean —
the POI system has no dependency on the Brain system — but the mob then owns all the
bookkeeping the villager class does (release on death, revalidate on use, give up on
unreachable).**

- `minecraft:meeting` is hard-bound to bell blockstates (`PoiTypes.bootstrap`), and a
  registered `PoiType`'s state set is immutable — the only way to "reuse" it is to put an
  actual bell at the town center, which drags in vanilla semantics (vanilla villagers
  compete for the same 32 tickets, raid/bell behaviors attach, `#minecraft:village`
  tagging). Wrong block, shared ledger, no control.
- A custom POI over campfire states is ~15 lines with NeoForge's `DeferredRegister`;
  NeoForge auto-wires the blockstate→POI map at registration (`NeoForgeRegistryCallbacks.PoiTypeCallbacks`),
  so world tracking, persistence, and automatic add/remove on block place/break all come free.
- Plain coordinates (Village object stores a `BlockPos`) would also work since `Village`
  already owns population state — but then block breaking, chunk persistence, and "find the
  gathering point near X" are all hand-rolled. The POI index gives those for free and is the
  vanilla-native answer. Middle ground is legitimate: use the POI as the spatial index and
  let `Village` own the roster, ignoring tickets (see §5).
- Suggested parameters: `maxTickets = 32` (matches `minecraft:meeting`; the idle cap should
  be enforced by Village logic, not tickets — `maxTickets` is frozen at registry time and
  can't follow a server config), `validRange = 6` (matches meeting; it is the pathfinding
  "close enough" radius, not a query radius).

---

## 1. Registering a custom POI type on NeoForge 1.21.1

### The PoiType record

`net.minecraft.world.entity.ai.village.poi.PoiType` is a plain record:

```java
public record PoiType(Set<BlockState> matchingStates, int maxTickets, int validRange)
```

- **`matchingStates`** — the exact `BlockState`s that count as this POI. Matching is
  set-membership (`PoiType.is(BlockState)` → `matchingStates.contains(state)`), so every
  property permutation you care about must be enumerated. Vanilla's idiom
  (`PoiTypes.getBlockStates`) is `ImmutableSet.copyOf(block.getStateDefinition().getPossibleStates())`,
  optionally filtered — beds only include `PART=HEAD` states. The canonical constructor
  `Set.copyOf`s the set, so it is immutable after construction.
- **`maxTickets`** — the claim capacity of *each placed instance* of this POI. Every
  `PoiRecord` starts with `freeTickets = maxTickets`; `PoiManager.take` decrements one.
  Vanilla: all job sites and `home` are 1; `meeting` is **32**; `beehive`, `bee_nest`,
  `nether_portal`, `lodestone`, `lightning_rod` are **0** — a 0-ticket type can never be
  `take`n (the `HAS_SPACE` filter never passes) and is a pure "locator" POI used only for
  queries.
- **`validRange`** — *not* a search radius. Its only vanilla use is
  `AcquirePoi.findPathToPois`, which passes it as the accuracy argument to
  `mob.getNavigation().createPath(positions, validRange)`: the path counts as reaching the
  POI when the mob gets within that many blocks. `meeting` is 6 (you stand *near* a bell,
  not on it), everything else 1. (`PortalForcer` also reads it for portal placement; not
  relevant here.)

Vanilla registration reference, `PoiTypes.bootstrap`:

```java
register(registry, MEETING, getBlockStates(Blocks.BELL), 32, 6);
register(registry, HOME, BEDS, 1, 1);          // beds filtered to PART=HEAD
register(registry, ARMORER, getBlockStates(Blocks.BLAST_FURNACE), 1, 1);
```

### NeoForge registration

The registry is `BuiltInRegistries.POINT_OF_INTEREST_TYPE` (key
`Registries.POINT_OF_INTEREST_TYPE`), a normal simple registry — standard `DeferredRegister`
applies ([docs.neoforged.net 1.21.1 Registries]; `DeferredRegister.create` overloads
confirmed in `net.neoforged.neoforge.registries.DeferredRegister`):

```java
public static final DeferredRegister<PoiType> POI_TYPES =
    DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, Villagelife.MODID);

public static final DeferredHolder<PoiType, PoiType> CAMPFIRE_POI =
    POI_TYPES.register("campfire", () -> new PoiType(
        ImmutableSet.copyOf(Blocks.CAMPFIRE.getStateDefinition().getPossibleStates()),
        32,   // maxTickets
        6));  // validRange

// mod constructor:
POI_TYPES.register(modEventBus);
```

**The blockstate map is wired automatically.** This is the NeoForge-specific part:
`PoiTypes.TYPE_BY_STATE` is patched to *be*
`NeoForgeRegistryCallbacks.PoiTypeCallbacks.BLOCKSTATE_TO_POI_TYPE_MAP` (exposed through
`GameData.getBlockStatePointOfInterestTypeMap()`), and `NeoForgeRegistriesSetup` attaches an
`AddCallback<PoiType>` to the POI registry. `PoiTypeCallbacks.onAdd` inserts every
`matchingStates()` entry into the map the moment your type registers. No manual
`registerBlockStates` equivalent, no reflection — registering the `PoiType` is the whole job.
(The comment in the patched `PoiTypes.registerBlockStates` says exactly this: "Neo: we do
this automatically for modded PoiTypes in NeoForgeRegistryCallbacks".)

The callback also enforces a **global 1:1 constraint**: a blockstate may belong to at most
one POI type, and a collision is a hard `IllegalStateException` at registration ("Point of
interest types %s and %s both list %s in their blockstates..."). Vanilla campfires are *not*
a vanilla POI (checked the full `PoiTypes.bootstrap` list), so claiming them is safe — unless
another installed mod claims campfire states too, which would crash at startup. That is the
one compat risk of building on the vanilla block; a custom villagelife campfire block would
remove it.

### Which campfire states to include

`CampfireBlock` has `LIT`, `SIGNAL_FIRE`, `WATERLOGGED`, `FACING` → 32 states total.

- **All 32 states** (recommended for v1): the POI survives extinguishing and waterlogging;
  the only thing that removes it is breaking/replacing the block. Fewest edge cases.
- **`LIT=true` only** (16 states): dousing the fire deletes the POI record — "keep the fire
  lit or the village stops gathering" becomes a real mechanic. It is one `.filter()` on the
  state list, but every claim held on the campfire silently dangles when it's doused (see
  §7), so only choose this once claim self-healing exists.

## 2. Reusing `minecraft:meeting` instead — how, and why not

`PoiTypes.MEETING` (`minecraft:meeting`) exists as a `ResourceKey<PoiType>`; you can query
or take it from mod code freely (`holder.is(PoiTypes.MEETING)`).

**You cannot extend it to campfires cleanly.** Its state set is fixed to
`getBlockStates(Blocks.BELL)` at bootstrap, the record is immutable, and built-in registry
entries can't be replaced on NeoForge. The only lever would be shoving campfire states into
`GameData.getBlockStatePointOfInterestTypeMap()` yourself, mapping them to the meeting
holder — it would "work" (`ServerLevel.onBlockStateChange` would start tracking campfires as
meeting POIs) but it's writing into NeoForge's internal callback map, bypassing the
collision check, and every vanilla villager in range would immediately treat our campfires
as their meeting point. Not a supported surface; don't.

**Reuse-by-bell** (put an actual bell at the town center) is the honest version of this
option:

| | Pro | Con |
| --- | --- | --- |
| Behavior | Vanilla villagers already gather there 09:00–11:00 game time, zero code | Our idle-pool claims and vanilla `AcquirePoi(MEETING)` claims compete for the same 32 tickets per bell |
| World meaning | Bell is in `#minecraft:village` (tag = `#acquirable_job_site` + `home` + `meeting`), so it counts as a village center for `PoiManager.isVillageCenter` / `sectionsToVillage` | Same fact means raids, `RingBell`, `ReactToBell`/`SetHiddenState` hide-flow, iron-golem-era mechanics all attach to our gathering point |
| Design | — | The decided design (docs/population-and-labor.md) is a *campfire*, not a bell |

Verdict: reuse only makes sense if the gathering point should literally be a vanilla bell
with vanilla semantics. It shouldn't. Register the custom type.

## 3. Claiming semantics: take, release, tickets, persistence

All claim state lives in `PoiRecord` (`net.minecraft.world.entity.ai.village.poi.PoiRecord`):

- `freeTickets` starts at `maxTickets`; `acquireTicket()` / `releaseTicket()` (both
  `protected`, driven via `PoiManager`) decrement/increment and mark the section dirty.
- `hasSpace()` = `freeTickets > 0`; `isOccupied()` = `freeTickets != maxTickets`. These back
  the `PoiManager.Occupancy` enum: `HAS_SPACE`, `IS_OCCUPIED`, `ANY`.

**`PoiManager.take(Predicate<Holder<PoiType>> typePred, BiPredicate<Holder<PoiType>, BlockPos> combinedPred, BlockPos pos, int distance)`**
streams `HAS_SPACE` records in range, applies the predicate, takes **`findFirst()` — chunk
iteration order, *not* closest** — acquires one ticket and returns the position. Vanilla
never uses `take` to search: `AcquirePoi` first picks a target with
`findAllClosestFirstWithType(...)` + pathfinding, then calls
`take(pred, (type, p) -> p.equals(target), target, 1)` to claim exactly that block. Copy
that pattern.

**`PoiManager.release(BlockPos)`** looks up the record and calls `releaseTicket()`. Two
sharp edges, straight from the code:

- If no record exists at that position (block was broken, or the type mapping changed),
  `PoiSection.release` **throws `IllegalStateException("POI never registered at " + pos)`**.
  Vanilla always guards: `Villager.releasePoi` checks
  `poiManager.getType(pos)` against the expected type predicate before releasing. Always do
  the same.
- Releasing an already-full record returns `false` (it never goes above `maxTickets`).

**Persistence.** Ticket counts are durable: `PoiRecord.codec` serializes
`pos`, `type` (`RegistryFixedCodec` — the type's registry id, another reason ids must be
stable), and **`free_tickets`**. Sections (`PoiSection`, one per 16³ chunk section, records
indexed both by packed position and in a `byType` map) serialize under a `"Sections"` tag
with a `"Valid"` flag, stored per-dimension in the **`poi/` region folder**
(`ChunkMap` constructs `PoiManager` with `RegionStorageInfo(..., "poi")` and
`path.resolve("poi")`; storage machinery is `SectionStorage` + `SimpleRegionStorage`,
datafix type `POI_CHUNK`).

**Chunk unload.** `ChunkMap.save` calls `poiManager.flush(chunkPos)` (write dirty sections),
and the NeoForge unload path (`ChunkMap.scheduleUnload` →
`net.neoforged.neoforge.common.CommonHooks.onChunkUnload`) flushes and then **evicts the
cached sections** via the Neo-added `SectionStorage.remove(long)` (vanilla kept every
loaded POI section in memory forever; see the "Neo: ... PR #937" comment). Dirty sections
are also written incrementally each tick (`PoiManager.tick` ← `SectionStorage.tick`). Net
effect: **claims survive chunk unload and full server restarts.** The claimer's half (which
mob holds which position) is the claimer's own problem — villagers persist it because
`MemoryModuleType.MEETING_POINT` is registered with `GlobalPos.CODEC` and Brain memories
with codecs save into entity NBT.

**What claims do *not* survive: the block disappearing.** Removing the record deletes the
tickets with it; nothing notifies claimers (§7).

## 4. Querying POIs from server code, and what it costs

Entry point: `serverLevel.getPoiManager()` (`ServerLevel`, delegating to the chunk source).
The useful surface on `PoiManager`, all taking a `Predicate<Holder<PoiType>>` (so
`holder -> holder.is(CAMPFIRE_POI_KEY)` or a `PoiTypeTags` tag check) plus an `Occupancy`:

- `findClosest(typePred, pos, distance, occupancy)` → `Optional<BlockPos>` (overload adds a
  `Predicate<BlockPos>`); `findClosestWithType` returns the holder too.
- `find` / `findAll` / `findAllWithType` — first/all matches, unsorted;
  `findAllClosestFirstWithType` — all matches sorted by distance² (what `AcquirePoi` uses).
- `getInRange` (Euclidean, distance² filter) / `getInSquare` (Chebyshev XZ) → `Stream<PoiRecord>`
  — records expose `getPos`, `getPoiType`, `hasSpace`, `isOccupied`.
- `getCountInRange`, `exists(pos, typePred)`, `existsAtPosition(typeKey, pos)`,
  `getType(pos)`, `getRandom(...)`, and debug-only `getFreeTickets(pos)`.

**Performance shape** (from `getInSquare` → `getInChunk`): a query with radius *d* iterates
`ChunkPos.rangeClosed` with chunk radius `floorDiv(d,16)+1`, and for each chunk column every
vertical section (`levelHeightAccessor.getMinSection()..getMaxSection()`), calling
`getOrLoad` per section. For the vanilla-standard *d* = 48 in an overworld (-64..320) that
is 9×9 columns × 24 sections ≈ **1900 hash-map lookups**, then per matching section a
stream over just the `byType` bucket for your predicate — cheap. Two things to know:

- `getOrLoad` on an uncached column does a **synchronous region-file read** (whole column
  cached at once, including "empty" markers, until NeoForge evicts on chunk unload). POI
  queries never load or generate *chunks* — only POI data — so querying unloaded areas is
  legal and cheap-ish, but keep radii modest.
- Vanilla's cadence is the right guide: `AcquirePoi` runs a 48-block
  `findAllClosestFirstWithType(...).limit(5)` only every 20–40 ticks, with per-position
  retry backoff (`JitteredLinearRetry`: 40–80 ticks growing to a 400-tick cap) when
  pathfinding fails. Cache the found `GlobalPos`; never scan per-tick.
- `ensureLoadedAndValid` (force-loads POI data in a radius) exists but the only vanilla
  caller in 1.21.1 is `PortalForcer`; villager AI relies on normal chunk loading.
- Everything is main-server-thread API. Even `ServerLevel.onBlockStateChange` defers its
  POI mutations through `getServer().execute(...)`.

## 5. Goal-based (non-Brain) mobs: viable, with owned bookkeeping

**The POI system is entity-agnostic.** `PoiManager` is a `ServerLevel`-owned data structure;
nothing in `take`/`release`/queries touches `Brain`. Even vanilla's `AcquirePoi` is just
Brain-flavored glue: typed against plain `PathfinderMob`, it calls `PoiManager` directly and
uses the Brain only to store the resulting `GlobalPos`. A `Goal` on a plain `PathfinderMob`
can do the identical sequence:

1. Every ~20–40 ticks while unclaimed: `findAllClosestFirstWithType(pred, retryFilter,
   blockPos, 48, Occupancy.HAS_SPACE).limit(5)`.
2. `AcquirePoi.findPathToPois(mob, candidates)` (it's `public static` — reusable as-is);
   proceed only if `path != null && path.canReach()`.
3. `take(pred, (type, p) -> p.equals(path.getTarget()), target, 1)`.
4. Store the claim yourself: a `GlobalPos` field, written/read in
   `addAdditionalSaveData`/`readAdditionalSaveData` (this replaces the Brain memory codec).

The bookkeeping the mob then owns — each item is something vanilla does for villagers and
nobody will do for a goal mob:

- **Release on death**: `Villager.die` → `releaseAllPois` → `releasePoi(memory)`, which
  re-checks `poiManager.getType(pos)` matches the expected type before calling
  `poiManager.release(pos)` (the guard against the `IllegalStateException`). Override `die`
  the same way. `/kill` flows through `die`; `discard()` does **not** — release before any
  own-code discard/conversion (vanilla releases before the witch conversion in
  `Villager.thunderHit`).
- **Revalidate before trusting the claim**: `ValidateNearbyPoi` (runs when within 16
  blocks): if `!poiManager.exists(pos, typePred)` → just drop the stored position (no
  release — the record is already gone).
- **Give up on unreachable**: `SetWalkTargetFromBlockMemory` tracks
  `CANT_REACH_WALK_TARGET_SINCE` and, past a timeout (200 ticks in the meet package) or
  when it can't even path *toward* a far target, calls `releasePoi` + forgets. Note this
  behavior is typed `Villager` precisely because `releasePoi` lives on `Villager` — a goal
  mob writes its own equivalent helper.
- **Dimension changes / teleports**: vanilla checks `globalpos.dimension() == level.dimension()`
  everywhere; release when leaving.
- Accept that the ledger drifts (crash between entity save and POI save, block broken while
  claimed): tickets can leak until the block is replaced (a re-added record starts fresh at
  full `maxTickets`). Vanilla lives with this — 32 tickets absorb leaks, and
  `PoiCompetitorScan` exists to resolve duplicate job-site claims. For the campfire, a
  periodic Village-side reconcile (count actual idle members vs. tickets) is easy insurance,
  **or sidestep tickets entirely**: since `Village` already owns the idle roster
  (docs/population-and-labor.md), it is legitimate to register the POI with the tickets
  unused (query with `Occupancy.ANY`, never call `take`) and let `Village` be the single
  source of truth. Then the POI is purely the self-maintaining spatial index, and the whole
  leak class disappears.

## 6. How villagers use the bell, end to end (the template to copy)

All in `net.minecraft.world.entity.ai.behavior.VillagerGoalPackages` unless noted.

1. **Acquire** — core package (active in every activity), priority 10:
   `AcquirePoi.create(h -> h.is(PoiTypes.MEETING), MemoryModuleType.MEETING_POINT, true,
   Optional.of((byte)14))` — adults only, fires the happy-particles entity event on success.
   Scan radius 48, closest-5, path-gated, then exact-pos `take` (§5 sequence).
2. **Schedule** — `Schedule.VILLAGER_DEFAULT`: `MEET` activity from day-time **9000 to
   11000** (`changeActivityAt(9000, Activity.MEET)`), i.e. late afternoon.
3. **The meet package** (`getMeetPackage`), what "hang around the bell" actually is:
   - `SetWalkTargetFromBlockMemory.create(MEETING_POINT, speed, closeEnough=6, tooFar=100,
     unreachableTimeout=200)` — walk to within 6 blocks of the bell; if >100 blocks
     (Manhattan), path toward it in steps; give up (release + forget) if unreachable.
   - `StrollAroundPoi.create(MEETING_POINT, 0.4, 40)` — while within 40 blocks of the bell,
     pick a random land spot ≤8 blocks away every ≥180 ticks. This is the actual
     "milling around" motion.
   - `SocializeAtBell.create()` — 1%-per-activation: when within 4 blocks of the bell and
     another villager is visible within √32, set it as `INTERACTION_TARGET` and walk/look
     at it; a gated `TradeWithVillager` then runs on `INTERACTION_TARGET`.
   - `ValidateNearbyPoi.create(h -> h.is(PoiTypes.MEETING), MEETING_POINT)` — drop the
     memory if the bell is gone.
   - Plus trades/gifts/look behaviors and `UpdateActivityFromSchedule` at priority 99.
4. **Release paths**: `Villager.die`, `Villager.thunderHit` (witch conversion),
   `SetWalkTargetFromBlockMemory` give-up. `MEETING_POINT` never expires on its own
   (registered with a codec, no TTL).
5. **Bell-specific extras** (referenced in the ticket, *not* needed for a campfire):
   `RingBell` (pre-raid package: 5% chance, within 3 blocks, `BellBlock.attemptToRing`) and
   the raid hide flow — `ReactToBell`/`SetHiddenState` run off `HEARD_BELL_TIME` +
   `HIDING_PLACE` memories (hide ~15s near the hiding place), which is about the bell as an
   *alarm*, orthogonal to the meeting POI.
6. **Village-center side effect**: `PoiManager.isVillageCenter` = any `IS_OCCUPIED` record
   whose type is in `#minecraft:village` (data tag = `#minecraft:acquirable_job_site` +
   `minecraft:home` + `minecraft:meeting`); `DistanceTracker`/`sectionsToVillage` spread
   "village-ness" 6 sections out from those, feeding `VillageBoundRandomStroll` /
   `GoToClosestVillage`. Adding `villagelife:campfire` to that tag (datapack:
   `data/minecraft/tags/point_of_interest_type/village.json`) would make occupied campfires
   count as village centers for all those vanilla mechanics — powerful, deliberate choice;
   leave it out until wanted. (Requires occupancy, i.e. actually using tickets.)

The campfire translation for goal mobs: acquire-goal (§5) + a "campfire loiter" goal =
walk-to-within-6 + the `StrollAroundPoi` random-stroll logic + occasional
socialize-with-neighbor, gated on our idle state instead of a schedule activity.

## 7. Gotchas

- **POI records track blockstate changes automatically, and destructively.** Any successful
  server-side `Level.setBlock` ends in `Level.markAndNotifyBlock` →
  `ServerLevel.onBlockStateChange(pos, old, new)`, which diffs `PoiTypes.forState(old)` vs
  `forState(new)` and schedules `PoiManager.remove(pos)` / `add(pos, holder)` on the server
  thread — regardless of update flags. Break the campfire → record **and its tickets**
  deleted instantly, claimers not notified; re-place it → fresh record at full
  `maxTickets`. Whether *waterlogging/extinguishing* removes the POI is entirely your state
  set choice (§1): with all 32 states included it survives; with `LIT=true`-only it doesn't.
  Either way every consumer needs the `ValidateNearbyPoi`-style "does it still exist"
  check.
- **`release` throws on a missing record** (`IllegalStateException("POI never registered
  at ...")`). Never release without the `getType(pos)` guard (§3, §5).
- **`take` is first-match, not closest** (§3). Search first, then take the exact position.
- **One blockstate, one POI type, globally** — registration crashes on collision. Claiming
  `Blocks.CAMPFIRE` states is fine against vanilla (campfire is not a vanilla POI) but can
  collide with another mod doing the same.
- **Self-healing on chunk load**: `ChunkSerializer.read` calls
  `PoiManager.checkConsistencyWithBlocks` per section; a section whose stored `"Valid"`
  flag is false gets rebuilt from real blockstates (`PoiSection.refresh`), *keeping*
  existing records (and ticket counts) at positions that still qualify. This also means
  worldgen/structure-placed campfires get their POI records created on first load without
  any code.
- **Persistence trust boundary**: POI data (`poi/` region files) and entity NBT save
  independently; a crash can leak or double-grant tickets. Design for drift (§5), don't
  assume the ticket ledger is exact.
- **`maxTickets`/`validRange` are frozen at registration.** Registry entries are built
  before server configs load, so the idle cap from `villagelife-common.toml` cannot be the
  ticket count. Enforce configurable caps in Village logic.
- **Main thread only.** Don't touch `PoiManager` from worldgen or other threads; vanilla's
  own block-change hook defers via `getServer().execute`.
- **NeoForge specifics**: blockstate map auto-registration via registry callback (no manual
  call, unlike old Forge `PoiTypes#registerBlockStates` reflection hacks — anything on the
  internet suggesting `PoiTypeCallbacks`-era workarounds predates this); POI section cache
  is properly evicted on chunk unload (Neo PR #937), so long-running servers don't
  accumulate POI memory; `DeferredHolder#get` is only valid after registration runs.

## 8. What the campfire implementation would actually do

Sketch, mapped to real API:

1. **Registration** (`ModPoiTypes`): `DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE,
   MODID)` + `POI_TYPES.register("campfire", () -> new PoiType(allCampfireStates, 32, 6))`,
   bus-registered in the mod constructor. All 32 campfire states for v1; revisit LIT-only
   when claim self-healing is proven.
2. **Village discovery**: when `Village` needs its gathering point,
   `poiManager.findClosest(h -> h.is(CAMPFIRE_POI.getKey()), center, 48, Occupancy.ANY)`;
   cache the `BlockPos`, re-verify with `poiManager.exists(pos, pred)` on use. If the town
   center placement is deterministic (structure places the campfire), the Village can also
   just be told the pos at placement and use `exists` as the liveness check.
3. **Idle villagers** (goal-based): an `AcquireCampfireGoal` implementing the §5 sequence
   (backoff, closest-5, path-gate, exact `take`, `GlobalPos` saved in NBT) and a
   `LoiterAtCampfireGoal` implementing §6.3 (walk to within 6, random ≤8-block strolls on a
   ~180-tick cadence while within 40, occasional look-at-neighbor), both gated on
   `isIdle()`.
4. **Bookkeeping**: `die()` override releasing via the guarded pattern; release when the
   villager takes a job (leaves the idle pool) or the claim goes unreachable; a
   `ValidateNearbyPoi`-equivalent check each time the goal starts.
5. **Simplification lever**: if Village remains the single roster authority, skip `take`/
   `release` entirely and query with `Occupancy.ANY` — POI as pure spatial index, idle cap
   enforced by Village, zero ticket bookkeeping. Adopt tickets only if villagers must
   self-organize without asking the Village object.
6. **Later, optional**: datapack-tag the POI into `#minecraft:village` if campfires should
   read as village centers to vanilla systems (needs tickets/occupancy to matter).

## Source index

| Claim area | Source (mojmap class in neoforge-21.1.72-minecraft-sources.jar) |
| --- | --- |
| PoiType fields, vanilla values | `net.minecraft.world.entity.ai.village.poi.PoiType`, `PoiTypes#bootstrap` |
| Tickets, `free_tickets` codec | `net.minecraft.world.entity.ai.village.poi.PoiRecord` |
| take/release/query surface, Occupancy, consistency check | `net.minecraft.world.entity.ai.village.poi.PoiManager` |
| Section layout, `Valid` flag, refresh | `net.minecraft.world.entity.ai.village.poi.PoiSection` |
| Storage, sync loads, tick/flush, Neo eviction (`remove`) | `net.minecraft.world.level.chunk.storage.SectionStorage` |
| poi/ folder, flush-on-save, unload hook | `net.minecraft.server.level.ChunkMap`, `net.neoforged.neoforge.common.CommonHooks#onChunkUnload` |
| Auto add/remove on block change | `net.minecraft.world.level.Level#markAndNotifyBlock`, `net.minecraft.server.level.ServerLevel#onBlockStateChange` |
| Consistency-on-load | `net.minecraft.world.level.chunk.storage.ChunkSerializer` |
| NeoForge blockstate-map callback | `net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks$PoiTypeCallbacks`, `NeoForgeRegistriesSetup`, `GameData` |
| Acquisition template | `net.minecraft.world.entity.ai.behavior.AcquirePoi` |
| Release/validation patterns | `net.minecraft.world.entity.npc.Villager` (`die`, `releasePoi`, `thunderHit`), `behavior.ValidateNearbyPoi`, `behavior.SetWalkTargetFromBlockMemory`, `behavior.PoiCompetitorScan` |
| Meet package, schedule | `net.minecraft.world.entity.ai.behavior.VillagerGoalPackages`, `SocializeAtBell`, `StrollAroundPoi`, `RingBell`, `SetHiddenState`, `net.minecraft.world.entity.schedule.Schedule` |
| Memory persistence | `net.minecraft.world.entity.ai.memory.MemoryModuleType` (`MEETING_POINT` + `GlobalPos.CODEC`), `net.minecraft.world.entity.ai.Brain` codec |
| Village tag contents | `data/minecraft/tags/point_of_interest_type/village.json` (neoforge-21.1.72-minecraft-resources jar), `net.minecraft.tags.PoiTypeTags` |
| DeferredRegister idiom | [docs.neoforged.net/docs/1.21.1/concepts/registries](https://docs.neoforged.net/docs/1.21.1/concepts/registries/), `net.neoforged.neoforge.registries.DeferredRegister` |
