package com.quzzar.kithkyn.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.quzzar.kithkyn.llm.provider.FailedProvider;
import com.quzzar.kithkyn.llm.provider.LlmProvider;
import com.quzzar.kithkyn.llm.provider.LocalRuntimeProvider;

class LlmServiceProviderSelectionTest {

  @Test
  void localNameStillSelectsTheOfflineRuntime() {
    LlmProvider provider = LlmService.createProvider("local");
    try {
      assertInstanceOf(LocalRuntimeProvider.class, provider);
    } finally {
      provider.shutdown();
    }
  }

  @Test
  void invalidNameFailsWithoutSelectingTheOfflineRuntime() {
    LlmProvider provider = LlmService.createProvider("jlama");

    assertInstanceOf(FailedProvider.class, provider);
    assertEquals(LlmService.Status.FAILED, provider.getStatus());
    assertTrue(provider.getStatusDetail().contains("jlama"));
  }

  @Test
  void blankNameAlsoFailsWithoutSelectingTheOfflineRuntime() {
    LlmProvider provider = LlmService.createProvider("  ");

    assertInstanceOf(FailedProvider.class, provider);
    assertEquals(LlmService.Status.FAILED, provider.getStatus());
  }
}
