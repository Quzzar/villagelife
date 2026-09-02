# -*- coding: utf-8 -*-
"""Derive the level-2 mine from the shipped level-1 headframe, in every variant.

The level-1 mine is a 7x7 open-sided pavilion over one 3x3 shaft mouth, with the
MINER station in the middle of the mouth (local [3,0,3]). An upgrade is rebuilt on
the same origin corner in the same orientation, so a bigger mine can only grow
toward local +X or +Z; the shaft ramps down toward +Z from the mouth, so a second
mouth behind the first would run one ramp straight over the other. The level-2
mine therefore grows toward +X: a 13x7 pavilion under one long ridged roof with
two mouths six blocks apart (stations [3,0,3] and [9,0,3]), which leaves one block
of rock between the two five-wide ramps MineStep drives, and one chest at each
mouth, as the level-1 has (c63c9ad).

Nothing here is authored per variant. The plains layout is written once, in the
level-1 plains file's own blockstates, and every other variant is produced by the
block-for-block mapping between mine_plains_1 and mine_<variant>_1 at the same
position (spruce for oak in the taiga, packed ice and snow in the snowy, and so
on), so the five level-2 files stay in step with the five level-1 files they
grow out of. Re-run it after changing any of them.

    python3 mine-level-2.py <structure dir> [<out dir>]

Writes mine_<variant>_2.nbt for each variant whose level-1 file exists.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nbt import read
from nbtwrite import write

VARIANTS = ("plains", "taiga", "snowy", "desert", "savanna")
SIZE = (13, 6, 7)
MOUTHS = (3, 9)          # local x of each shaft mouth's centre; the stations sit here
POSTS = (1, 6, 11)       # local x of the roof posts, on rows z=1 and z=5


def grid_of(path):
    """A shipped structure as {(x, y, z): (name, properties, block nbt)}."""
    d = read(path)
    palette = d["palette"]
    out = {}
    for b in d["blocks"]:
        p = palette[b["state"]]
        out[tuple(b["pos"])] = (p["Name"], dict(p.get("Properties", {})), b.get("nbt"))
    return out, d["DataVersion"]


def key_of(cell):
    return (cell[0], tuple(sorted(cell[1].items())))


def plains_layout(l1):
    """The level-2 layout, cell by cell, in the level-1 plains file's own states.

    Every state used here is looked up FROM the level-1 file at a position that
    holds it, so the layout cannot name a block the level-1 palette lacks, and
    the variant mapping below always resolves.
    """
    def at(x, y, z):
        return l1[(x, y, z)]

    air = at(3, 1, 3)
    cobble = at(0, 0, 1)
    step_west, step_mid, step_east = at(2, 0, 0), at(3, 0, 0), at(4, 0, 0)
    lip = at(2, 0, 5)                      # upside-down stair at the back of the mouth
    chest = at(1, 1, 0)
    log = at(1, 1, 1)
    rail_ns = at(1, 1, 2)                  # fence joined north-south (the side rails)
    rail_w, rail_e, rail_we = at(2, 1, 1), at(4, 1, 1), at(2, 1, 5)
    torch = at(1, 4, 1)
    slab_low, slab_high = at(2, 4, 1), at(3, 4, 1)
    lantern = at(3, 4, 3)
    cap_south, cap_east, cap_west, cap_north = at(3, 5, 2), at(2, 5, 3), at(4, 5, 3), at(3, 5, 4)
    planks = at(3, 5, 3)

    W, H, L = SIZE
    g = {}

    # Ground: a cobblestone apron with the four corner cells left absent so the
    # world's own ground shows there (4dd9c6a), a stepped entrance and an
    # upside-down lip at each mouth, and the mouths themselves open.
    for x in range(W):
        for z in range(L):
            if (x, z) in ((0, 0), (W - 1, 0), (0, L - 1), (W - 1, L - 1)):
                continue
            g[(x, 0, z)] = cobble
    for m in MOUTHS:
        g[(m - 1, 0, 0)], g[(m, 0, 0)], g[(m + 1, 0, 0)] = step_west, step_mid, step_east
        g[(m, 0, 1)] = air                                  # the step down into the mouth
        for x in range(m - 1, m + 2):
            for z in (2, 3, 4):
                g[(x, 0, z)] = air                          # the mouth
            g[(x, 0, 5)] = lip

    # Everything above the ground starts as air and is carved into: air is what
    # clears grass and branches out of the footprint when the template is placed.
    for x in range(W):
        for y in range(1, H):
            for z in range(L):
                if (x, z) in ((0, 0), (W - 1, 0), (0, L - 1), (W - 1, L - 1)):
                    continue
                g[(x, y, z)] = air

    # Posts on the front and back rows, three blocks tall, a torch on each.
    for x in POSTS:
        for z in (1, L - 2):
            for y in (1, 2, 3):
                g[(x, y, z)] = log
            g[(x, 4, z)] = torch

    # Rails at head height: the back row closed between the posts, the front row
    # open at each mouth, the two ends joined north-south. The passage between the
    # mouths, under the middle posts, stays open.
    for x in range(2, W - 2):
        if x in POSTS:
            continue
        g[(x, 1, L - 2)] = rail_we
    for m in MOUTHS:
        g[(m - 1, 1, 1)] = rail_w                            # joined to its west only
        g[(m + 1, 1, 1)] = rail_e                            # joined to its east only
    g[(5, 1, 1)] = rail_we                                   # between mouth one and the middle post
    g[(7, 1, 1)] = rail_we                                   # between the middle post and mouth two
    for z in (2, 3, 4):
        g[(1, 1, z)] = rail_ns
        g[(W - 2, 1, z)] = rail_ns

    # One chest at each mouth, on the west side of its entrance (c63c9ad).
    for m in MOUTHS:
        g[(m - 2, 1, 0)] = chest

    # The roof rim at y=4: slabs alternating low and high between the torches,
    # the ends the same as the level-1's. A lantern hangs over each mouth.
    for z in (1, L - 2):
        for x in range(2, W - 2):
            if x in POSTS:
                continue
            g[(x, 4, z)] = slab_high if x % 2 == 1 else slab_low
    for x in (1, W - 2):
        g[(x, 4, 2)], g[(x, 4, 3)], g[(x, 4, 4)] = slab_low, slab_high, slab_low
    for m in MOUTHS:
        g[(m, 4, 3)] = lantern

    # A ridge at y=5 joining the two caps the level-1 wears one of: planks along
    # the ridge line with stairs pitched to either side, and a stair at each end.
    first, last = MOUTHS[0], MOUTHS[-1]
    for x in range(first, last + 1):
        g[(x, 5, 2)] = cap_south
        g[(x, 5, 3)] = planks
        g[(x, 5, 4)] = cap_north
    g[(first - 1, 5, 3)] = cap_east
    g[(last + 1, 5, 3)] = cap_west
    return g


def variant_mapping(plains, variant):
    """plains state -> variant state, read off the two level-1 files position by position."""
    mapping = {}
    for pos, cell in plains.items():
        mapping.setdefault(key_of(cell), variant[pos])
    return mapping


def build(layout, mapping, data_version):
    palette, index, blocks = [], {}, []
    for pos in sorted(layout, key=lambda p: (p[1], p[2], p[0])):
        name, props, nbt = mapping[key_of(layout[pos])]
        k = (name, tuple(sorted(props.items())))
        if k not in index:
            index[k] = len(palette)
            entry = {"Name": name}
            if props:
                entry["Properties"] = dict(props)
            palette.append(entry)
        block = {"pos": list(pos), "state": index[k]}
        if nbt is not None:
            block["nbt"] = nbt
        blocks.append(block)
    return {
        "size": list(SIZE),
        "entities": [],
        "blocks": blocks,
        "palette": palette,
        "DataVersion": data_version,
    }


def main(structure_dir, out_dir):
    plains, data_version = grid_of(os.path.join(structure_dir, "mine_plains_1.nbt"))
    layout = plains_layout(plains)
    for variant in VARIANTS:
        source = os.path.join(structure_dir, "mine_%s_1.nbt" % variant)
        if not os.path.exists(source):
            print("no mine_%s_1.nbt, skipping" % variant)
            continue
        l1, _ = grid_of(source)
        mapping = variant_mapping(plains, l1)
        missing = {key_of(c) for c in layout.values()} - set(mapping)
        if missing:
            raise SystemExit("mine_%s_1 has no block for %s" % (variant, sorted(missing)))
        target = os.path.join(out_dir, "mine_%s_2.nbt" % variant)
        write(target, build(layout, mapping, data_version))
        print("wrote %s: %dx%dx%d, %d cells" % (target, SIZE[0], SIZE[1], SIZE[2], len(layout)))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else sys.argv[1])
