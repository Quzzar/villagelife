package com.quzzar.villagelife.llm.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.llm.LlmService;

/**
 * Cloud provider speaking the OpenAI chat-completions dialect. Serves both
 * OpenAI proper and DeepSeek (deliberately OpenAI-compatible; only the base
 * URL, key, and token-cap field differ). The base URL living in the spec also
 * quietly keeps the door open for other clones of this endpoint.
 *
 * The API key is a secret: it must never reach logs, status strings, or
 * exceptions — at most the last four characters for troubleshooting.
 */
public final class OpenAiCompatibleProvider implements LlmProvider {

  /**
   * @param name                   display name ("OpenAI", "DeepSeek")
   * @param baseUrl                e.g. "https://api.openai.com" — endpoint is
   *                               baseUrl + "/v1/chat/completions"
   * @param defaultModel           model used when the config leaves it blank
   * @param useMaxCompletionTokens OpenAI's newest models require
   *                               max_completion_tokens and reject max_tokens;
   *                               DeepSeek still speaks max_tokens
   * @param sendMinimalReasoning   send reasoning_effort: minimal (OpenAI
   *                               reasoning models; hidden reasoning tokens
   *                               count against the cap)
   */
  public record Spec(String name, String baseUrl, String defaultModel,
      boolean useMaxCompletionTokens, boolean sendMinimalReasoning) {
  }

  // sendMinimalReasoning stays false: gpt-5.6-luna hard-rejects
  // reasoning_effort=minimal (probed live); the generous token cap covers
  // hidden reasoning instead.
  public static final Spec OPENAI = new Spec("OpenAI", "https://api.openai.com", "gpt-5.6-luna", true, false);
  public static final Spec DEEPSEEK = new Spec("DeepSeek", "https://api.deepseek.com", "deepseek-chat", false, false);

  private static final int MAX_RETRIES = 2;

  private final Spec spec;
  private final Supplier<String> apiKey;
  private final Supplier<String> configuredModel;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  private final AtomicReference<LlmService.Status> status = new AtomicReference<>(LlmService.Status.NOT_LOADED);
  private volatile String statusDetail = "";

  public OpenAiCompatibleProvider(Spec spec, Supplier<String> apiKey, Supplier<String> configuredModel) {
    this.spec = spec;
    this.apiKey = apiKey;
    this.configuredModel = configuredModel;
  }

  private String model() {
    String configured = configuredModel.get();
    return configured == null || configured.isBlank() ? spec.defaultModel() : configured;
  }

  @Override
  public void start() {
    if (apiKey.get() == null || apiKey.get().isBlank()) {
      statusDetail = "no API key configured for " + spec.name()
          + ". Paste your key into villagelife-common.toml (treat that file like a password).";
      status.set(LlmService.Status.FAILED);
      return;
    }
    statusDetail = spec.name() + ", model " + model();
    status.set(LlmService.Status.READY);
    LlmService.get().onProviderReady();
  }

  @Override
  public void shutdown() {
  }

  @Override
  public LlmService.Status getStatus() {
    return status.get();
  }

  @Override
  public String getStatusDetail() {
    return statusDetail;
  }

  @Override
  public CompletableFuture<Optional<String>> complete(CompletionRequest request) {
    JsonObject body = new JsonObject();
    body.addProperty("model", model());
    JsonArray messages = new JsonArray();
    messages.add(message("system", request.system()));
    for (LlmService.FewShotExample example : request.examples()) {
      messages.add(message("user", example.user()));
      messages.add(message("assistant", example.assistant()));
    }
    messages.add(message("user", request.user()));
    body.add("messages", messages);
    // Reasoning models spend hidden tokens against the cap, so it sits well
    // above the visible reply budget.
    int cap = Math.max(1024, request.maxNewTokens() * 4);
    body.addProperty(spec.useMaxCompletionTokens() ? "max_completion_tokens" : "max_tokens", cap);
    if (spec.sendMinimalReasoning()) {
      body.addProperty("reasoning_effort", "minimal");
    }
    body.addProperty("temperature", request.temperature());

    return attempt(body.toString(), 0);
  }

  private static JsonObject message(String role, String content) {
    JsonObject message = new JsonObject();
    message.addProperty("role", role);
    message.addProperty("content", content);
    return message;
  }

  private CompletableFuture<Optional<String>> attempt(String body, int tryNumber) {
    HttpRequest httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(spec.baseUrl() + "/v1/chat/completions"))
        .timeout(Duration.ofSeconds(45))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + apiKey.get())
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
        .thenCompose(response -> handleResponse(response, body, tryNumber))
        .exceptionallyCompose(t -> {
          Villagelife.LOGGER.warn("{} request failed: {}", spec.name(), t.getMessage());
          return retryOrEmpty(body, tryNumber);
        });
  }

  private CompletableFuture<Optional<String>> handleResponse(HttpResponse<String> response, String body,
      int tryNumber) {
    int code = response.statusCode();
    if (code == 200) {
      try {
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
        String finishReason = choice.has("finish_reason") ? choice.get("finish_reason").getAsString() : "";
        var content = choice.getAsJsonObject("message").get("content");
        String text = content != null && !content.isJsonNull() ? content.getAsString() : "";
        if (text.isBlank() && "length".equals(finishReason)) {
          Villagelife.LOGGER.warn("{}: reply truncated before any visible text (finish_reason=length)", spec.name());
          return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.completedFuture(text.isBlank() ? Optional.empty() : Optional.of(text));
      } catch (Exception e) {
        Villagelife.LOGGER.warn("{}: unparseable response body", spec.name(), e);
        return CompletableFuture.completedFuture(Optional.empty());
      }
    }

    String errorCode = extractErrorCode(response.body());
    if (code == 401) {
      statusDetail = "invalid API key for " + spec.name() + " (…" + maskedKey() + ")";
      status.set(LlmService.Status.FAILED);
      return CompletableFuture.completedFuture(Optional.empty());
    }
    if (code == 429 && "insufficient_quota".equals(errorCode)) {
      statusDetail = spec.name() + " account is out of quota; top up billing";
      status.set(LlmService.Status.FAILED);
      return CompletableFuture.completedFuture(Optional.empty());
    }
    if (code == 429 || code >= 500) {
      Villagelife.LOGGER.warn("{}: HTTP {} ({}), retrying", spec.name(), code, errorCode);
      return retryOrEmpty(body, tryNumber);
    }
    Villagelife.LOGGER.warn("{}: HTTP {} ({})", spec.name(), code, errorCode);
    return CompletableFuture.completedFuture(Optional.empty());
  }

  private CompletableFuture<Optional<String>> retryOrEmpty(String body, int tryNumber) {
    if (tryNumber >= MAX_RETRIES) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    long backoffSeconds = tryNumber == 0 ? 2 : 5;
    return CompletableFuture.supplyAsync(() -> null,
        CompletableFuture.delayedExecutor(backoffSeconds, TimeUnit.SECONDS))
        .thenCompose(ignored -> attempt(body, tryNumber + 1));
  }

  private static String extractErrorCode(String body) {
    try {
      return JsonParser.parseString(body).getAsJsonObject()
          .getAsJsonObject("error").get("code").getAsString();
    } catch (Exception e) {
      return "";
    }
  }

  private String maskedKey() {
    String key = apiKey.get();
    return key == null || key.length() < 4 ? "????" : key.substring(key.length() - 4);
  }

}
