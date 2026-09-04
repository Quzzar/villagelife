package com.quzzar.kithkyn.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrictDecisionTest {
  private static final List<String> OPTIONS = List.of("redevelop the farm", "wait");

  @Test
  void onlyAnExactCompleteSelectionCanAuthorizeRedevelopment() {
    var answer = LlmService.parseStrictDecision(
        "{\"reason\":\"The remaining farms suffice.\",\"choice\":1,\"action\":\"redevelop the farm\"}", OPTIONS);
    assertEquals(0, answer.orElseThrow().choiceIndex());
  }

  @Test
  void malformedMissingContradictoryAndFuzzyAnswersCannotAuthorizeRemoval() {
    for (String raw : List.of("1", "redevelop the farm", "{}",
        "{\"reason\":\"yes\",\"choice\":1}",
        "{\"reason\":\"yes\",\"choice\":1,\"action\":\"wait\"}",
        "{\"reason\":\"yes\",\"choice\":1,\"action\":\"redevelop farm\"}",
        "{\"reason\":\"yes\",\"choice\":1.5,\"action\":\"redevelop the farm\"}",
        "{\"reason\":\"yes\",\"choice\":9,\"action\":\"redevelop the farm\"}",
        "{\"reason\":\"yes\",\"choice\":1,\"action\":")) {
      assertTrue(LlmService.parseStrictDecision(raw, OPTIONS).isEmpty(), raw);
    }
  }
  @org.junit.jupiter.api.Test
  void rejectsLenientSyntaxAndContradictoryDuplicateMembers() {
    var options = java.util.List.of("redevelop the farm", "wait");
    org.junit.jupiter.api.Assertions.assertTrue(LlmService.parseStrictDecision(
        "{reason:'ok',choice:1,action:'redevelop the farm'}", options).isEmpty());
    org.junit.jupiter.api.Assertions.assertTrue(LlmService.parseStrictDecision(
        "{\"reason\":\"wait\",\"choice\":2,\"action\":\"wait\",\"choice\":1,\"action\":\"redevelop the farm\"}",
        options).isEmpty());
    org.junit.jupiter.api.Assertions.assertTrue(LlmService.parseStrictDecision(
        "{\"reason\":\"ok\",\"choice\":1,\"action\":\"redevelop the farm\"} /* trailing comment */",
        options).isEmpty());
  }
  @org.junit.jupiter.api.Test
  void rejectsRawStringControlCharacters() {
    var options = java.util.List.of("redevelop the farm", "wait");
    org.junit.jupiter.api.Assertions.assertTrue(LlmService.parseStrictDecision(
        "{\"reason\":\"line one\nline two\",\"choice\":1,\"action\":\"redevelop the farm\"}", options).isEmpty());
  }
}
