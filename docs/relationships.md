# Villager relationships

**Implemented.** Villagers hold pairwise opinions of each other: a fondness value in
-100..100 plus one LLM-written flavor sentence. Design history on the
[persona pipeline map](https://github.com/Quzzar/villagelife/issues/1) (model decided
on its relationship ticket); this is the operational summary.

## The model

- **One pair, one object.** A relationship is stored once per unordered pair: a shared
  `value`, small per-person `lean`s (clamped to +-15, or +-40 when the model deliberately
  marks the pair `asymmetric`), and a flavor line. A person's effective opinion is
  `value + their lean`. Because one generation produces the whole pair, two villagers'
  views of each other are roughly aligned *by construction*.
- **Absence means neutral.** Pairs with |value| below 10 are never stored; the store
  self-prunes at village scale. A dead or removed villager's pairs are dropped.
- **Static in v1.** Opinions never change after generation. Drift from bookkeeper events
  is recorded map fog for a post-audit decision.

## The integration pass

Each newcomer gets exactly ONE pass, at campfire arrival (`Village.confirmArrival`),
riding the low-priority LLM queue behind decisions and chat:

1. **Selection**: the roster is pre-filtered to at most 12 one-line descriptors
   (coworkers first, then a shuffle), and the model names up to 5 residents the newcomer
   would form opinions about. Names are fuzzy-matched against the real roster; unmatched
   names are dropped.
2. **One call per selected pair**: both descriptors in, one JSON object out
   (`value`, `lean_a`, `lean_b`, `asymmetric`, `flavor`). Malformed or out-of-range
   output discards that pair, decide-style.

Existing residents never re-run selection; the newcomer's pass creates those pairs.
Founders bond with founders: earlier arrivals have fewer, older bonds, accepted as a
feature.

## Code map

- `relationships/RelationshipPair`: the pair record, canonical unordered key, clamping,
  `opinionOf(uuid)`.
- `relationships/RelationshipService`: the two-stage pass on `LlmService.submitPersona`
  with few-shot examples.
- `relationships/RelationshipCommands`: `/vlrel show <entity>` (permission 2).
- Storage: `VillageBrain`'s codec (additive `relationships` field); accessors delegate
  through `Village` (`putRelationship`, `getRelationship`, `relationshipsOf`).

## Consumers

Chat briefings include "People in your life": the person's 3 strongest pairs by
|effective opinion|, feeling word from the opinion band, flavor line verbatim (see the
conversation map). Future consumers (AI goals reacting to fondness, gift preferences)
read `opinionOf`.

## What changes a relationship after generation

Generation writes the web once, on the day a villager arrives. These are the forces that
move it afterwards, and the split between them is the design:

**Mutual drift** moves the pair's shared value, because both people lived the same thing.
It runs on the village tick, so it only advances while the village is actually simulated:

| Shared experience | Every pass |
| --- | --- |
| Working the same building | +1 |
| Idling at the same campfire with no work | +1 |

**One-sided change** moves only that person's lean, because only they experienced it. The
other may not know it happened at all:

| Moment | Change |
| --- | --- |
| Someone kills a mob that was hunting you | **+6** toward them |
| You lose your job to someone better suited | **-8** toward them |

Drift is deliberately weak and bounded to +/-55, so the strong feelings in a village stay
the ones the model authored with a reason attached, and drift is the slow pressure of
ordinary life around them. It never writes flavour text: a pair that exists only because
two people shovelled the same field for a week has no story, and should not pretend to.

Both one-sided moments also write the villager's personal log, so the next time you talk
to them they can tell you about it themselves.

