package com.steve.ai.execution;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;

/**
 * Executes LLM-generated JavaScript in a sandboxed GraalVM context.
 * Safety: no file/network/native/thread/process access, no Java class lookup,
 * only @HostAccess.Export-annotated SteveAPI members reachable, and a statement
 * limit aborts runaway/infinite scripts.
 */
public class CodeExecutionEngine {
    private final SteveAPI steveAPI;
    private final Context graalContext;

    public CodeExecutionEngine(SteveAPI api, long statementLimit) {
        this.steveAPI = api;

        HostAccess access = HostAccess.newBuilder()
            .allowAccessAnnotatedBy(HostAccess.Export.class)
            .allowListAccess(true)
            .allowMapAccess(true)
            .allowArrayAccess(true)
            .build();

        ResourceLimits limits = ResourceLimits.newBuilder()
            .statementLimit(statementLimit, null)
            .build();

        this.graalContext = Context.newBuilder("js")
            .allowAllAccess(false)
            .allowIO(false)
            .allowNativeAccess(false)
            .allowCreateThread(false)
            .allowCreateProcess(false)
            .allowHostClassLookup(className -> false)
            .allowHostAccess(access)
            .resourceLimits(limits)
            .build();

        graalContext.getBindings("js").putMember("steve", steveAPI);
    }

    /** Convenience for production: build the API from a live entity. */
    public static CodeExecutionEngine forEntity(com.steve.ai.entity.SteveEntity steve,
                                                int maxActions, int placementRadius,
                                                long statementLimit) {
        return new CodeExecutionEngine(new SteveAPI(steve, maxActions, placementRadius), statementLimit);
    }

    /**
     * Execute JavaScript code.
     *
     * @param code JavaScript code to execute
     * @return ExecutionResult containing success/failure status and output
     */
    public ExecutionResult execute(String code) {
        if (code == null || code.trim().isEmpty()) {
            return ExecutionResult.error("No code provided");
        }
        try {
            Value result = graalContext.eval("js", code);
            return ExecutionResult.success(result.isNull() ? "null" : result.toString());
        } catch (PolyglotException e) {
            if (e.isResourceExhausted()) return ExecutionResult.error("Resource limit exceeded (loop too long?)");
            if (e.isSyntaxError())       return ExecutionResult.error("Syntax error: " + e.getMessage());
            String msg = e.getMessage();
            return ExecutionResult.error("Error: " + (msg == null || msg.isEmpty() ? "unknown" : msg));
        } catch (Exception e) {
            return ExecutionResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Validate JavaScript code syntax without executing.
     *
     * @param code JavaScript code to validate
     * @return true if syntax is valid, false otherwise
     */
    public boolean validateSyntax(String code) {
        try {
            // Parse without executing by wrapping in function
            graalContext.eval("js", "function __validate() { " + code + " }");
            return true;
        } catch (PolyglotException e) {
            return false;
        }
    }

    /**
     * Get the Steve API bridge.
     */
    public SteveAPI getAPI() {
        return steveAPI;
    }

    /**
     * Clean up resources.
     */
    public void close() {
        if (graalContext != null) {
            try {
                graalContext.close(true);
            } catch (PolyglotException e) {
                // Suppress: closing a context that hit a resource limit can re-throw the limit exception.
            }
        }
    }

    /**
     * Result of code execution.
     */
    public static class ExecutionResult {
        private final boolean success;
        private final String output;
        private final String error;

        private ExecutionResult(boolean success, String output, String error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }

        public static ExecutionResult success(String output) {
            return new ExecutionResult(true, output, null);
        }

        public static ExecutionResult error(String error) {
            return new ExecutionResult(false, null, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public String getError() {
            return error;
        }

        @Override
        public String toString() {
            if (success) {
                return "Success: " + output;
            } else {
                return "Error: " + error;
            }
        }
    }
}
