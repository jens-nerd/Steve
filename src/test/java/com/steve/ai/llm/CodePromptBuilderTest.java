package com.steve.ai.llm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodePromptBuilderTest {

    @Test
    void systemPromptDocumentsApiAndDemandsJsOnly() {
        String sys = CodePromptBuilder.buildSystemPrompt();
        assertTrue(sys.contains("steve.place"));
        assertTrue(sys.contains("steve.getPosition"));
        assertTrue(sys.toLowerCase().contains("javascript"));
    }

    @Test
    void userPromptIncludesCommandAndContext() {
        String user = CodePromptBuilder.buildUserPrompt("make a sunflower field", "ground: grass_block", null);
        assertTrue(user.contains("make a sunflower field"));
        assertTrue(user.contains("grass_block"));
    }

    @Test
    void userPromptIncludesRetryErrorWhenPresent() {
        String user = CodePromptBuilder.buildUserPrompt("build a wall", "ground: stone",
            "ReferenceError: foo is not defined");
        assertTrue(user.contains("foo is not defined"));
        assertTrue(user.toLowerCase().contains("previous"));
    }
}
