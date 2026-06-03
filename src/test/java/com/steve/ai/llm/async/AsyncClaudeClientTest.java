package com.steve.ai.llm.async;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for AsyncClaudeClient (Anthropic Messages API).
 */
public class AsyncClaudeClientTest {

    private AsyncClaudeClient newClient() {
        return new AsyncClaudeClient("test-key", "claude-haiku-4-5", 1000, 0.7);
    }

    @Test
    void providerIdIsClaude() {
        assertEquals("claude", newClient().getProviderId());
    }

    @Test
    void buildRequestBodyPutsSystemAtTopLevelNotInMessages() {
        Map<String, Object> params = Map.of(
            "systemPrompt", "You are Steve",
            "model", "claude-haiku-4-5",
            "maxTokens", 1000,
            "temperature", 0.7
        );

        JsonObject body = JsonParser.parseString(
            newClient().buildRequestBody("mine some iron", params)).getAsJsonObject();

        // Anthropic-specific: system is a top-level field, not a message
        assertTrue(body.has("system"), "system must be a top-level field");
        assertEquals("You are Steve", body.get("system").getAsString());

        assertEquals("claude-haiku-4-5", body.get("model").getAsString());
        assertEquals(1000, body.get("max_tokens").getAsInt());
        assertEquals(0.7, body.get("temperature").getAsDouble(), 0.0001);

        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(1, messages.size(), "only the user message belongs in messages");
        JsonObject userMessage = messages.get(0).getAsJsonObject();
        assertEquals("user", userMessage.get("role").getAsString());
        assertEquals("mine some iron", userMessage.get("content").getAsString());
    }

    @Test
    void parseResponseExtractsTextAndSumsTokens() {
        String anthropicResponse = "{"
            + "\"id\":\"msg_123\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"plan\\\":\\\"mine iron\\\"}\"}],"
            + "\"model\":\"claude-haiku-4-5\","
            + "\"usage\":{\"input_tokens\":50,\"output_tokens\":20}"
            + "}";

        LLMResponse response = newClient().parseResponse(anthropicResponse, 1234L);

        assertEquals("{\"plan\":\"mine iron\"}", response.getContent());
        assertEquals(70, response.getTokensUsed(), "tokens = input_tokens + output_tokens");
        assertEquals("claude", response.getProviderId());
        assertEquals(1234L, response.getLatencyMs());
        assertFalse(response.isFromCache());
    }
}
