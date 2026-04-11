package com.rsscopilot.server.feed;

public final class LanguageHeuristics {

  private LanguageHeuristics() {}

  public static boolean isForeign(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    int cjkCount = 0;
    int letterCount = 0;
    for (char character : text.toCharArray()) {
      if (Character.isLetter(character)) {
        letterCount++;
      }
      if (isCjk(character)) {
        cjkCount++;
      }
    }
    if (letterCount == 0) {
      return false;
    }
    return ((double) cjkCount / (double) letterCount) < 0.15d;
  }

  private static boolean isCjk(char character) {
    Character.UnicodeBlock unicodeBlock = Character.UnicodeBlock.of(character);
    return unicodeBlock == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || unicodeBlock == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        || unicodeBlock == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
        || unicodeBlock == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
  }
}
