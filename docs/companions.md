# Companion pets

Some villagers keep an animal. A hunter always has a dog; a guard sometimes does;
the quartermaster sometimes keeps a cat. The pet is a plain vanilla tamed Wolf or
Cat, owned by the villager, and it rides rails that already exist: the villager's
own persona names it and picks its look on the conversation engine
([conversations.md](conversations.md)), the village re-finds it by UUID the way it
re-finds people ([marriage.md](marriage.md)), and its coat and collar are vanilla
entity data the client already renders. It invents no parallel state and adds
nothing to the village save.

The instinct behind it is Aaron's: Minecraft already knows how a tamed wolf or cat
behaves, so a companion is mostly a matter of handing a villager the animal and
letting vanilla do the rest. One thing does not come for free, and the design is
mostly about that one thing (see "Following").

## Who gets one, and when

A pet is granted when a villager takes a job, not when it spawns: a fresh villager
is a `WANDERER` and only later claims an occupation
([population-and-labor.md](population-and-labor.md)), so the grant hangs off the one
chokepoint where occupation is set (`JobClaiming.startJob`).

| Occupation | Pet | Chance |
| --- | --- | --- |
| Hunter | dog | always |
| Guard | dog | 1 in 4 |
| Quartermaster | cat | 1 in 4 |

Two rules keep the grant honest across a working life:

- **One per species.** A villager holds at most one dog and at most one cat. A
  hunter who later guards does not gain a second dog; a hunter who later keeps the
  stores gains a cat, so a villager can end up with one of each, never two of a kind.
- **Rolled once per occupation.** The 1-in-4 is rolled a single time for a given
  occupation on a given villager, so a villager reassigned to the same job again and
  again cannot keep re-rolling until the coin lands. A hunter's dog is certain; a
  guard either drew one or did not.

The bond is to the **person, not the post**. Changing jobs never takes a pet away.
Both facts (which species a villager owns, which occupations it has rolled) live on
the villager's own persistent data, so they survive save and load with no bookkeeping.

## The owner names it

A pet's name, collar colour, and coat are not a rule's to pick; they are the
owner's. Once the animal is handed over, the villager decides, in a single
first-person turn on the shared conversation engine (`PetNaming`, on `Dialogue`),
the same "just ask the villager" pattern that settles a couple's household name
([marriage.md](marriage.md), [conversations.md](conversations.md)), with the
villager's persona as the flavour that steers it. The line is spoken aloud, so a
player nearby hears the villager name their new dog.

The choice is constrained and validated the way the marriage name is: the collar
must be one of the sixteen dye colours, the coat one of vanilla's wolf or cat
variants, and the name a short, cleaned string. A quiet or absent model is no
blocker: the animal is given a random collar, coat, and name the moment it spawns,
and the deliberation only overwrites them if it lands a valid choice. The owner's
voice is honoured when they use it, and the pet is never nameless when they do not.

## Following

A tamed wolf or cat in vanilla follows its owner on its own, but only a **player**
owner: `TamableAnimal.getOwner()` resolves the owner id through the player list, and
a villager is a `PathfinderMob`, never a player. So vanilla's follow, teleport, and
sit-by-owner goals find no owner and quietly do nothing. Rather than force the
animal to see a mob as a player (a change that would touch every tamed animal in the
game), the pet carries one small goal of its own that resolves its owner the way the
rest of the mod resolves a villager: by UUID, through the server
(`ServerLevel#getEntity`), exactly as a married villager finds their spouse on the
road (`FollowSpouseGoal`). Everything else about the animal, its coat, its collar,
its no-despawn, its sitting pose, is vanilla and untouched.

The goal is attached where the mod already attaches goals to freshly-joined vanilla
mobs: the `EntityJoinLevelEvent` handler in `CoreEvents`. Because goals are not
saved, attaching there means the follow behaviour comes back on every world load as
surely as on the first spawn, keyed off a marker on the animal's persistent data.

## When the owner is gone

A pet with no owner to follow, because the owner has wandered out of the loaded
world or has died, does not strike out across the map on vanilla's random stroll.
It is tied to its **village**, whose id it carries, and it keeps to the village:
beyond a tether from the centre it walks back, and within it it mills about like any
resident. So an owner who steps away for a while finds their pet waiting near home,
and an owner who dies leaves behind an animal that lives on as the village's, roaming
it the way a wanderer committed to a village does. This needs no death hook: a dead
owner's id simply never resolves again, and the animal settles permanently into the
village's keeping.

## Persistence

A companion is a normal world entity. It persists itself in its chunk, and it holds
its two ties, its owner and its village, as UUIDs on its own persistent data. The
village stores nothing about it: it is re-linked, when anything needs the link, by
the same UUID resolution the village uses for its people. Because the owner is a
village member and the pet keeps near the owner or the village centre, the pet sits
inside the village's loaded chunks without any forced loading of its own.

## Sit and stay, recall and come

An owner can tell their pet to sit where it is, or to get up and come. This is the
villager's own call, not a rule's and not a player's: now and then the owner's brain
weighs whether to settle their pet or call it back, through the one-shot `decide`
primitive the craft and job decisions use (`PetOrder`; [llm-brain.md](llm-brain.md)).
A hunter can settle their dog while they work and call it back when they leave. The
ask goes out at most once a day, and only once a pet is actually near, so it is a
quiet, occasional thing rather than a fidget; and silence leaves the pet exactly as
it is, so a mute model never moves an animal on its own.

Sitting reuses vanilla's own ordered-to-sit state, so the animal stays put and the
follow goal yields to it. Recalling clears it, and if the animal is far off when
called, it is brought to the owner at once rather than made to trek back, the same
teleport vanilla gives a player's pet that has fallen too far behind.

## What is not here

- **Combat.** A companion is company, not a weapon. A dog does not yet fight
  alongside its hunter or guard; that, and taming wild animals (a horse a villager
  breaks and keeps), are the next lane, deliberately left for later.
- **Re-homing a stray.** An orphaned pet keeps to its village but is not adopted by a
  new owner. Adoption waits on the taming work.

## Code map

- `village/CompanionPets.java`: the grant (occupation roll, per-species cap,
  once-per-occupation), the spawn, the persistent-data ties, the sit/recall
  mechanics, and both ends of the owner bond by UUID (`loadedOwner`, `findOwnedPet`).
- `village/PetNaming.java`: the owner's one-turn naming of collar, coat, and name, on
  the `Dialogue` engine, validated and spoken aloud.
- `entities/PetOrder.java`: the owner's occasional sit-or-recall decision, on the
  one-shot `decide` primitive, default no-op.
- `entities/ai/goals/PetFollowOwnerGoal.java`: the follow, resolving the owner by
  UUID; yields to a sitting pose; teleports when far. Modelled on `FollowSpouseGoal`.
- `entities/ai/goals/PetVillageTetherGoal.java`: keeps an ownerless or owner-away pet
  to its village.
- `events/CoreEvents.java`: attaches the two goals to a marked Wolf/Cat on join, so
  they return on every load.
- `entities/RealPerson.java`: the `maybeOrderPet` trigger, a once-a-day slot in the
  villager's own AI step.
- `village/JobClaiming.java`: the grant hook, in `startJob` after the occupation is set.
