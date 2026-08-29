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
  and self-set goals. Design only so far; grows out of PersonalLogData's flat issue log.
- [relationships.md](relationships.md): pairwise villager opinions. The one-pair-one-object
  model that keeps opinions roughly shared, the newcomer integration pass, and the code map.
- [ui-preview.md](ui-preview.md): how to photograph a client screen without playing the
  game. Required reading before changing anything under `client/gui/`, because every
  version of the villager screen written without looking at it was wrong.
- [economy.md](economy.md): the market, emeralds, and trade. Why a market exists when chests
  are free, the bank that floors every price, and how items get valued for modpacks.
- [llm-brain.md](llm-brain.md): the required LLM that picks among rule-generated
  options for villagers, run offline via llama.cpp (or a cloud provider) with zero
  player setup. The two offline models, the three cloud services, and failure semantics.
- [village-tiers.md](village-tiers.md): the progression ladder (camp → hamlet → village
  → town → city) as datapack data. A tier is a read-out of population, never a gate:
  tier format, the 4-bed village center bootstrap, and why building is constrained by
  resources and space instead.
- [buildings.md](buildings.md): the building catalog. The categories a village can
  build, the three axes they vary on (category, variant, level), the production chains
  that connect them, and which biomes can support which. Proposed, not yet decided.
- [appearance.md](appearance.md): why villagers use the player model and not the vanilla
  villager model, and the three-layer skin plan (base skin, regional dress, occupation
  layer) that makes them read as villagers rather than as players.
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
- [structure-sourcing.md](structure-sourcing.md): where building structures can legally come
  from. License survey of vanilla, CTOV, Towns and Towers, YUNG's, and community schematic
  sites, each read from its own LICENSE file, plus the realistic paths forward.
- [worker-loops.md](worker-loops.md): what a villager actually does. The three verbs a job is
  built from, roaming versus fixed, what happens when there is nothing to work on, and the
  performance budget that decides how targets get found.
- [structure-authoring.md](structure-authoring.md): how a building's structure file gets made,
  headlessly, with commands plus `/vldev village save-structure`. The loop the current village
  center was built with.
