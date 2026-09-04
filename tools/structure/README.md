# Structure tooling

The pipeline that turns a raw schematic or Litematica capture into a
`kithkyn` structure `.nbt` the datapack can place. Dev tooling, not mod
code — it lives outside `src/` and is never shipped in the jar.

**Licensing:** the source→licence position for every structure set (which are
reference-only, which are used by permission, which are free) lives in
[`docs/structure-sourcing.md`](../../docs/structure-sourcing.md). Read it before
adopting anything from a third-party set. These tools carry no structure data
themselves — they only transform files you point them at.

## The pipeline

```
.schematic / .litematic  ──►  flatten  ──►  schem2nbt  ──►  validate + navcheck
   (raw capture)          (old ids →      (→ 1.21.1 .nbt)    (is it placeable
                          blockstates)                        and walkable?)
```

| Tool | Does |
| --- | --- |
| `nbt.py` / `nbtwrite.py` | Read and write raw NBT — the format every other tool speaks |
| `litematic.py` | Read a Litematica `.litematic` into a `{(x,y,z): (name, props)}` grid (handles the bit-packed palette that straddles longs) |
| `schem2nbt.py` | Convert an MCEdit `.schematic` to a 1.21.1 structure `.nbt` |
| `flatten.py` | Map pre-1.13 numeric block ids to modern blockstates |
| `split_scene.py` | Crop the separate buildings out of one multi-building `.schematic` (flood-fill footprints; rejects trees by composition) |
| `validate.py` | Flag blocks that would drop on placement — bed and door halves, wall torches, gravity-affected stacks, carpet on nothing |
| `navcheck.py` | Score how walkable a finished structure is for a villager |
| `roof.py` | Fix roof-stair facing |
| `mine-level-2.py` | Derive the level-2 mine, in every family, from the shipped level-1 files |

Each script's `__main__` is an example driver; point the glob at your own
structure directory. Run them from this directory so their imports resolve.
