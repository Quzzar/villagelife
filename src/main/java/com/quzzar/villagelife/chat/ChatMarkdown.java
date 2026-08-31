package com.quzzar.villagelife.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Renders the light markdown the language models tend to write - **bold**,
 * *italic*, __bold__, _italic_, ~~struck~~ - into Minecraft text formatting, so a
 * villager's reply reads as styled text rather than literal asterisks.
 *
 * <p>A marker with no closing pair is left as plain text, so a stray punctuation
 * mark (a lone "*", "2 * 3") is never mistaken for formatting. Spans nest: a bold
 * span holding an italic one comes out bold AND italic, because a child component
 * inherits the parent's style for anything it does not set itself.
 */
public final class ChatMarkdown {

  private ChatMarkdown() {
  }

  /** An opening delimiter, its matched content, and where it ends in the source. */
  private record Span(String inner, ChatFormatting format, int end) {
  }

  /** The text as a styled component: markdown markers become Minecraft formatting. */
  public static MutableComponent render(String text) {
    MutableComponent out = Component.empty();
    StringBuilder plain = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      Span span = spanAt(text, i);
      if (span == null) {
        plain.append(text.charAt(i));
        i++;
        continue;
      }
      if (plain.length() > 0) {
        out.append(Component.literal(plain.toString()));
        plain.setLength(0);
      }
      // Recurse so a bold span can hold an italic one, and both styles apply.
      out.append(render(span.inner()).withStyle(span.format()));
      i = span.end();
    }
    if (plain.length() > 0) {
      out.append(Component.literal(plain.toString()));
    }
    return out;
  }

  /** A markdown span opening at {@code i}, or null if none does. */
  private static Span spanAt(String s, int i) {
    if (s.startsWith("**", i)) {
      return close(s, i, "**", ChatFormatting.BOLD);
    }
    if (s.startsWith("__", i)) {
      return close(s, i, "__", ChatFormatting.BOLD);
    }
    if (s.startsWith("~~", i)) {
      return close(s, i, "~~", ChatFormatting.STRIKETHROUGH);
    }
    if (s.charAt(i) == '*') {
      return close(s, i, "*", ChatFormatting.ITALIC);
    }
    if (s.charAt(i) == '_') {
      return close(s, i, "_", ChatFormatting.ITALIC);
    }
    return null;
  }

  /** The span from the opening delimiter at {@code i} to its next match, or null. */
  private static Span close(String s, int i, String delim, ChatFormatting format) {
    int start = i + delim.length();
    int end = s.indexOf(delim, start);
    if (end <= start) {
      return null; // no closing pair, or an empty span: not formatting
    }
    return new Span(s.substring(start, end), format, end + delim.length());
  }
}
