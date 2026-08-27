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
./gradlew runClient -Puipreview=trade
./gradlew runClient -Puipreview=chat
```

The client boots, opens the named face of the villager screen over sample data, writes
`run/screenshots/ui-<mode>.png`, and quits. It never loads a world, so it does not take the
session lock and will not collide with a running dev server. Read the PNG directly.

Takes roughly two to four minutes, most of it client startup.

## How it works

`client/gui/UiPreview.java` waits for the loading overlay to clear, opens `PersonChatScreen`
with a fixed sample payload, lets the screen settle, then calls `Screenshot.grab` and stops
the client.

Two traps are already paid for, and both cost an hour the first time:

- **The flag must be forwarded through the run config.** `-Dfoo=bar` on a Gradle command sets
  the property on Gradle's own JVM. ModDevGradle forks the client as a separate process,
  which never sees it. `build.gradle` maps `-Puipreview` onto a `systemProperty` for exactly
  this reason. Use `-P`, not `-D`.
- **Do not wait for `TitleScreen`.** A first launch shows accessibility onboarding first, and
  a harness that waits for a title screen that never arrives will sit at 3% CPU forever
  looking like a hang. Wait for the overlay to clear instead.

## Sample data, not live data

The payload is authored to contain the awkward cases: a long item name, a two-digit stack, a
column with fewer rows than its neighbour. A live village shows whatever it happens to hold
that minute, which is usually the easy case, and the easy case is not what breaks layouts.

Add a case here whenever a layout bug gets through, so the next screenshot would have caught it.

## Related

- Screens live in `client/gui/`. `PersonChatScreen` is the villager screen: chat on one tab,
  the market stall on the other for a merchant at a staffed market.
- The stall's contents and pricing are [economy.md](economy.md); this file is only about
  seeing them.
