# -*- coding: utf-8 -*-
"""Map the hand-built plains couple's cottage into the other biome families.

The plains cottage (couple_cottage_plains_1) is authored by hand; the four other
variants are derived from it block-for-block, the way mine-level-2.py derives the
level-2 mine: only the biome-specific materials change (oak -> spruce or acacia,
cobblestone -> the biome's stone, the bed to the biome's colour), and everything
else, the glass, lanterns, jukebox, pots, flowers, and the whole garden and roof
shape, is carried over unchanged. Re-run it whenever the plains cottage changes.

Block-entity tags in the source are all empty (an unstocked jukebox, an empty
chest, plain pots), so they are dropped: the game recreates the default block
entity on placement.

    python3 couple-cottage-variants.py [<structure dir>]

Writes couple_cottage_<variant>_1.nbt for taiga, snowy, desert, and savanna, plus
each one's building-definition JSON.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nbt import read
from nbtwrite import write

# The wood family and the substitutions each biome makes against the plains cottage.
# Anything not named here is carried over unchanged.
VARIANTS = {
    "taiga": {"wood": "spruce", "bed": "green_bed", "stone": "cobblestone"},
    "snowy": {"wood": "spruce", "bed": "light_blue_bed", "stone": "stone_bricks"},
    "desert": {"wood": "acacia", "bed": "orange_bed", "stone": "cut_sandstone"},
    "savanna": {"wood": "acacia", "bed": "yellow_bed", "stone": "cobblestone"},
}

# Plains wood blocks, by their suffix after the wood name.
OAK_BLOCKS = {
    "minecraft:oak_planks": "{w}_planks",
    "minecraft:oak_stairs": "{w}_stairs",
    "minecraft:oak_door": "{w}_door",
    "minecraft:oak_fence": "{w}_fence",
    "minecraft:oak_slab": "{w}_slab",
    "minecraft:oak_pressure_plate": "{w}_pressure_plate",
    "minecraft:stripped_oak_log": "stripped_{w}_log",
    # Aaron's spruce trapdoor accent follows the biome wood too.
    "minecraft:spruce_trapdoor": "{w}_trapdoor",
}


def substitution(spec):
    """The full plains-name -> variant-name map for one biome."""
    wood = spec["wood"]
    out = {name: tmpl.format(w=wood) for name, tmpl in OAK_BLOCKS.items()}
    out["minecraft:red_bed"] = "minecraft:" + spec["bed"]
    if spec["stone"] != "cobblestone":
        out["minecraft:cobblestone"] = "minecraft:" + spec["stone"]
    # Normalise the wood entries to full ids.
    return {k: (v if v.startswith("minecraft:") else "minecraft:" + v) for k, v in out.items()}


def build_variant(source, subs):
    """The plains structure with its palette names remapped and block-entity tags dropped."""
    palette, index, blocks = [], {}, []
    for b in source["blocks"]:
        entry = source["palette"][b["state"]]
        name = subs.get(entry["Name"], entry["Name"])
        props = entry.get("Properties", {})
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            out = {"Name": name}
            if props:
                out["Properties"] = dict(props)
            palette.append(out)
        blocks.append({"pos": list(b["pos"]), "state": index[key]})
    return {
        "size": list(source["size"]),
        "entities": [],
        "blocks": blocks,
        "palette": palette,
        "DataVersion": source["DataVersion"],
    }


def main(structure_dir):
    buildings_dir = os.path.normpath(os.path.join(
        structure_dir, "..", "villagelife", "buildings"))
    plains_nbt = os.path.join(structure_dir, "couple_cottage_plains_1.nbt")
    plains_json = os.path.join(buildings_dir, "couple_cottage_plains_1.json")
    source = read(plains_nbt)
    template = json.load(open(plains_json))

    for variant, spec in VARIANTS.items():
        nbt_path = os.path.join(structure_dir, "couple_cottage_%s_1.nbt" % variant)
        write(nbt_path, build_variant(source, substitution(spec)))

        definition = dict(template)
        definition["structure"] = "couple_cottage_%s_1" % variant
        definition["variant"] = variant
        json_path = os.path.join(buildings_dir, "couple_cottage_%s_1.json" % variant)
        with open(json_path, "w") as f:
            json.dump(definition, f, indent=2)
            f.write("\n")
        print("wrote couple_cottage_%s_1 (.nbt + .json)" % variant)


if __name__ == "__main__":
    default_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                               "..", "..", "src", "main", "resources", "data", "villagelife", "structure")
    main(os.path.normpath(sys.argv[1] if len(sys.argv) > 1 else default_dir))
