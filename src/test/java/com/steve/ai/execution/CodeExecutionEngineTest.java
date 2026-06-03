package com.steve.ai.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeExecutionEngineTest {

    @Test
    void scriptCanCallExportedApiAndBufferActions() {
        SteveAPI api = new SteveAPI(null, 1000, 64);
        CodeExecutionEngine engine = new CodeExecutionEngine(api, 100_000);
        var result = engine.execute("for (var i = 0; i < 5; i++) { steve.mine('iron_ore', 1); }");
        assertTrue(result.isSuccess(), () -> "unexpected error: " + result.getError());
        assertEquals(5, api.getBufferedCount());
        engine.close();
    }

    @Test
    void infiniteLoopIsStoppedByStatementLimit() {
        SteveAPI api = new SteveAPI(null, 100000, 64);
        CodeExecutionEngine engine = new CodeExecutionEngine(api, 10_000);
        var result = engine.execute("while (true) {}");
        assertFalse(result.isSuccess());
        engine.close();
    }

    @Test
    void javaAndFileAccessAreDenied() {
        SteveAPI api = new SteveAPI(null, 1000, 64);
        CodeExecutionEngine engine = new CodeExecutionEngine(api, 100_000);
        var result = engine.execute("java.lang.System.exit(0);");
        assertFalse(result.isSuccess());
        engine.close();
    }
}
