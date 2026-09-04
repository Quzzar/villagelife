package com.quzzar.kithkyn.llm.provider;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.quzzar.kithkyn.llm.LlmService;

/**
 * One model backend. Selected by config at startup; everything above this seam
 * (LlmService's decide/chat/persona APIs, queue priorities, prompt building,
 * reply parsing) is provider-agnostic — callers never learn which provider
 * answered (Model provider system map #30, ticket #34).
 *
 * Providers own their request timeouts and retries: a returned future always
 * completes, with empty on any failure.
 */
public interface LlmProvider {

  /**
   * @param system      the system prompt
   * @param user        the user message
   * @param examples    true few-shot example turns, possibly empty — small
   *                    offline models need them; cloud providers send them as
   *                    ordinary messages
   * @param maxNewTokens generation budget for the reply
   * @param temperature sampling temperature; providers whose API rejects it
   *                    (Anthropic's newest models) omit it
   */
  record CompletionRequest(String system, String user, List<LlmService.FewShotExample> examples,
      int maxNewTokens, double temperature, double frequencyPenalty) {

    /**
     * No repetition penalty, which is what every caller but chat wants: their
     * replies are JSON, and JSON repeats its own structural tokens by design.
     */
    public CompletionRequest(String system, String user, List<LlmService.FewShotExample> examples,
        int maxNewTokens, double temperature) {
      this(system, user, examples, maxNewTokens, temperature, 0.0D);
    }
  }

  /** Begin starting up (async); safe to call repeatedly and after failure. */
  void start();

  /** Stop and release resources; called on JVM shutdown. */
  void shutdown();

  LlmService.Status getStatus();

  String getStatusDetail();

  CompletableFuture<Optional<String>> complete(CompletionRequest request);

}
