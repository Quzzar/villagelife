# docs/

The project's knowledge, one topic per file. Read the topic covering an area before working
in it, and update it in the same change that moves what it describes.

- [population-and-labor.md](population-and-labor.md): the campfire model. How villagers
  arrive, what caps the population, and how jobs get filled from the idle pool. The decided
  design the refactor is building toward.
- [genetics-and-attributes.md](genetics-and-attributes.md): the D&D-style stat block
  (six abilities plus size and eyesight) every villager carries, and the projection
  matrix that turns stats into Minecraft attribute modifiers.
- [personas.md](personas.md): AI-generated character blurbs and quirks. The prompt
  contract, the generate-before-spawn lifecycle, and the persona package code map.
- [undertakings.md](undertakings.md): the tracked matters a villager sees through, good or
  bad, with progress and resolution. One generic model for making amends, needing rescue,
  and self-set goals. Design only so far; grows out of PersonalLogData's event memories.
- [relationships.md](relationships.md): pairwise villager opinions. The one-pair-one-object
  model that keeps opinions roughly shared, the newcomer integration pass, and the code map.
- [marriage.md](marriage.md): two villagers who have grown close ask the brain to wed them,
  and it decides. The emergent proposal, the brain's blessing, the couple choosing their own
  married name in a group chat, and the couple's cottage the village saves for and moves them into.
- [families.md](families.md): parentage and the four-stage growth lifecycle, dependent family
  housing, teenager employment, advance housing goals, and the transition to an adult bed.
- [companions.md](companions.md): the dog or cat some villagers keep. Who is granted one and
  when, the one-per-species cap bonded to the person not the post, the owner naming it and
  choosing its look, the custom follow goal a mob owner needs, the village-tether when the
  owner is gone, and the owner's occasional sit-or-recall decision.
- [ui-preview.md](ui-preview.md): how to photograph a client screen without playing the
  game. Required reading before changing anything under `client/gui/`, because every
  version of the villager screen written without looking at it was wrong.
- [economy.md](economy.md): the market, emeralds, and trade. Why a market exists when chests
  are free, the bank that floors every price, and how items get valued for modpacks.
- [llm-brain.md](llm-brain.md): the required LLM that picks among rule-generated
  options for villagers, run offline via llama.cpp (or a cloud provider) with zero
  player setup. The two offline models, the three cloud services, and failure semantics.
- [villager-requests.md](villager-requests.md): how a villager petitions the brain to
  build something, with a reason, and the brain still decides. The propose-not-dispose
  invariant, the chat-tool-to-planner loop, and requests as raw context the brain weighs.
- [conversations.md](conversations.md): the one turn-taking engine (`Dialogue`) behind
  every conversation the village drives itself. The conversation shapes, the
  voices-transcript-stop-condition loop, the autonomous callers (villager talk, the
  quartermaster's shelving, a couple's and a pet-owner's naming), and why the one-shot
  `decide` family stays a separate primitive.
- [villager-conversations.md](villager-conversations.md): villagers talking to each
  other through the same pipeline a player uses, run on the shared conversation engine.
  The seek-and-pause loop, the background LLM lane, what passes between them (items,
  opinions, undertakings, requests), and the pacing budget that keeps gossip cheap.
- [village-tiers.md](village-tiers.md): the progression ladder (camp → hamlet → village
  → town → city) as datapack data. A tier is a read-out of population, never a gate:
  tier format, the 4-bed village center bootstrap, and why building is constrained by
  resources and space instead.
- [buildings.md](buildings.md): the building catalog. The categories a village can
  build, the three axes they vary on (category, variant, level), the production chains
  that connect them, and which biomes can support which. Proposed, not yet decided.
- [appearance.md](appearance.md): why villagers use the player model and not the vanilla
  villager model, the wide/slim model split by gender, and the client-side runtime skin
  compositor that bakes a villager's look from inherited skin, hair, and eye structures,
  continuous pigment genes, and occupation- or life-stage-driven clothing.
- [appearance-wardrobes.md](appearance-wardrobes.md): the occupational clothing catalog,
  current role coverage, implementation gaps, and the separation between inherited appearance
  and replaceable job clothing.
- [site-selection.md](site-selection.md): how a village finds somewhere to build. Site cost
  instead of site validity, how far villagers may reshape ground (surface yes, shape never),
  the builder's prepare phase, and the runtime budget that keeps site search off the tick.
- [building-spec.md](building-spec.md): the complete enumeration. Every variant, every level,
  its cost in build points, its special materials, and the capabilities it grants the village.
  The enumeration maps 36 categories, of which 17 survived the cut — see its "The cut"
  section for the survivors and the casualties. Ends in the structure-file manifest used for
  sourcing.
- [walls.md](walls.md): the perimeter defense system. Why a wall is not a `Building` (linear,
  not a footprint), how its ring is derived from `claimGrid` and built by a terrain-following
  builder step, the two tiers (wood palisade upgrading in place to stone brick), and the
  safety-need trigger. The site-selection pass the parked `wall`/`gatehouse` spec was waiting on.
- [block-ownership.md](block-ownership.md): who owns the block at a position, village, player,
  or nobody. Two compact per-level sets recorded at placement and pruned on break, why planted
  saplings are deliberately nobody's, the `mayFell` verdict tree-clearing uses, and the known
  over-protecting imprecisions.
- [structure-sourcing.md](structure-sourcing.md): where building structures can legally come
  from. License survey of vanilla, CTOV, Towns and Towers, YUNG's, and community schematic
  sites, each read from its own LICENSE file, plus the realistic paths forward.
- [worker-loops.md](worker-loops.md): what a villager actually does. The three verbs a job is
  built from, roaming versus fixed, what happens when there is nothing to work on, and the
  performance budget that decides how targets get found.
- [structure-authoring.md](structure-authoring.md): how a building's structure file gets made,
  headlessly, with commands plus `/vldev village save-structure`. The loop the current village
  center was built with.
- [village-loading.md](village-loading.md): keeping a village awake when no player is near. The
  two-speed village today (bookkeeping always, world frozen when unattended), what a loaded
  village keeps resident (building chunks, a 2-chunk perimeter, the pending build site, a bubble
  per member), the three modes (off, all, hybrid default with a six-day grace window), and why
  there is no loaded-village cap.

## Research

Findings gathered against primary sources, kept as read at the time. The topic files above
carry the decisions; these carry the evidence behind them.

- [research/automatone.md](research/automatone.md): Automatone, the server-side Baritone fork that
  gives non-player entities terrain-modifying pathfinding. Why it is read-not-ship (Fabric-locked,
  fake-player-shaped, unmaintained at 1.21, built for a few bots), and the small license-clean core
  worth reimplementing ourselves: a tick-priced movement cost model with break and place folded
  into the A* edge weights, and a weighted A* that returns a best-so-far segment on timeout and
  never expands into unloaded chunks. The real alternative to teleport-on-stuck.
- [research/poi-gathering-points.md](research/poi-gathering-points.md): POI types on NeoForge
  1.21.1 for the campfire gathering point. Why a custom `villagelife:campfire` POI beats reusing
  `minecraft:meeting`, and what goal-based claiming costs without the Brain system.
- [research/vanilla-structure-conversion.md](research/vanilla-structure-conversion.md): what
  vanilla village templates actually ship, how much of phase 1 they cover, and why copying them
  into the jar is forbidden while referencing them at runtime is not.
- [research/villager-work-loops.md](research/villager-work-loops.md): how MineColonies,
  Millenaire, Minecraft Comes Alive, and Ancient Warfare drive villager work, and which of their
  mechanisms survive the trip to NeoForge 1.21.1.
