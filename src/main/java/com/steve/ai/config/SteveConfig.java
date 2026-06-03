package com.steve.ai.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class SteveConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<String> AI_PROVIDER;
    public static final ModConfigSpec.ConfigValue<String> OPENAI_API_KEY;
    public static final ModConfigSpec.ConfigValue<String> OPENAI_MODEL;
    public static final ModConfigSpec.ConfigValue<String> ANTHROPIC_API_KEY;
    public static final ModConfigSpec.ConfigValue<String> ANTHROPIC_MODEL;
    public static final ModConfigSpec.IntValue MAX_TOKENS;
    public static final ModConfigSpec.DoubleValue TEMPERATURE;
    public static final ModConfigSpec.IntValue ACTION_TICK_DELAY;
    public static final ModConfigSpec.BooleanValue ENABLE_CHAT_RESPONSES;
    public static final ModConfigSpec.IntValue MAX_ACTIVE_STEVES;
    public static final ModConfigSpec.IntValue CODE_MAX_ACTIONS;
    public static final ModConfigSpec.IntValue CODE_STATEMENT_LIMIT;
    public static final ModConfigSpec.IntValue CODE_MAX_RETRIES;
    public static final ModConfigSpec.IntValue CODE_PLACEMENT_RADIUS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("AI API Configuration").push("ai");
        
        AI_PROVIDER = builder
            .comment("AI provider to use: 'claude' (default), 'groq' (FASTEST, FREE), 'openai', or 'gemini'")
            .define("provider", "claude");

        builder.pop();

        builder.comment("Anthropic (Claude) API Configuration").push("anthropic");

        ANTHROPIC_API_KEY = builder
            .comment("Your Anthropic API key (required when provider = 'claude')")
            .define("apiKey", "");

        ANTHROPIC_MODEL = builder
            .comment("Claude model to use (claude-haiku-4-5, claude-sonnet-4-6, claude-opus-4-8)")
            .define("model", "claude-haiku-4-5");

        builder.pop();

        builder.comment("OpenAI/Gemini API Configuration (same key field used for both)").push("openai");
        
        OPENAI_API_KEY = builder
            .comment("Your OpenAI API key (required)")
            .define("apiKey", "");
        
        OPENAI_MODEL = builder
            .comment("OpenAI model to use (gpt-4, gpt-4-turbo-preview, gpt-3.5-turbo)")
            .define("model", "gpt-4-turbo-preview");
        
        MAX_TOKENS = builder
            .comment("Maximum tokens per API request")
            .defineInRange("maxTokens", 8000, 100, 65536);
        
        TEMPERATURE = builder
            .comment("Temperature for AI responses (0.0-2.0, lower is more deterministic)")
            .defineInRange("temperature", 0.7, 0.0, 2.0);
        
        builder.pop();

        builder.comment("Steve Behavior Configuration").push("behavior");
        
        ACTION_TICK_DELAY = builder
            .comment("Ticks between action checks (20 ticks = 1 second)")
            .defineInRange("actionTickDelay", 20, 1, 100);
        
        ENABLE_CHAT_RESPONSES = builder
            .comment("Allow Steves to respond in chat")
            .define("enableChatResponses", true);
        
        MAX_ACTIVE_STEVES = builder
            .comment("Maximum number of Steves that can be active simultaneously")
            .defineInRange("maxActiveSteves", 10, 1, 50);
        
        builder.pop();

        builder.comment("LLM Code-Execution Configuration").push("codeExecution");

        CODE_MAX_ACTIONS = builder
            .comment("Maximum actions a single generated program may queue")
            .defineInRange("maxActions", 1024, 1, 100000);

        CODE_STATEMENT_LIMIT = builder
            .comment("GraalVM statement limit per program (guards against infinite loops)")
            .defineInRange("statementLimit", 5000000, 1000, 1000000000);

        CODE_MAX_RETRIES = builder
            .comment("How many times to ask the LLM to fix a failing program before giving up")
            .defineInRange("maxRetries", 2, 0, 5);

        CODE_PLACEMENT_RADIUS = builder
            .comment("Max distance (blocks) from Steve that a program may place blocks")
            .defineInRange("placementRadius", 64, 4, 256);

        builder.pop();

        SPEC = builder.build();
    }
}

