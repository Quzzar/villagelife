# Villager personas

**Implemented.** Every villager that enters the world carries an AI-generated persona: a
2-3 sentence third-person character blurb plus one observable-habit quirk line, generated
by the in-game LLM (see [llm-brain.md](llm-brain.md)) from the villager's rolled identity.
Design history and decision detail live on the
[persona pipeline wayfinder map](https://github.com/Quzzar/villagelife/issues/1);
this doc is the operational summary.

## The contract

- **Inputs**: full name, gender, personality, pronounced virtues, and the stat block
  verbalized as words only (scores 8-13 are silent; genetic conditions always speak).
  **Occupation is deliberately excluded**: personas are generated before the villager has
  a job, and jobs churn under the campfire model. The blurb must not invent one.
- **Output wire format**: two tagged plain-text lines, `BLURB:` and `QUIRK:`. Parsed
  leniently (case-insensitive, markdown decoration stripped, stray lines ignored); both
  tags present = success, anything else = failed generation.
- **Model**: the mod's default decide() model (Llama-3.2-1B), temperature 0.5, ~120
  output tokens, roughly 3 s per persona on the native path.

## Lifecycle: generate-before-spawn

The persona is generated *before* the entity is added to the world: roll the person
(genetics in the `Person` constructor, identity in `finalizeSpawn`) without spawning,
request the persona, and only `addFreshEntity` once it arrives. A failed generation
discards the rolled person entirely (a "skipped arrival"). Consequences:

- A spawned villager with an empty persona is impossible by construction; there is no
  fallback text, one retry on an unparseable answer, and no regeneration afterwards (a
  persona is rolled once, ever).
- While the LLM is down, villages stop growing; this matches the LLM-required design.
- Persona requests queue behind decide() calls (`LlmService.submitPersona`): one persona
  in flight at a time and any foreground work preempts it — decisions AND player
  conversation — so spawn bursts cannot stall
  village decisions.

## Code map

All in `persona/` (plus one registration line in `entities/VillagelifeAttachments.java`):

| Class | Role |
| --- | --- |
| `PersonaData` | The attachment payload (`villagelife:persona`): blurb, quirk, model, timing, prompt version |
| `PersonaPrompts` | System prompt, one-shot example, and the stat/virtue verbalization ladder |
| `PersonaParser` | Lenient tagged-line parser |
| `PersonaService` | Generation orchestration on `LlmService.submitPersona`; attach/get helpers |
| `PersonaSpawner` | **The** generate-before-spawn pipeline: `trySpawn(level, pos, configure)` rolls, generates, spawns on success, discards on failure. Server thread in, server thread out. |
| `PersonaCommands` | `/vldev persona audit <n>`, `/vldev persona show <entity>`, `/vldev persona judgetest` (permission 2) |
| `PersonaAuditRun` | The audit command's loop: N serialized `PersonaSpawner` attempts, each scored by the judge, plus a report file in `<game dir>/villagelife/` |
| `PersonaJudge` | Cloud judge that scores a blurb per trait: conveyed / contradicted / absent (issue #77) |

Village arrival mechanics (campfire map) call the same `PersonaSpawner.trySpawn`, setting
village membership in the `configure` hook. One pipeline, two callers; do not fork it.

## Quality scoring: the judge (issue #77)

`/vldev persona audit <n>` scores every generated blurb against the traits it was asked to
convey. Scoring is a **cloud judge**, not keyword matching: for each intended trait the judge
returns *conveyed* / *contradicted* / *absent*. Keyword matching failed both ways — it scored
good paraphrase (`"a mountain of a man"` for `"a true giant"`) as a miss, and could not see a
genuinely inverted trait (`"often sick"` for `"never ill"`), which scored the same as a faithful
one. **Contradicted** is the bucket that catches the inversion, and the reason a model is needed.

- **Judge model**: cloud, because a small local model cannot reliably make this call (measured;
  it is the same weakness that motivated the issue). Configured by three dedicated keys in
  `villagelife-common.toml`, separate from the villagers' model so the game can run a local
  model while the judge calls the cloud: `Persona judge provider` (claude / openai / deepseek),
  `Persona judge API key`, `Persona judge model`. A blank key runs the audit unscored.
- **Traits** come from `PersonaPrompts.buildTraits` (the structured list behind the sheet — do
  not split the sheet string on `", "`, some phrases carry an internal comma).
- **Report**: an aggregate line (conveyed %, contradicted, absent) plus, per persona, the named
  contradictions and absences. Written to `<game dir>/villagelife/persona-audit-<ts>.txt`.
- `/vldev persona judgetest` runs a deterministic selftest of the reply parsing, no cloud call.

## Not yet built

(Relationships were listed here and are now built; see
[relationships.md](relationships.md).)

- ~~Relationships~~ — built; see [relationships.md](relationships.md).
- Personas feeding decide() prompts as villager context.
- Player-facing persona UI (explicitly out of the persona map's scope).
