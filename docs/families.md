# Families and growing up

**Implemented.** Married couples who share a completed home periodically decide together whether
they want a child. A successful decision schedules a birth, the child inherits both parents'
genetics and household surname, and the household then moves, sleeps, grows, and returns from the
road as a family. The ordinary child-creation service and the development commands exercise the
same inheritance path.

## The four age stages

Every person has one persisted and synced `AgeStage`. A newborn begins as a Toddler and advances
automatically after the configured number of Minecraft days per stage (8 by default, one lunar
cycle). Time is measured from a persisted game-time stamp, so unloading a child does not pause
their growth.

| Stage | Stage multiplier | Approximate display height | Entity height | Work | Visible role | Clothing |
| --- | ---: | ---: | ---: | --- | --- | --- |
| Toddler | 1.13 | 1.13 m | 1.20 m | No | Toddler | Child commonwear |
| Kid | 1.44 | 1.44 m | 1.51 m | No | Kid | Child commonwear |
| Teenager | 1.74 | 1.74 m | 1.82 m | Yes | Teenager while idle; occupation while employed | Commonwear while idle; occupation clothing while employed |
| Adult | 1.00 | 1.88 m | 1.95 m | Yes | Normal title/occupation | Occupation clothing |

These are reference heights before the person's Size gene and dwarfism/gigantism modify them.
The pre-adult values are multipliers over Minecraft's nominal one-block young player model, not
direct meter heights. They compensate for that naturally smaller model and then compose with the
person's Size gene and dwarfism/gigantism. Entity and nameplate height use the resulting relative
display height plus the same 0.074-block model-to-bounds clearance used by an adult; Minecraft adds
its normal fixed 0.5-block nameplate lift after that. Toddler, Kid, and Teenager label stacks then
receive the same presentation-only 0.10-block lift for a little extra head clearance. This moves the
name, role, and speech bubble together without changing the rendered body or collision dimensions.
Old saves carrying only `IsBaby` migrate into Kid, preserving their pre-adult life stage under the
new sizing model.

## Parentage and child creation

`ChildCreationService` is the single creation seam. It uses the existing generate-before-spawn
persona pipeline, records both parent UUIDs on the child, inherits a parent-centered `StatBlock`,
recombines `AppearanceGenes`, starts the child as a Toddler, and registers them directly in the
parents' village when both parents belong to the same one. Every derived attribute, including
height and maximum health, is recalculated from the inherited stats before persona generation.
The child takes the parents' household surname. Given name, virtues, personality, and gender are
freshly randomized.

At birth, each parent-child relationship starts at 85 and sibling relationships start at 75, both
with the `close family` flavor. Those edges make the family socially close immediately instead of
waiting for ordinary relationship drift to discover a bond that is already true. Parent-child,
full-sibling, and half-sibling pairs are ineligible for marriage at proposal, decision, and final
wedding time. The same check rejects invalid legacy pairs from family planning.

## Deciding to have a child

Family planning is a persisted schedule per married pair in the village brain's `strategy` tag.
A pair is eligible when both people are Adults, married to each other, resident in the same
village, and sharing one completed home. The default cadence is:

1. One Minecraft day after first becoming an eligible household, the village convenes them.
2. They hold a visible, alternating conversation as themselves. The prompt includes the village
   briefing, including population, housing, recent events, work, and food.
3. Food and housing are context for their choice, not hard birth gates. Both spouses must
   independently say yes.
4. Mutual agreement schedules the birth for the next daytime. A no or an unfinished model call
   schedules another conversation four days later.
5. After a successful birth, the couple waits eight days before discussing another child.

There is deliberately no hard family-size limit. Conversation and the post-birth cooldown provide
the pacing. When no language model is available, no decision or birth is forced; the conversation
is retried later.

Each agreed birth has a 3% chance of twins and a 0.25% chance of triplets, leaving a 96.75% chance
of one child. All siblings in a multiple birth share one exact inherited stat block, appearance
gene set, and appearance seed. Their names, personalities, and social identities are still
generated independently. The rates and all three timing intervals are advanced config values.

## Dependent housing

A Toddler, Kid, or Teenager may live in either resident parent's usable home without claiming a
bed. The building is their household anchor: they return there at night, share its personal chest,
and count its residents as housemates. They never occupy a parent's bed; they use the visible sleep
pose on the floor near the household center. There is no reserved floor coordinate. If neither
parent has a usable home, they have no dependent housing and remain by the campfire at night.

Children retire earlier by age and wake with the household at dawn. Toddlers begin resting at
time 11000, Kids at 12000, and Teenagers at 13000. Adults keep the ordinary Minecraft night check.

Dependents do not count against the housing cap and are not included in the adult homelessness
penalty. Toddlers and Kids are excluded from the idle labor pool. An idle Teenager is in that pool
and may be claimed by a job, but only while a parent supplies valid dependent housing.

## Working teenager to adult

The first employed Teenager who lacks a future adult bed makes the village save for an ordinary
one-bed house through `VillageGoal`. Multiple teenagers are matched against distinct free general
or live-in workplace beds, so one spare bed is never counted as the answer for several children.
The housing need queues behind a goal the village already committed to and is reconsidered every
village tick until it can become the saved-for goal.

On adulthood, dependent housing stops immediately. The new adult receives first claim on a free
general bed; a free live-in bed at their own workplace is also valid. If neither exists, the
ordinary employment invariant applies: their job reopens, their occupation becomes Wanderer, and
they return to the campfire. This is the same outcome for every adult worker whose bed is lost.

## Family travel and orphans

Emigration treats the resident spouse, parents, and dependent children as one departure group.
When one adult household member leaves because village attractiveness collapsed, the nuclear
family walks to the edge together, roams behind one stable household leader, crosses the horizon
together, and is restored together when another village recruits them. The destination rebuilds
their marriage and close-family relationship edges. Arrival caps count dependents correctly:
pre-adults do not consume independent beds, and only work-eligible family members consume idle
labor capacity.

A child stays with any living resident parent and inherits that parent's usable home. If neither
parent remains, the child is an orphaned Wanderer with no dependent home. No other household adopts
them in this version. They continue through the ordinary age stages and, as an Adult, follow the
same bed and employment rules as every other unhoused Wanderer.

## Danger and personality

Being struck and actively seeking danger are separate decisions. Guards and hunters can still
seek threats as part of their occupation. Direct self-defense is based on the person's aggression,
their protect-self virtue, and age. Toddlers always flee. Kids need unusually high resolve;
Teenagers and Adults fight back when their combined resolve is positive. Anyone who does not fight
back leaves the attacker untargeted so panic and avoidance behavior can carry them away.

## Development controls

- `/vldev appearance child <firstParent> <secondParent>` creates a child through the canonical path.
- `/vldev appearance stage <target> <toddler|kid|teenager|adult>` moves a person directly to a stage
  and restarts that stage's clock, making every transition inspectable without waiting eight days.
- `/vldev family status <firstParent> <secondParent>` reports eligibility and the persisted next
  conversation or birth day.
- `/vldev family consider <firstParent> <secondParent>` starts the real visible family-planning
  conversation immediately.
- `/vldev family birth <firstParent> <secondParent> [singleton|twins|triplets]` creates a chosen
  multiple birth through the canonical inheritance and persona path.
- `/vldev family sample <firstParent> <secondParent> <count>` simulates 1 to 10,000 inherited stat
  blocks and reports min/mean/max stats and condition counts without spawning entities.
