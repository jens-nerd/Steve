package com.steve.ai.llm.async;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous Anthropic (Claude) API client using Java HttpClient's sendAsync().
 *
 * <p>Provides non-blocking calls to Anthropic's Messages API.
 * Uses CompletableFuture to return immediately without blocking the calling thread.</p>
 *
 * <p><b>API Endpoint:</b> https://api.anthropic.com/v1/messages</p>
 *
 * <p><b>Supported Models:</b></p>
 * <ul>
 *   <li>claude-haiku-4-5 (default — fastest, cheapest)</li>
 *   <li>claude-sonnet-4-6 (balanced)</li>
 *   <li>claude-opus-4-8 (most capable)</li>
 * </ul>
 *
 * <p><b>Note on temperature:</b> Haiku 4.5 and Sonnet 4.6 accept the {@code temperature}
 * parameter. Opus 4.7/4.8 reject it (HTTP 400). The default model accepts it, so this
 * client always sends it; switch the model with that in mind.</p>
 *
 * <p><b>Thread Safety:</b> Thread-safe. HttpClient is thread-safe and immutable.</p>
 *
 * @since 1.2.0
 */
public class AsyncClaudeClient implements AsyncLLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncClaudeClient.class);
    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String PROVIDER_ID = "claude";

    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;

    /**
     * Constructs an AsyncClaudeClient.
     *
     * @param apiKey      Anthropic API key (required)
     * @param model       Model to use (e.g., "claude-haiku-4-5", "claude-sonnet-4-6")
     * @param maxTokens   Maximum tokens in response (e.g., 1000)
     * @param temperature Response randomness (0.0 - 1.0, lower = more deterministic)
     * @throws IllegalArgumentException if apiKey is null or empty
     */
    public AsyncClaudeClient(String apiKey, String model, int maxTokens, double temperature) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Anthropic API key cannot be null or empty");
        }

        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        LOGGER.info("AsyncClaudeClient initialized (model: {}, maxTokens: {}, temperature: {})",
            model, maxTokens, temperature);
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        String requestBody = buildRequestBody(prompt, params);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ANTHROPIC_API_URL))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(30))
            .build();

        LOGGER.debug("[claude] Sending async request (prompt length: {} chars)", prompt.length());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                long latencyMs = System.currentTimeMillis() - startTime;

                if (response.statusCode() != 200) {
                    LLMException.ErrorType errorType = determineErrorType(response.statusCode());
                    boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;

                    LOGGER.error("[claude] API error: status={}, body={}", response.statusCode(),
                        truncate(response.body(), 200));

                    throw new LLMException(
                        "Anthropic API error: HTTP " + response.statusCode(),
                        errorType,
                        PROVIDER_ID,
                        retryable
                    );
                }

                return parseResponse(response.body(), latencyMs);
            });
    }

    /**
     * Builds the JSON request body for the Anthropic Messages API.
     *
     * <p>Unlike OpenAI, the system prompt is a top-level {@code system} field rather than
     * a message with role "system".</p>
     *
     * @param prompt User prompt
     * @param params Additional parameters (can override defaults)
     * @return JSON string
     */
    String buildRequestBody(String prompt, Map<String, Object> params) {
        JsonObject body = new JsonObject();

        String modelToUse = (String) params.getOrDefault("model", this.model);
        int maxTokensToUse = (int) params.getOrDefault("maxTokens", this.maxTokens);
        double tempToUse = (double) params.getOrDefault("temperature", this.temperature);

        body.addProperty("model", modelToUse);
        body.addProperty("max_tokens", maxTokensToUse);
        body.addProperty("temperature", tempToUse);

        // System prompt is a top-level field in the Anthropic API
        String systemPrompt = (String) params.get("systemPrompt");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            body.addProperty("system", systemPrompt);
        }

        // Messages array contains only the user turn
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messages.add(userMessage);

        body.add("messages", messages);

        return body.toString();
    }

    /**
     * Parses the Anthropic Messages API response.
     *
     * @param responseBody Raw JSON response
     * @param latencyMs    Request latency
     * @return Parsed LLMResponse
     * @throws LLMException if response cannot be parsed
     */
    LLMResponse parseResponse(String responseBody, long latencyMs) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            // Extract content from content[0].text
            if (!json.has("content") || json.getAsJsonArray("content").isEmpty()) {
                throw new LLMException(
                    "Anthropic response missing 'content' array",
                    LLMException.ErrorType.INVALID_RESPONSE,
                    PROVIDER_ID,
                    false
                );
            }

            JsonObject firstBlock = json.getAsJsonArray("content").get(0).getAsJsonObject();
            String content = firstBlock.get("text").getAsString();

            // Extract token usage (input_tokens + output_tokens)
            int tokensUsed = 0;
            if (json.has("usage")) {
                JsonObject usage = json.getAsJsonObject("usage");
                int inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : 0;
                int outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : 0;
                tokensUsed = inputTokens + outputTokens;
            }

            LOGGER.debug("[claude] Response received (latency: {}ms, tokens: {})", latencyMs, tokensUsed);

            return LLMResponse.builder()
                .content(content)
                .model(model)
                .providerId(PROVIDER_ID)
                .latencyMs(latencyMs)
                .tokensUsed(tokensUsed)
                .fromCache(false)
                .build();

        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("[claude] Failed to parse response: {}", truncate(responseBody, 200), e);
            throw new LLMException(
                "Failed to parse Anthropic response: " + e.getMessage(),
                LLMException.ErrorType.INVALID_RESPONSE,
                PROVIDER_ID,
                false,
                e
            );
        }
    }

    /**
     * Determines the error type based on the HTTP status code.
     *
     * @param statusCode HTTP status code
     * @return Corresponding ErrorType
     */
    private LLMException.ErrorType determineErrorType(int statusCode) {
        return switch (statusCode) {
            case 429 -> LLMException.ErrorType.RATE_LIMIT;
            case 401, 403 -> LLMException.ErrorType.AUTH_ERROR;
            case 400 -> LLMException.ErrorType.CLIENT_ERROR;
            case 408 -> LLMException.ErrorType.TIMEOUT;
            default -> {
                if (statusCode >= 500) {
                    yield LLMException.ErrorType.SERVER_ERROR;
                }
                yield LLMException.ErrorType.CLIENT_ERROR;
            }
        };
    }

    /**
     * Truncates a string for logging.
     *
     * @param str       String to truncate
     * @param maxLength Maximum length
     * @return Truncated string
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return "[null]";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isHealthy() {
        // Base client is always healthy; resilience layer checks circuit breaker
        return true;
    }
}
