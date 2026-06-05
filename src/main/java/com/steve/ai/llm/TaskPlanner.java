package com.steve.ai.llm;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.async.*;
import com.steve.ai.llm.resilience.LLMFallbackHandler;
import com.steve.ai.llm.resilience.ResilientLLMClient;
import com.steve.ai.memory.WorldKnowledge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class TaskPlanner {
    // Legacy synchronous clients (for backward compatibility)
    private final OpenAIClient openAIClient;
    private final GeminiClient geminiClient;
    private final GroqClient groqClient;

    // NEW: Async resilient clients
    private final AsyncLLMClient asyncOpenAIClient;
    private final AsyncLLMClient asyncGroqClient;
    private final AsyncLLMClient asyncGeminiClient;
    private final AsyncLLMClient asyncClaudeClient;
    private final LLMCache llmCache;
    private final LLMFallbackHandler fallbackHandler;

    public TaskPlanner() {
        // Legacy clients
        this.openAIClient = new OpenAIClient();
        this.geminiClient = new GeminiClient();
        this.groqClient = new GroqClient();

        // Initialize async infrastructure
        this.llmCache = new LLMCache();
        this.fallbackHandler = new LLMFallbackHandler();

        // Initialize async clients with resilience wrappers.
        // Keys default to a placeholder so an unconfigured provider (e.g. an empty OpenAI
        // key when running Claude-only) fails at call time with HTTP 401 instead of
        // throwing during construction of every base client.
        String apiKey = orPlaceholder(SteveConfig.OPENAI_API_KEY.get());
        String model = SteveConfig.OPENAI_MODEL.get();
        int maxTokens = SteveConfig.MAX_TOKENS.get();
        double temperature = SteveConfig.TEMPERATURE.get();

        String anthropicApiKey = orPlaceholder(SteveConfig.ANTHROPIC_API_KEY.get());
        String anthropicModel = SteveConfig.ANTHROPIC_MODEL.get();

        // Create base async clients
        AsyncLLMClient baseOpenAI = new AsyncOpenAIClient(apiKey, model, maxTokens, temperature);
        AsyncLLMClient baseGroq = new AsyncGroqClient(apiKey, "llama-3.1-8b-instant", 500, temperature);
        AsyncLLMClient baseGemini = new AsyncGeminiClient(apiKey, "gemini-1.5-flash", maxTokens, temperature);
        AsyncLLMClient baseClaude = new AsyncClaudeClient(anthropicApiKey, anthropicModel, maxTokens, temperature);

        // Wrap with resilience patterns
        this.asyncOpenAIClient = new ResilientLLMClient(baseOpenAI, llmCache, fallbackHandler);
        this.asyncGroqClient = new ResilientLLMClient(baseGroq, llmCache, fallbackHandler);
        this.asyncGeminiClient = new ResilientLLMClient(baseGemini, llmCache, fallbackHandler);
        this.asyncClaudeClient = new ResilientLLMClient(baseClaude, llmCache, fallbackHandler);

        SteveMod.LOGGER.info("TaskPlanner initialized with async resilient clients");
    }

    public ResponseParser.ParsedResponse planTasks(SteveEntity steve, String command) {
        try {
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            WorldKnowledge worldKnowledge = new WorldKnowledge(steve);
            String userPrompt = PromptBuilder.buildUserPrompt(steve, command, worldKnowledge);
            
            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            SteveMod.LOGGER.info("Requesting AI plan for Steve '{}' using {}: {}", steve.getSteveName(), provider, command);
            
            String response = getAIResponse(provider, systemPrompt, userPrompt);
            
            if (response == null) {
                SteveMod.LOGGER.error("Failed to get AI response for command: {}", command);
                return null;
            }            ResponseParser.ParsedResponse parsedResponse = ResponseParser.parseAIResponse(response);
            
            if (parsedResponse == null) {
                SteveMod.LOGGER.error("Failed to parse AI response");
                return null;
            }
            
            SteveMod.LOGGER.info("Plan: {} ({} tasks)", parsedResponse.getPlan(), parsedResponse.getTasks().size());
            
            return parsedResponse;
            
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error planning tasks", e);
            return null;
        }
    }

    private String getAIResponse(String provider, String systemPrompt, String userPrompt) {
        String response = switch (provider) {
            case "groq" -> groqClient.sendRequest(systemPrompt, userPrompt);
            case "gemini" -> geminiClient.sendRequest(systemPrompt, userPrompt);
            case "openai" -> openAIClient.sendRequest(systemPrompt, userPrompt);
            default -> {
                SteveMod.LOGGER.warn("Unknown AI provider '{}', using Groq", provider);
                yield groqClient.sendRequest(systemPrompt, userPrompt);
            }
        };

        if (response == null && !provider.equals("groq")) {
            SteveMod.LOGGER.warn("{} failed, trying Groq as fallback", provider);
            response = groqClient.sendRequest(systemPrompt, userPrompt);
        }

        return response;
    }

    /**
     * Asynchronously plans tasks for Steve using the configured LLM provider.
     *
     * <p>This method returns immediately with a CompletableFuture, allowing the game thread
     * to continue without blocking. The actual LLM call is executed on a separate thread pool
     * with full resilience patterns (circuit breaker, retry, rate limiting, caching).</p>
     *
     * <p><b>Non-blocking:</b> Game thread is never blocked</p>
     * <p><b>Resilient:</b> Automatic retry, circuit breaker, fallback on failure</p>
     * <p><b>Cached:</b> Repeated prompts may hit cache (40-60% hit rate)</p>
     *
     * @param steve   The Steve entity making the request
     * @param command The user command to plan
     * @return CompletableFuture that completes with the parsed response, or null on failure
     */
    public CompletableFuture<ResponseParser.ParsedResponse> planTasksAsync(SteveEntity steve, String command) {
        try {
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            WorldKnowledge worldKnowledge = new WorldKnowledge(steve);
            String userPrompt = PromptBuilder.buildUserPrompt(steve, command, worldKnowledge);

            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            SteveMod.LOGGER.info("[Async] Requesting AI plan for Steve '{}' using {}: {}",
                steve.getSteveName(), provider, command);

            // Build params map (model depends on provider so Claude doesn't receive an OpenAI model name)
            String modelForProvider = provider.equals("claude")
                ? SteveConfig.ANTHROPIC_MODEL.get()
                : SteveConfig.OPENAI_MODEL.get();
            Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", modelForProvider,
                "maxTokens", SteveConfig.MAX_TOKENS.get(),
                "temperature", SteveConfig.TEMPERATURE.get()
            );

            // Select async client based on provider
            AsyncLLMClient client = getAsyncClient(provider);

            // Execute async request
            return client.sendAsync(userPrompt, params)
                .thenApply(response -> {
                    String content = response.getContent();
                    if (content == null || content.isEmpty()) {
                        SteveMod.LOGGER.error("[Async] Empty response from LLM");
                        return null;
                    }

                    ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(content);
                    if (parsed == null) {
                        SteveMod.LOGGER.error("[Async] Failed to parse AI response");
                        return null;
                    }

                    SteveMod.LOGGER.info("[Async] Plan received: {} ({} tasks, {}ms, {} tokens, cache: {})",
                        parsed.getPlan(),
                        parsed.getTasks().size(),
                        response.getLatencyMs(),
                        response.getTokensUsed(),
                        response.isFromCache());

                    return parsed;
                })
                .exceptionally(throwable -> {
                    SteveMod.LOGGER.error("[Async] Error planning tasks: {}", throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            SteveMod.LOGGER.error("[Async] Error setting up task planning", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Asynchronously asks the LLM to WRITE a JavaScript program for the command and
     * returns the extracted code (fences stripped). Single-shot — retries are
     * orchestrated by the caller, which feeds {@code lastError} back in.
     *
     * @param lastError error text from a failed previous attempt, or null on the first try
     * @return future of clean JS source, or null on transport failure / empty response
     */
    public CompletableFuture<String> planCodeAsync(SteveEntity steve, String command, String lastError) {
        try {
            String systemPrompt = CodePromptBuilder.buildSystemPrompt();
            WorldKnowledge worldKnowledge = new WorldKnowledge(steve);
            String userPrompt = CodePromptBuilder.buildUserPrompt(steve, command, worldKnowledge, lastError);

            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            SteveMod.LOGGER.info("[Code] Requesting JS program for Steve '{}' using {}: {}",
                steve.getSteveName(), provider, command);

            String modelForProvider = provider.equals("claude")
                ? SteveConfig.ANTHROPIC_MODEL.get()
                : SteveConfig.OPENAI_MODEL.get();
            Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", modelForProvider,
                "maxTokens", SteveConfig.MAX_TOKENS.get(),
                "temperature", SteveConfig.TEMPERATURE.get()
            );

            return getAsyncClient(provider).sendAsync(userPrompt, params)
                .thenApply(response -> {
                    String content = response.getContent();
                    if (content == null || content.isEmpty()) {
                        SteveMod.LOGGER.error("[Code] Empty response from LLM");
                        return null;
                    }
                    String code = CodeExtractor.extract(content);
                    SteveMod.LOGGER.info("[Code] Program received ({} chars, {}ms, {} tokens, cache: {})",
                        code.length(), response.getLatencyMs(), response.getTokensUsed(), response.isFromCache());
                    // Log the actual generated program so in-game misbehaviour can be diagnosed
                    // (e.g. which API calls / coordinates the LLM produced).
                    SteveMod.LOGGER.info("[Code] Generated program for '{}':\n{}", command, code);
                    return code;
                })
                .exceptionally(t -> {
                    SteveMod.LOGGER.error("[Code] Error requesting program: {}", t.getMessage());
                    return null;
                });
        } catch (Exception e) {
            SteveMod.LOGGER.error("[Code] Error setting up code planning", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Returns the appropriate async client based on provider config.
     *
     * @param provider Provider name ("openai", "groq", "gemini")
     * @return Resilient async client
     */
    private AsyncLLMClient getAsyncClient(String provider) {
        return switch (provider) {
            case "claude" -> asyncClaudeClient;
            case "openai" -> asyncOpenAIClient;
            case "gemini" -> asyncGeminiClient;
            case "groq" -> asyncGroqClient;
            default -> {
                SteveMod.LOGGER.warn("[Async] Unknown provider '{}', using Groq", provider);
                yield asyncGroqClient;
            }
        };
    }

    /**
     * Returns the LLM cache for monitoring.
     *
     * @return LLM cache instance
     */
    public LLMCache getLLMCache() {
        return llmCache;
    }

    /**
     * Checks if the specified provider's async client is healthy.
     *
     * @param provider Provider name
     * @return true if healthy (circuit breaker not OPEN)
     */
    public boolean isProviderHealthy(String provider) {
        return getAsyncClient(provider).isHealthy();
    }

    /**
     * Returns the key, or a non-empty placeholder if it is null/empty.
     *
     * <p>Base clients reject empty keys in their constructor. Since all base clients are
     * created eagerly, an unconfigured provider would otherwise break planner construction.
     * The placeholder lets construction succeed; an unconfigured provider that is actually
     * selected fails at call time with HTTP 401 instead.</p>
     */
    private static String orPlaceholder(String key) {
        return (key == null || key.isEmpty()) ? "not-configured" : key;
    }

    public boolean validateTask(Task task) {
        String action = task.getAction();
        
        return switch (action) {
            case "pathfind" -> task.hasParameters("x", "y", "z");
            case "mine" -> task.hasParameters("block", "quantity");
            case "place" -> task.hasParameters("block", "x", "y", "z");
            case "craft" -> task.hasParameters("item", "quantity");
            case "attack" -> task.hasParameters("target");
            case "follow" -> task.hasParameters("player");
            case "gather" -> task.hasParameters("resource", "quantity");
            case "build" -> task.hasParameters("structure", "blocks", "dimensions");
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", action);
                yield false;
            }
        };
    }

    public List<Task> validateAndFilterTasks(List<Task> tasks) {
        return tasks.stream()
            .filter(this::validateTask)
            .toList();
    }
}

