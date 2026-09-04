# Project identity

The mod is **Kithkyn**, spelled with a **y**. Its website is
[kithkyn.com](https://kithkyn.com), a domain owned by the project author. Source and issues
live in [Quzzar/kithkyn](https://github.com/Quzzar/kithkyn).

## Rename decision

On 2026-09-04, Village Life was renamed to Kithkyn to distinguish it from another Minecraft
mod using the old name. This is a complete internal rename, including the `kithkyn` mod ID,
`com.quzzar.kithkyn` Java package, resource namespaces, config filenames, and commands.
The player command is `/kithkyn`; developer tools live under `/kkdev`.

The author chose a **clean break with existing saves**. There are no old registry aliases,
save migrations, or command aliases. Start a fresh world after replacing the old mod jar.
Existing datapacks and external scripts must use the new namespace and command names.

Configuration now lives in `config/kithkyn-common.toml` and `config/kithkyn-advanced.toml`.
Settings can be copied into those new files while the game is stopped. The offline AI runtime
and model cache now live under `<game directory>/kithkyn/`; an existing cache can be moved
there without downloading the model again. Old files are not loaded automatically.

The original Forge 1.18.2 repository keeps its historical name, `Quzzar/villagelife-legacy`.
References to that repository are intentional.
