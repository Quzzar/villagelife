# Authoring a structure

How a building's `.nbt` gets made, now that no external source is derivable
([structure-sourcing.md](structure-sourcing.md)): we author our own, headlessly, with
commands. This is the loop the current `village_center_plains_1` was built with.

## The loop

1. **Pick empty ground** far from anything the simulation touches, and force-load it:
   `forceload add <x1> <z1> <x2> <z2>`. A superflat dev world makes the build plane
   obvious.
2. **Build with fill and setblock**, driving RCON from a script rather than typing. Work
   from one origin corner and offset every coordinate from it, so the whole build is a
   list of relative positions that can be re-run after a mistake. Block states go in
   quotes: `setblock X Y Z "minecraft:red_bed[facing=north,part=head]"`.
3. **Capture it**: `/villagelife save-structure <from> <to> <name>`, where the two
   positions are opposite corners INCLUSIVE. The file lands in
   `<world>/generated/villagelife/structures/<name>.nbt`. Entities are deliberately not
   captured; the existing legacy structures carry broken item frames precisely because
   entities were baked in.
4. **Ship it**: copy that file to `src/main/resources/data/villagelife/structure/<name>.nbt`.
   The id in the building JSON's `structure` field, the file name, and the `.nbt` name are
   all the same string, so a definition and its structure can never drift apart.
5. **Write the definition** JSON beside it, with positions RELATIVE to the structure
   origin (the corner you passed as `from`). Beds, work stations, containers, and the
   gathering point are all origin offsets.
6. **Look at it**: `/villagelife gallery <pos>` places every loaded definition on labelled
   plinths. `/reload` picks up JSON edits without a restart; a new `.nbt` needs a restart.

## What to check before shipping one

- **Rotation.** The gallery places structures as loaded, but villages place them rotated.
  Anything position-sensitive (a gathering point, a work station) must be verified in a
  real village, not only in the gallery. The campfire POI landing correctly after rotation
  is the check that catches this.
- **Nothing that damages a villager where they stand.** The gathering point is beside the
  campfire, never on it, because everything that gathers walks there and idles there.
- **No block entities in the footprint of anything the site scorer must accept**: a chest
  or sign in the way makes a site impossible rather than clearable
  ([site-selection.md](site-selection.md)).

## Why headless

Building in a client with WorldEdit is faster for a human, but the command loop is
reproducible, reviewable in a script, and available to an agent session with no client
attached. A build that exists as a list of commands can be regenerated after a design
change; one that exists only as an `.nbt` cannot.
