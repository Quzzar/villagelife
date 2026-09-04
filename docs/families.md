# Families and growing up

**The growth and housing lifecycle is implemented.** Child creation has one reusable service and
the developer command exercises that same path. Automatic decisions about when a married couple
has a child remain future gameplay; the family system does not invent a birth cadence on its own.

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
persona pipeline, records both parent UUIDs on the child, recombines `AppearanceGenes`, starts the
child as a Toddler, and registers them directly in the parents' village when both parents belong
to the same one. `/vldev appearance child <firstParent> <secondParent>` calls this service; repeated
calls create distinct siblings.

Mechanical stat and virtue inheritance is still separate work. The current service inherits the
implemented appearance genes and preserves parentage so those inheritance systems have one place
to attach later.

## Dependent housing

A Toddler, Kid, or Teenager may live in either resident parent's usable home without claiming a
bed. The building is their household anchor: they return there at night, share its personal chest,
and count its residents as housemates. They never occupy a parent's bed; at night they rest in the
home without entering the bed pose. If neither parent has a usable home, they have no dependent
housing and remain by the campfire at night.

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

## Development controls

- `/vldev appearance child <firstParent> <secondParent>` creates a child through the canonical path.
- `/vldev appearance stage <target> <toddler|kid|teenager|adult>` moves a person directly to a stage
  and restarts that stage's clock, making every transition inspectable without waiting eight days.
