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
   list of relative positions that can be re-run after a mistake.

   **Never quote a block id.** `setblock X Y Z minecraft:red_bed[facing=north,part=head]`
   is correct; wrapping it in quotes makes the command parser reject it. Any shell
   escaping belongs outside the command text, not inside it. This exact mistake silently
   emptied the first village center: every stateful block (beds, chest, campfire, bell,
   door, torches) failed while the plain walls and floor succeeded, so the build looked
   finished and was a shell.

   **A build script's own success count proves nothing.** Failed setblocks come back as
   ordinary command output, not as errors a naive filter catches. Verify the world, then
   verify the capture (step 7).
3. **Capture it**: `/vldev village save-structure <from> <to> <name>`, where the two
   positions are opposite corners INCLUSIVE. The file lands in
   `<world>/generated/villagelife/structures/<name>.nbt`. Entities are deliberately not
   captured; the existing legacy structures carry broken item frames precisely because
   entities were baked in.
4. **Ship it**: copy that file to `src/main/resources/data/villagelife/structure/<name>.nbt`.
   The id in the building JSON's `structure` field, the file name, and the `.nbt` name are
   all the same string, so a definition and its structure can never drift apart.
5. **Write the definition** JSON beside it, with positions RELATIVE to the structure
   origin (the corner you passed as `from`). Beds, work stations, containers, personal containers (a home's own chest, the rule in
   [building-spec.md](building-spec.md)), and the gathering point are all origin offsets.
6. **Look at it**: `/vldev village gallery <pos>` places every loaded definition on labelled
   plinths. `/reload` picks up JSON edits without a restart; a new `.nbt` needs a restart.
7. **Verify the palette**, always, before shipping. The decompressed NBT contains every
   block id as plain text, so a raw string search answers "did this block actually make
   it in" without an NBT library:

   ```
   python3 -c "import gzip,sys;t=gzip.open(sys.argv[1],'rb').read().decode('latin-1');
   print([b for b in ['red_bed','chest','campfire','bell'] if 'minecraft:'+b not in t])" FILE.nbt
   ```

   It prints what is MISSING. A structure whose definition promises four beds and whose
   palette has no bed is the failure this catches: the simulation reads bed coordinates
   from the JSON, so attractiveness cheerfully reports four free beds while a villager
   walks to bare floor.

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

## The ground layer: leave what you are not building

A structure is seated with one layer on the ground's top block (layer 0, or layer `sink` when
the definition declares one), and every block recorded in that layer replaces the ground there,
air included. A template that records air around its floor at that layer therefore digs a
one-block pit ring into the grass wherever it is placed. Found 2026-09-01 in the bakeries,
taverns, fisheries and level-1 watchtowers, and taken out of all twenty files: the ground layer
should hold only what the building actually puts on or in the ground, and cells that are not the
building's are left absent so the world's own ground stays. The mine is the one deliberate
exception: its ground-layer air is the shaft mouth. Above the ground layer, air is wanted, it is
what clears grass, flowers and branches out of the footprint.

Seating is checked offline rather than by eye: a door's lower half should sit one layer above
the ground layer, beds and work stations likewise. Fisheries and level-1 watchtowers carried an
extra course below that and now declare `"sink": 1`; taverns and bakeries keep their floor a
step above the ground on purpose.
