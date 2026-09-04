package com.quzzar.kithkyn.chat;

import java.util.Locale;

/** Pure topic and action gates for village-planning conversation turns. */
final class VillageChangeIntent {

  private static final String[] REQUEST_MARKERS = {
      "should build", "should make", "let's build", "let us build", "build a ", "build the ",
      "build some ", "build more ", "build walls", "build a wall", "put up a ", "we need a ",
      "we need more ", "we need walls", "start building", "save up for", "save for a ",
      "prioriti", "focus on building", "instead of building", "the village should", "ought to build",
      "you lot should", "you should build"
  };

  private static final String[] PLANNING_SUBJECTS = {
      "build", "house", "home", "housing", "lodging", "bed", "farm", "fishery", "workshop",
      "wall", "village plan", "save for"
  };

  private VillageChangeIntent() {
  }

  static boolean proposes(String playerLine) {
    String line = playerLine.toLowerCase(Locale.ROOT);
    for (String marker : REQUEST_MARKERS) {
      if (line.contains(marker)) {
        return true;
      }
    }
    return line.matches(".*\\bwe (need|want|could use|ought to have) (some |more |a |an )?"
        + "(houses?|homes?|housing|lodging|beds?)\\b.*");
  }

  static boolean discusses(String playerLine) {
    if (proposes(playerLine)) {
      return true;
    }
    String line = playerLine.toLowerCase(Locale.ROOT);
    for (String subject : PLANNING_SUBJECTS) {
      if (line.contains(subject)) {
        return true;
      }
    }
    return false;
  }
}
