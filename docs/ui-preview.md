# UI preview

How to look at a client screen without playing the game.

## Why this exists

Every version of the villager screen before this harness was written blind, shipped unseen,
and was wrong. Not subtly wrong: a panel stretched to near-fullscreen, two columns marooned
at opposite edges of a grey void, stack counts drawn in white on top of a white bevel, a send
button that rendered as the letter K. All of it obvious within one second of looking, and
none of it findable by reading the code, because the bugs live in the gap between what the
source says and what the pixels do.

Reasoning about a layout is not looking at it. If you are changing anything under
`client/gui/`, take the screenshot.

## Running it

```bash
./gradlew runClientJoinLocal -Puipreview=trade
./gradlew runClientJoinLocal -Puipreview=chat
./gradlew runClientJoinLocal -Puipreview=age-lineup
./gradlew runClientJoinLocal -Puipreview=age-lineup-world
```

Start the local development server first. The preview client joins it as `Dev`, opens the named
preview over controlled sample data, writes `run-preview/screenshots/ui-<mode>.png`, and quits.
The separate `run-preview/` game directory keeps its config and session files away from the server.
Read the PNG directly.

Takes roughly two to four minutes, most of it client startup.

## How it works

`client/gui/UiPreview.java` waits for the loading overlay to clear, opens the requested screen,
lets it settle, then calls `Screenshot.grab` and stops the client. Chat and trade use a fixed
`PersonChatScreen` payload. `age-lineup` creates four unspawned client-side people with identical
appearance inputs, changes only their age stage, and renders them through the real entity renderer.
`age-lineup-world` briefly spawns the same controlled stages in front of the preview player so the
real entity attachments, name line, role line, camera perspective, and model scale are photographed
together. The tagged entities are removed immediately after the capture.

Two traps are already paid for, and both cost an hour the first time:

- **The flag must be forwarded through the run config.** `-Dfoo=bar` on a Gradle command sets
  the property on Gradle's own JVM. ModDevGradle forks the client as a separate process,
  which never sees it. `build.gradle` maps `-Puipreview` onto a `systemProperty` for exactly
  this reason. Use `-P`, not `-D`.
- **Wait for the joined world.** Screens need a player inventory and entity previews need a client
  level. Waiting at the title screen cannot exercise either real path.

## Sample data, not live data

The screen payload is authored to contain the awkward cases: a long item name, a two-digit stack,
and a column with fewer rows than its neighbour. The age lineup holds appearance and attributes
constant so stage geometry is the only visual variable. A live village shows whatever it happens
to hold that minute, which is usually the easy case, and the easy case is not what breaks layouts.

Add a case here whenever a layout bug gets through, so the next screenshot would have caught it.

## Related

- Screens live in `client/gui/`. `PersonChatScreen` is the villager screen: chat on one tab,
  the market stall on the other for a merchant at a staffed market.
- The stall's contents and pricing are [economy.md](economy.md); this file is only about
  seeing them.
