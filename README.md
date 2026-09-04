# Kithkyn

Welcome! This mod introduces AI NPCs into your Minecraft world, adding a dynamic and autonomous village-building experience.

[Website](https://kithkyn.com) · [Source](https://github.com/Quzzar/kithkyn) · [Issues](https://github.com/Quzzar/kithkyn/issues)

Now running on **Minecraft 1.21.1 / NeoForge** (ported from the original [Forge 1.18.2 version](https://github.com/Quzzar/villagelife-legacy)).

Kithkyn replaces Village Life with a new mod ID. Start a new world; old mod saves are not
compatible. See [project identity](docs/project-identity.md) for the naming and compatibility decision.

## Features

- **Autonomous NPCs**: AI villagers ("people") with generated names, genders, personalities, and virtues that shape how they behave.
- **Dynamic Villages**: Villages plan and construct their own buildings, assign jobs and beds, and keep an internal event log.
- **Occupations**: Guards, farmers, lumberjacks, miners, builders, clerics, blacksmiths, and more — each with its own AI goals.
- **Quests and Interaction**: Engage with the villagers, form relationships, and manage guards (work in progress!).

## Development

Requires Java 21 (the Gradle toolchain provisions it automatically). Useful tasks:

```
./gradlew build       # build the mod jar into build/libs/
./gradlew runClient   # launch a dev client
./gradlew runServer   # launch a dev server
```

Debug triggers while testing: placing a **diamond block** founds a new village (instantly builds a town center and spawns its first villager); placing an **emerald block** instant-builds a well with marker blocks for beds/jobs/containers.

## Contributing

We welcome contributions! If you're interested in improving the Kithkyn mod, please feel free to fork the repository, make your changes, and submit a pull request.

---

With the recent developments in generative AI, I plan to revisit this mod.

Kithkyn is not affiliated with Mojang or Microsoft.
