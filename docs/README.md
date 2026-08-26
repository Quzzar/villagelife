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
- [relationships.md](relationships.md): pairwise villager opinions. The one-pair-one-object
  model that keeps opinions roughly shared, the newcomer integration pass, and the code map.
- [llm-brain.md](llm-brain.md): the required LLM that picks among rule-generated
  options for villagers, and the worker process that runs it with zero player setup.
  Model benchmarks, failure semantics, and why Jlama must be shaded into one flat jar.
- [village-tiers.md](village-tiers.md): the progression ladder (camp → hamlet → village
  → town → city) as datapack data. A tier is a read-out of population, never a gate:
  tier format, the 4-bed village center bootstrap, and why building is constrained by
  resources and space instead.
- [buildings.md](buildings.md): the building catalog. The 37 categories a village can
  build, the three axes they vary on (category, variant, level), the production chains
  that connect them, and which biomes can support which. Proposed, not yet decided.
- [appearance.md](appearance.md): why villagers use the player model and not the vanilla
  villager model, and the three-layer skin plan (base skin, regional dress, occupation
  layer) that makes them read as villagers rather than as players.
- [site-selection.md](site-selection.md): how a village finds somewhere to build. Site cost
  instead of site validity, how far villagers may reshape ground (surface yes, shape never),
  the builder's prepare phase, and the runtime budget that keeps site search off the tick.
- [building-spec.md](building-spec.md): the complete enumeration. All 37 categories with
  every variant, every level, its cost in build points, its special materials, and the
  capabilities it grants the village. Ends in the structure-file manifest used for sourcing.
