# Kithkyn

<!-- quzzar-skills:start -->
## Agent skills

**Before starting any work in this repo, read the `quzzar-workplace` skill.** It holds the
conventions for issues, branches, commits, PRs, and review, plus the code rules that apply
at every layer: naming, file organization, comments, types, error handling, dependencies.

Then, before writing code, read the skill for the layer you're touching:

- **`quzzar-frontend`**: UI, components, styling, client state, data fetching, forms,
  accessibility, frontend tests.
- **`quzzar-backend`**: API routes, server code, database queries, migrations, validation,
  auth, background jobs, backend tests.

Read the layer skill *before* writing, not as a check afterwards. A change spanning both
layers needs both.

For visual work, also read **`quzzar-design`**: the visual plan that comes before the code,
and the render-screenshot-critique loop. Never report a visual change as done without having
looked at it rendered.

### Stack

<!-- filled in by /setup from the repo itself. Correct it by hand if it drifts -->

- Package manager: Gradle 8.9 wrapper (`./gradlew`), ModDevGradle 1.0.21
- Backend / runtime: NeoForge 21.1.72 mod for Minecraft 1.21.1, Java 21 toolchain
- Test: JUnit 5 (`./gradlew test`; also included in `./gradlew check`)
- Typecheck: `./gradlew compileJava`
<!-- quzzar-skills:end -->

### Issue tracker

Issues live in GitHub Issues (`Quzzar/kithkyn`), via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage labels, used as-is (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root, created lazily by `/domain-modeling`. See `docs/agents/domain.md`.

A NeoForge mod for Minecraft 1.21.1: autonomous AI villagers that build and develop their
own villages. Java 21, Gradle (`./gradlew build`, `./gradlew runClient` to launch a dev
client). Mod id `kithkyn`, package `com.quzzar.kithkyn`.

The codebase is mid-refactor: it is being modernized, and its mechanics are being brought in
line with the decided designs in `docs/`.

## Read the docs first

`docs/README.md` indexes the design docs. **The docs describe the target design; the code
does not always match it yet.** Where they disagree, the doc is the intent and the code is
what the refactor is fixing.

- `docs/population-and-labor.md`: the campfire model for villager spawning and job
  assignment. Required reading before touching `village/`, `entities/`, or any spawn or
  job-assignment logic.
- `docs/genetics-and-attributes.md`: per-villager genetic variation via the attribute
  system. Required reading before touching entity attributes, spawn finalization, or
  anything stat-related on `Person`.

## Layout

- `village/`: the village simulation. `Village` (per-village state and tick),
  `VillageBrain` (assignment and inventory logic), `village/buildings/` (structure
  placement and construction), `village/bookkeeping/` (event log feeding village mood).
- `entities/`: the `Person`/`RealPerson` entity and its AI goals under `entities/ai/goals/`.
- `savedata/`: world save persistence (`VillageManagerSaveData`, codec/NBT format).
- `src/main/resources/data/kithkyn/kithkyn/buildings/`: datapack JSON building
  definitions consumed by `village/buildings/`.
- `client/`: renderers, models, GUI.
- `networking/`, `events/`, `configuration/`: the usual mod plumbing.
