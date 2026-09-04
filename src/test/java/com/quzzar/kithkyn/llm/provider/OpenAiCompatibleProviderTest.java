package com.quzzar.kithkyn.llm.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.quzzar.kithkyn.llm.provider.LlmProvider.CompletionRequest;

class OpenAiCompatibleProviderTest {

  @Test
  void lunaLeavesSamplingAndReasoningAtApiDefaults() {
    OpenAiCompatibleProvider provider = provider(OpenAiCompatibleProvider.OPENAI);
    try {
      JsonObject body = provider.requestBody(request());

      assertEquals("gpt-5.6-luna", body.get("model").getAsString());
      assertTrue(body.has("max_completion_tokens"));
      assertFalse(body.has("temperature"));
      assertFalse(body.has("frequency_penalty"));
      assertFalse(body.has("reasoning_effort"));
    } finally {
      provider.shutdown();
    }
  }

  @Test
  void openAiCompatibleProvidersCanKeepSamplingControls() {
    OpenAiCompatibleProvider provider = provider(OpenAiCompatibleProvider.DEEPSEEK);
    try {
      JsonObject body = provider.requestBody(request());

      assertEquals(0.4D, body.get("temperature").getAsDouble());
      assertEquals(0.3D, body.get("frequency_penalty").getAsDouble());
      assertTrue(body.has("max_tokens"));
    } finally {
      provider.shutdown();
    }
  }

  private static OpenAiCompatibleProvider provider(OpenAiCompatibleProvider.Spec spec) {
    return new OpenAiCompatibleProvider(spec, () -> "test-key", () -> "");
  }

  private static CompletionRequest request() {
    return new CompletionRequest("system", "user", List.of(), 64, 0.4D, 0.3D);
  }
}
