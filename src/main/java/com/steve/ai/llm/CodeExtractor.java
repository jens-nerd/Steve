package com.steve.ai.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts executable JavaScript from a raw LLM response, removing any
 * Markdown code fences (```js ... ``` or ``` ... ```).
 */
public final class CodeExtractor {

    private static final Pattern FENCE =
        Pattern.compile("```(?:js|javascript)?\\s*\\r?\\n(.*?)```", Pattern.DOTALL);

    private CodeExtractor() {}

    public static String extract(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher m = FENCE.matcher(raw);
        if (m.find()) {
            return m.group(1).trim();
        }
        return raw.trim();
    }
}
