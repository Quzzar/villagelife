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
- `relationships/RelationshipCommands`: `/vldev relationships show <entity>` (permission 2).
- Storage: `VillageBrain`'s codec (additive `relationships` field); accessors delegate
  through `Village` (`putRelationship`, `getRelationship`, `relationshipsOf`).

## Consumers

Chat briefings include "People in your life": the person's 3 strongest pairs by
|effective opinion|, feeling word from the opinion band, flavor line verbatim (see the
conversation map). Future consumers (AI goals reacting to fondness, gift preferences)
read `opinionOf`.

## What generation may and may not do

Tightened after the audit on [#21](https://github.com/Quzzar/villagelife/issues/21), which
measured the first live webs and found the content wanting where the mechanism was sound.

- **Flavour is about the pair, or it is nothing.** The prompt now forbids describing one
  villager on their own, and a line that names one of the two and not the other is dropped
  before storage: better to keep the numbers and lose the sentence than to let a villager
  describe their neighbour to themselves. The audit found two thirds of lines were
  biographies wearing a relationship's clothes.
- **Asymmetry has to mean something.** The prompt asks for it in roughly one pair in ten,
  and the parse only honours the flag when the two leans actually diverge — a model that
  marks a pair asymmetric while giving both people the same small lean has described a
  symmetric pair, and believing it would make asymmetry meaningless everywhere it appears.
- **Generation meets people on their first day**, so it may not hand out devotion or
  loathing: values are capped at 60 either way. Anything stronger is earned, by drift or by
  something that happened between them.

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
other may not know it happened at all. Game code never sets these numbers, with one
exception below. It records what happened and stops:

| Game code records | The villager decides |
| --- | --- |
| "Narius killed the skeleton that was coming for me." | whether that earns gratitude, and how much |
| "I lost the miner job to Isolde; the village said she was better suited." | whether that is resentment or fair enough |
| "Quzzar threw me 3 diamonds." | whether that was a gift, a bribe, or litter |

**The one mechanical exception is being struck by a player.** Pain needs no
interpretation, so the blow itself moves the victim's own opinion the moment it lands: a
configurable base (`AssaultOpinionHit`) plus the damage dealt, applied through the same
clamped `OpinionService` call, every strike counting. One punch is a small grievance that
fades; a beating carries the victim past the grudge line (`GrudgeAttackBelow`), and from
there that villager treats the player as an enemy on sight. Fighters attack, and
retaliation alerts the neighbours; everyone else keeps their distance. Witnesses to the
assault still go through reflection like anything else, and the victim's own log entry
("I was attacked by ...") still gets reflected on too: the mechanical part is only what
they feel in their skin before they have had time to think. The village-scale consequences
(standing, and its hostile rung) are on [economy.md](economy.md).

**The clamp on a judgement is load-bearing, not decoration.** On its first live outing the
model was asked for a number between -15 and 15 and answered 100. `OpinionService` caps a
single judgement at 15 either way, which is the only reason one dramatic evening does not
rewrite a relationship. Do not remove it because the prompt says a range: the prompt says a
range and the model exceeds it in practice.

Reflection is where the deciding happens: every few minutes one villager per village reads
the log entries they have not yet felt anything about, and their brain answers with a
change per person and a short reason. The answer is applied through `OpinionService`, which
is the single tool for "how I feel about someone" and takes any UUID, so a fellow villager
and a player go through exactly the same call. Only the storage differs: feelings about a
resident live as that person's private lean on the pair, feelings about a player live on
the villager. The `"opinion"` field a villager may return mid-conversation is the same tool,
invoked while talking instead of while thinking. A villager may also answer words with blows:
`"fight": true` in the reply picks a minute-long quarrel with the player
(villager-conversations.md), the villager's own decision rather than a threshold of opinion.
Grudges and the standing tiers remain what the numbers do on their own.

If the model is unavailable or answers unusably, the entries are marked considered and the
villager carries on unchanged: quiet, not wrong.

Drift is deliberately weak and bounded to +/-55, so the strong feelings in a village stay
the ones the model authored with a reason attached, and drift is the slow pressure of
ordinary life around them. It never writes flavour text: a pair that exists only because
two people shovelled the same field for a week has no story, and should not pretend to.

Both one-sided moments also write the villager's personal log, so the next time you talk
to them they can tell you about it themselves.

