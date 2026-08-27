# -*- coding: utf-8 -*-
"""Read a Litematica .litematic into a plain {(x,y,z): (name, props)} grid.

Litematica packs block indices into a long array with ceil(log2(palette)) bits
each, and unlike Minecraft's own chunk format an entry MAY straddle two longs,
so the reader has to stitch across the boundary. Index order is y, then z,
then x.
"""
import sys, math
sys.path.insert(0, ".")
from nbt import read

MASK64 = (1 << 64) - 1


def _u64(v):
    return v & MASK64


def grid(path, region=None):
    d = read(path)
    regions = d["Regions"]
    name = region or next(iter(regions))
    r = regions[name]
    sx, sy, sz = abs(r["Size"]["x"]), abs(r["Size"]["y"]), abs(r["Size"]["z"])
    palette = r["BlockStatePalette"]
    longs = [_u64(v) for v in r["BlockStates"]]
    bits = max(2, (len(palette) - 1).bit_length())
    mask = (1 << bits) - 1

    def entry(i):
        start = i * bits
        a, b = start >> 6, ((i + 1) * bits - 1) >> 6
        off = start & 63
        if a == b:
            return (longs[a] >> off) & mask
        return ((longs[a] >> off) | (longs[b] << (64 - off))) & mask

    out, i = {}, 0
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                p = palette[entry(i)]
                if p["Name"] != "minecraft:air":
                    out[(x, y, z)] = (p["Name"], p.get("Properties"))
                i += 1
    return out, (sx, sy, sz), d


if __name__ == "__main__":
    from collections import Counter
    g, size, d = grid(sys.argv[1])
    print("size %dx%dx%d, %d non-air blocks (metadata says %d)"
          % (size[0], size[1], size[2], len(g), d["Metadata"]["TotalBlocks"]))
    for k, v in Counter(n.split(":")[-1] for n, _ in g.values()).most_common(30):
        print("  %3d %s" % (v, k))
