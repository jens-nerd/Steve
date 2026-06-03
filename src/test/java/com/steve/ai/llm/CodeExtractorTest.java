package com.steve.ai.llm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeExtractorTest {

    @Test
    void stripsJsFencedBlock() {
        String raw = "Here you go:\n```js\nsteve.mine('iron', 3);\n```\nDone!";
        assertEquals("steve.mine('iron', 3);", CodeExtractor.extract(raw));
    }

    @Test
    void stripsBareFencedBlock() {
        String raw = "```\nsteve.build('house');\n```";
        assertEquals("steve.build('house');", CodeExtractor.extract(raw));
    }

    @Test
    void returnsTrimmedRawWhenNoFence() {
        assertEquals("steve.follow('Steve');", CodeExtractor.extract("  steve.follow('Steve');  "));
    }

    @Test
    void returnsEmptyForNull() {
        assertEquals("", CodeExtractor.extract(null));
    }
}
