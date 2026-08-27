# -*- coding: utf-8 -*-
"""Find blocks that will pop off and drop as items the moment a structure is placed.

Minecraft breaks a block whose support is missing: half a bed with no partner,
half a door, a wall torch on air. A structure carrying any of these looks fine in
the file and litters the floor with items in the world.
"""
import sys, collections
sys.path.insert(0, ".")
from nbt import read
from navcheck import passable

FACE = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}

# Blocks that fall. At y=0 a structure rests on whatever terrain it lands on, so
# a cave or ravine under the footprint drains the floor away and leaves a hole in
# the building. Never use these as flooring.
GRAVITY = {"sand", "red_sand", "gravel", "suspicious_sand", "suspicious_gravel",
           "anvil", "chipped_anvil", "damaged_anvil", "dragon_egg"}
GRAVITY |= {c + "_concrete_powder" for c in
            ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
             "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
             "red", "black")}


def validate(path):
    d = read(path)
    pal, nm = d["palette"], [p["Name"] for p in d["palette"]]
    W, H, L = d["size"]
    at, props = {}, {}
    for b in d["blocks"]:
        p = tuple(b["pos"])
        at[p] = nm[b["state"]]
        props[p] = pal[b["state"]].get("Properties", {})

    def solid(p):
        # A structure rests ON terrain, and every finished building here omits
        # its ground-layer gaps so the world's surface shows through. So a cell
        # missing at or below y=0 is ground, not air; treating it as air made
        # the validator condemn anything standing on a bare patch of floor.
        if p[1] <= 0 and p not in at:
            return True
        return p in at and not passable(at[p])

    bad = []

    for p, n in sorted(at.items()):
        pr = props[p]
        x, y, z = p

        if n.endswith("_bed"):
            dx, dz = FACE[pr.get("facing", "north")]
            # foot's partner lies toward facing; head's lies opposite
            partner = ((x + dx, y, z + dz) if pr.get("part") == "foot"
                       else (x - dx, y, z - dz))
            pn, pp = at.get(partner), props.get(partner, {})
            if pn != n:
                bad.append((p, n, "%s half, no partner at %s (found %s)"
                            % (pr.get("part"), partner, (pn or "nothing").split(":")[-1])))
            elif pp.get("facing") != pr.get("facing") or pp.get("part") == pr.get("part"):
                bad.append((p, n, "partner at %s disagrees (%s/%s vs %s/%s)"
                            % (partner, pp.get("part"), pp.get("facing"),
                               pr.get("part"), pr.get("facing"))))

        elif n.endswith("_door") and not n.endswith("trapdoor"):
            other = (x, y + 1, z) if pr.get("half") == "lower" else (x, y - 1, z)
            on, op = at.get(other), props.get(other, {})
            if on != n:
                bad.append((p, n, "%s half, no partner at %s (found %s)"
                            % (pr.get("half"), other, (on or "nothing").split(":")[-1])))
            elif op.get("facing") != pr.get("facing") or op.get("hinge") != pr.get("hinge"):
                bad.append((p, n, "partner at %s disagrees on facing/hinge" % (other,)))
            if pr.get("half") == "lower" and not solid((x, y - 1, z)):
                bad.append((p, n, "nothing solid underneath at %s" % ((x, y - 1, z),)))

        elif n.endswith("wall_torch") or n == "minecraft:ladder":
            f = pr.get("facing", "north")
            dx, dz = FACE[f]
            anchor = (x - dx, y, z - dz)      # anchor is opposite the way it juts
            if not solid(anchor):
                bad.append((p, n, "no wall behind it at %s (found %s)"
                            % (anchor, (at.get(anchor) or "nothing").split(":")[-1])))

        elif n.endswith("_torch") and "wall" not in n:
            if not solid((x, y - 1, z)):
                bad.append((p, n, "nothing solid underneath"))

        elif n.endswith("_carpet"):
            # Carpet is the lenient one: it survives on ANYTHING that is not
            # air, a trapdoor or a fence included. Requiring a full solid block
            # here flagged a working awning as broken.
            below = (x, y - 1, z)
            if below not in at and y - 1 > 0:
                bad.append((p, n, "nothing at all underneath"))

        elif n.endswith("_pressure_plate") or n == "minecraft:snow":
            if not solid((x, y - 1, z)):
                bad.append((p, n, "nothing solid underneath"))

        if n.split(":")[-1] in GRAVITY:
            if y == 0:
                bad.append((p, n, "falls: gravity block on the ground layer, so it "
                                  "drains away if placed over a cave"))
            elif not solid((x, y - 1, z)):
                bad.append((p, n, "falls: gravity block with nothing solid under it"))

    return W, H, L, bad


if __name__ == "__main__":
    import glob, os
    for path in sys.argv[1:] or sorted(glob.glob(
            "path/to/structure/*.nbt")):
        W, H, L, bad = validate(path)
        name = os.path.basename(path)[:-4]
        print("=== %-18s %dx%dx%d   %s" %
              (name, W, H, L, "OK" if not bad else "%d WILL DROP" % len(bad)))
        for p, n, why in bad:
            print("      %-14s %-22s %s" % (p, n.split(":")[-1], why))
