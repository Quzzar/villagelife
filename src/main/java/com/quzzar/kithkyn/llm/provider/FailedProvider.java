package com.quzzar.kithkyn.llm.provider;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.quzzar.kithkyn.llm.LlmService;

/**
 * A configured provider that cannot start. It keeps configuration mistakes in
 * the ordinary unavailable-provider path without silently starting a local
 * model or preventing the game server from running.
 */
public final class FailedProvider implements LlmProvider {

  private final String detail;

  public FailedProvider(String detail) {
    this.detail = detail;
  }

  @Override
  public void start() {
    // The failure is final for this game process. Correcting config needs the
    // same restart as every other provider change.
  }

  @Override
  public void shutdown() {
    // No resources were acquired.
  }

  @Override
  public LlmService.Status getStatus() {
    return LlmService.Status.FAILED;
  }

  @Override
  public String getStatusDetail() {
    return detail;
  }

  @Override
  public CompletableFuture<Optional<String>> complete(CompletionRequest request) {
    return CompletableFuture.completedFuture(Optional.empty());
  }
}
