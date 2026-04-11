package com.rsscopilot.server.feed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LanguageHeuristicsUnitTest {

  @Test
  void shouldTreatChineseContentAsNotForeign() {
    assertThat(LanguageHeuristics.isForeign("这是一篇中文文章，主要讨论产品设计和工程实现。")).isFalse();
  }

  @Test
  void shouldTreatEnglishContentAsForeign() {
    assertThat(
            LanguageHeuristics.isForeign(
                "This article discusses engineering tradeoffs and long-form analysis."))
        .isTrue();
  }

  @Test
  void shouldTreatBlankContentAsNotForeign() {
    assertThat(LanguageHeuristics.isForeign("  ")).isFalse();
    assertThat(LanguageHeuristics.isForeign(null)).isFalse();
  }

  @Test
  void shouldTreatMostlyChineseMixedContentAsNotForeign() {
    assertThat(LanguageHeuristics.isForeign("这是一段中文 mixed with a few English words.")).isFalse();
  }
}
