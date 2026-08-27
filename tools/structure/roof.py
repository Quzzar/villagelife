# -*- coding: utf-8 -*-
"""Work out which way a roof stair should face, from the roof's own geometry.

A roof stair points UPHILL: its tall half sits against the higher part of the
roof and its step drops away toward the eave. So for an exposed roof cell, the
facing is whichever direction the roof continues to rise, found by looking for
an occupied neighbour one layer up. Corners have no such neighbour and fall back
to pointing at the ridge.

This is derived from, and unit-tested against, the hand-built tier 1 roof.
"""
DIRS = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}


def roof_facing(layer, p):
    """Which slope of a hipped roof does this cell belong to, and so which way
    does its stair point?

    layer: every occupied cell at this cell's own height. A cell sits on the
    slope whose eave it is nearest: right on the x-edge of the layer means it is
    part of an east/west slope, right on the z-edge means part of a hip end.
    Ties go to the long slopes, which is what a rectangular roof reads as.
    """
    x, y, z = p
    xs = [c[0] for c in layer]
    zs = [c[2] for c in layer]
    # The ridge of THIS layer, not of the whole structure: each layer of a
    # hipped roof steps inward, so its centre line moves with it.
    ridge_x = (min(xs) + max(xs)) / 2
    ridge_z = (min(zs) + max(zs)) / 2
    to_x_eave = min(x - min(xs), max(xs) - x)
    to_z_eave = min(z - min(zs), max(zs) - z)
    if to_x_eave <= to_z_eave:
        return "east" if x < ridge_x else "west"
    return "south" if z < ridge_z else "north"


if __name__ == "__main__":
    import sys
    sys.path.insert(0, ".")
    from nbt import read

    D = "path/to/structure/"
    d = read(D + "house_plains_1.nbt")
    pal, nm = d["palette"], [p["Name"] for p in d["palette"]]
    W, H, L = d["size"]
    occ, stairs, layers = set(), {}, {}
    for b in d["blocks"]:
        n = nm[b["state"]]
        if n == "minecraft:air":
            continue
        p = tuple(b["pos"])
        occ.add(p)
        layers.setdefault(p[1], []).append(p)
        if n.endswith("_stairs"):
            stairs[p] = pal[b["state"]]["Properties"]["facing"]

    ok = bad = 0
    for p, actual in sorted(stairs.items()):
        if p[1] < 4:                      # roof only; ignore the chair downstairs
            continue
        pred = roof_facing(layers[p[1]], p)
        if pred == actual:
            ok += 1
        else:
            bad += 1
            print("   MISMATCH %s actual=%-6s predicted=%s" % (p, actual, pred))
    print("tier 1 roof: %d correct, %d wrong out of %d" % (ok, bad, ok + bad))
