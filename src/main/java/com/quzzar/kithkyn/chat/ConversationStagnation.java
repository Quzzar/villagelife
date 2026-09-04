package com.quzzar.kithkyn.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detects when alternating model voices are paraphrasing themselves instead of
 * moving a conversation forward.
 *
 * <p>Each voice is compared only with its own recent lines. That avoids calling
 * an ordinary answer repetitive merely because it responds to the subject the
 * other speaker introduced. Six repeated turns in one uninterrupted run means
 * both sides have spent three exchanges restating old material, which is enough
 * evidence to close the session without cutting off a naturally recurring
 * phrase.
 */
final class ConversationStagnation {

  private static final int HISTORY_PER_VOICE = 4;
  private static final int REPEATED_TURNS_TO_END = 6;
  private static final int MINIMUM_TERMS = 3;
  private static final double REPEATED_OVERLAP = 0.60D;

  private static final Set<String> STOP_WORDS = Set.of(
      "a", "an", "and", "are", "as", "at", "be", "been", "but", "by",
      "do", "for", "from", "had", "has", "have", "he", "her", "hers",
      "him", "his", "i", "if", "in", "is", "it", "its", "me", "my",
      "of", "on", "or", "our", "ours", "she", "so", "that", "the",
      "their", "theirs", "them", "then", "they", "this", "to", "us",
      "was", "we", "were", "while", "will", "with", "you", "your",
      "yours");

  private final List<Deque<Set<String>>> recentByVoice;
  private int repeatedTurns;

  ConversationStagnation(int voices) {
    if (voices <= 0) {
      throw new IllegalArgumentException("A conversation must have at least one voice");
    }
    this.recentByVoice = new ArrayList<>(voices);
    for (int i = 0; i < voices; i++) {
      this.recentByVoice.add(new ArrayDeque<>());
    }
  }

  /**
   * Records one spoken line and answers whether the talk has stopped making
   * progress for long enough to end.
   */
  boolean record(int voice, String line) {
    if (voice < 0 || voice >= this.recentByVoice.size()) {
      throw new IllegalArgumentException("Voice index is outside this conversation");
    }
    Set<String> terms = terms(line);
    Deque<Set<String>> recent = this.recentByVoice.get(voice);
    boolean repeated = terms.size() >= MINIMUM_TERMS
        && recent.stream().anyMatch(previous -> overlap(terms, previous) >= REPEATED_OVERLAP);

    this.repeatedTurns = repeated ? this.repeatedTurns + 1 : 0;
    recent.addLast(terms);
    while (recent.size() > HISTORY_PER_VOICE) {
      recent.removeFirst();
    }
    return this.repeatedTurns >= REPEATED_TURNS_TO_END;
  }

  private static double overlap(Set<String> first, Set<String> second) {
    if (first.isEmpty() || second.isEmpty()) {
      return 0.0D;
    }
    int shared = 0;
    for (String term : first) {
      if (second.contains(term)) {
        shared++;
      }
    }
    return (double) shared / Math.min(first.size(), second.size());
  }

  private static Set<String> terms(String line) {
    String normalized = line == null ? "" : line.toLowerCase(Locale.ROOT)
        .replace('’', '\'')
        .replaceAll("'s\\b", "")
        .replaceAll("[^a-z0-9]+", " ");
    Set<String> terms = new HashSet<>();
    for (String word : normalized.split("\\s+")) {
      if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
        terms.add(stem(word));
      }
    }
    return terms;
  }

  private static String stem(String word) {
    String stem = word;
    if (stem.length() > 5 && stem.endsWith("ing")) {
      stem = stem.substring(0, stem.length() - 3);
    } else if (stem.length() > 4 && stem.endsWith("ed")) {
      stem = stem.substring(0, stem.length() - 2);
    } else if (stem.length() > 4 && stem.endsWith("es")) {
      stem = stem.substring(0, stem.length() - 2);
    } else if (stem.length() > 3 && stem.endsWith("s")) {
      stem = stem.substring(0, stem.length() - 1);
    }
    int length = stem.length();
    if (length >= 2 && stem.charAt(length - 1) == stem.charAt(length - 2)) {
      stem = stem.substring(0, length - 1);
    }
    if (stem.endsWith("bounc")) {
      return stem + "e";
    }
    return stem;
  }
}
