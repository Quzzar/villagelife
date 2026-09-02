# Marriage

**Implemented (plains).** Two villagers who have grown close ask the village brain
to wed them, and the brain decides, the same way a villager petitions it to build
something ([villager-requests.md](villager-requests.md)). A married pair is given a
home of its own to raise. The whole feature rides rails that already existed:
pairwise bonds ([relationships.md](relationships.md)), the brain's `decide` call
([llm-brain.md](llm-brain.md)), and the saved-for goal
([buildings.md](buildings.md)). It invents no parallel state.

## The loop

```
a strong, mutual bond
   → each villager files a proposal naming the other        (MarriageProposals)
   → the brain is asked to bless the pair that named each other   (LlmService.decide)
   → the blessed couple settle their married name themselves      (MarriageNaming, on Dialogue)
   → both become MARRIED and take the name they chose (or a hyphenation), the pair edge is flagged married
   → the village names a couple's cottage as its saved-for goal   (VillageGoal)
   → on the cottage's completion both are moved into its two beds  (Village.houseCouple)
   → their shared chest follows for free                          (PersonalChest)
```

Each arrow is an existing mechanism. Nothing here is a new subsystem.

## Proposing is emergent, not scripted

A proposal is not raw feeling, and it is not the brain's decision: it is one
villager's expressed wish, the marriage twin of a build request. A single villager
whose bond to another single villager has grown **strong and mutual** files a
proposal naming them (`MarriageService.fileEmergentProposals`). Because a bond is
one shared object ([relationships.md](relationships.md)), a strong one makes *both*
sides file, and two proposals pointing at each other are the mutual ask the brain
is handed.

- **Strong** is an effective opinion of at least `STRONG_BOND` (50) on **both**
  sides. Generation caps a first-day opinion at 60 and drift is bounded, so a pair
  this warm both ways is among the strongest bonds a village holds: the feelings
  the model authored with a reason, not the slow pull of familiarity. The number is
  tunable; the live web is what settles it.
- Each villager proposes only to the **one** villager they are most fond of, so no
  one asks two people at once.
- Any two eligible villagers may marry. There is no gender rule: children are a
  separate system and out of scope here.

Proposals live in the brain's `strategy` tag beside the build requests and the
goal (`MarriageProposals`), age out after 1800 village-seconds, and are cleared
the moment the pair is wed.

## The brain decides

When two villagers have each proposed the other, the brain is asked whether to wed
them through the same numeric-choice `decide` the build planner uses: the facts of
the pair go in (who they are, their bond value and its flavour line, the village's
size), the model answers *yes, they should marry* or *not yet*. One decision is in
flight at a time (`Village.marriageDecisionPending`), the same discipline the build
and job decisions keep, so a slow model cannot stack requests. A failed, slow, or
absent model just leaves the pair betrothed for next pass; nothing is forced.

A wedding (`MarriageService.wed`):

- sets both villagers to `MARRIED` and gives them **one household name**, the one
  the couple chose for themselves (see "The couple names itself" below), or a
  hyphenation when their talk settled nothing.
- flags the `RelationshipPair` between them **married**, which is the single source
  of truth for who is wed to whom, and stops the pair from ever being proposed
  again.

`MarriageStatus` on the person is the cheap projection the client shows and the
eligibility check reads; the pair edge is the fact. The spouse is derived from the
edge wherever it is needed (a villager's chat briefing tells them who they are
married to), never stored twice.

## The couple names itself

The married name is not a rule's to pick; it is the couple's. Once the brain has
blessed the marriage, it convenes the two betrothed and they settle the name
themselves, in a group chat run on the shared conversation engine
(`MarriageNaming`, on `Dialogue`; see [conversations.md](conversations.md)). This
is the "just ask the villager" pattern applied to a decision that is genuinely
theirs: they talk it over as themselves, in the first person and overheard by any
player nearby, and the way to end the talk is a valid choice.

The choice is constrained to what a marriage can sensibly make of two names: keep
one, take the other, or join them hyphenated in either order (`Ada Hollic` and
`Bren Vane` may become the `Hollic`, `Vane`, `Hollic-Vane`, or `Vane-Hollic`
household). The model is shown those exact options and its answer is validated
against them, so no invented surname can slip through.

The decision stays pending (`Village.marriageDecisionPending`) across the whole
talk, so no other marriage in the village starts while it runs. When the talk
lands no valid name (a quiet model, or a pair who never agree), the wedding falls
back to the hyphenation the two derive by UUID order, so the couple's voice is
honoured when they use it and a marriage is never blocked when they do not. This
is the same graceful-deferral contract every LLM path in the mod keeps.

## Housing queues, it does not preempt

A married pair who do not yet share a home is a **couple awaiting one**, derived
each pass from the married pairs and the bed assignments, never stored as its own
list. When the village is **not already saving for something else**, it names a
`couple_cottage` as its `VillageGoal` (`MarriageService.ensureCoupleHomeGoal`).
That is the "queues next" rule: a marriage does not shove aside a lumberjack the
village was already saving for; it takes the goal slot once that clears.

The goal short-circuit then builds the cottage the moment it can afford it. A home
the economy cannot reach stalls through the ordinary goal machinery, and while it
sits out the village is free to build other things rather than hammering an
unaffordable cottage forever.

On the cottage's completion (`Village.addBuilding` → `MarriageService.onHomeBuilt`)
both spouses are moved into its two beds (`Village.houseCouple`), freeing whatever
single beds they held back to the pool. Their **shared chest needs no code**: a
home's `personal_containers` belong to whoever sleeps there
([PersonalChest](../src/main/java/com/quzzar/villagelife/village/PersonalChest.java)),
so co-assigning both beds makes the cottage chest theirs together, and each already
reads the other as a housemate.

## The couple's cottage

A new building category, `couple_cottage`. Unlike every other category it is
**never chosen spontaneously** by the brain: the planner filters it out of the
options it deliberates over (`UrbanPlanner.isMarriageOnly`), so it is built only as
the goal a marriage sets. A village should not raise one for no one.

The plains cottage (`couple_cottage_plains_1`) is a 9x7x11 one-room home with a
pitched oak-stair roof, glass windows, and a small front garden of flowers and a
path. Its one defining feature is the **double bed**: two beds side by side, the
couple's shared sleeping nook, rather than the separate beds a shared house
carries. Its beds are named at the **foot** block, the coordinate the building
definition's `beds` array expects.

It was authored the way the repo's structures are meant to be
([structure-authoring.md](structure-authoring.md)): built by hand in-world, then
captured with `/vldev village save-structure`, and copied into `resources/`. On the
bottom layer, every cell that is not floor or garden is left as nothing (captured as
structure-void, absent from the file), so placing the cottage on real ground lays
its floor without carving a pit where the footprint has no floor. It is checked with
`validate.py` (nothing drops on placement) and `navcheck.py` (both beds reachable
and on the ground floor, the door reachable).

**Not yet done:** the other four biome variants
(`couple_cottage_{taiga,snowy,desert,savanna}_1`), mapped off the plains cottage the
way `mine-level-2.py` maps its variants, or hand-built per biome.

## Code map

- `relationships/MarriageService`: the pass, run on the village tick. Files
  proposals, asks the brain, weds, names the home goal, houses the couple on
  completion.
- `village/MarriageProposals`: the proposal store on the brain's `strategy` tag,
  with mutual-match and clear-on-wed.
- `relationships/RelationshipPair`: gained a `married` flag (additive codec field),
  preserved through drift; the pair edge is the marriage.
- `entities/RealPerson#marry`: the person-level projection, `MARRIED` plus the
  hyphenated surname, on both spouses at once.
- `village/Village`: the tick pass, `marriageDecisionPending`, `marriedPairs`, and
  `houseCouple`; the `addBuilding` hook that houses a couple as their cottage lands.
- `village/buildings/UrbanPlanner#isMarriageOnly`: keeps the cottage out of the
  brain's spontaneous options, and `shortfallFor` names the housing goal with a
  real shortfall so it stalls honestly.
- `chat/PersonChatContext#spouseLine`: a married villager's own knowledge of who
  they are wed to, derived from the edge.
- `src/main/resources/data/villagelife/villagelife/buildings/couple_cottage_plains_1.json`
  and its `.nbt`; hand-built in-world and captured with `/vldev village save-structure`.
