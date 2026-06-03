# LLM Code-Execution Capability — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Steve respond to *any* command by having the LLM emit a small JavaScript program (run in a sandboxed GraalVM context) that plans primitive actions, replacing the brittle fixed-JSON-task path.

**Architecture:** For each command the configured LLM (Haiku) writes JS that calls a `@HostAccess.Export`-annotated `SteveAPI`. The script runs **on the server thread** in `ActionExecutor.tick()` and only *buffers* actions; on success the buffer is committed to the existing tick-drained `taskQueue` and Steve executes them block-by-block. Runtime/syntax failures trigger up to 2 LLM retries with the error fed back.

**Tech Stack:** Java 25, NeoForge 26.1.2, GraalVM polyglot/JS 24.2.1 (`HostAccess`, `ResourceLimits`), JUnit Jupiter 5.

**Reference spec:** `docs/superpowers/specs/2026-06-03-llm-code-execution-design.md`

---

## File Structure

| File | Responsibility |
|---|---|
| `llm/CodeExtractor.java` *(new)* | Pure util: strip Markdown ```js fences from raw LLM text → clean JS. |
| `llm/CodePromptBuilder.java` *(new)* | Build the code-gen system prompt (JS API reference + few-shot) and user prompt (command + world context + optional retry error). |
| `execution/SteveAPI.java` *(modify)* | `@HostAccess.Export` on public methods; buffer-with-cap instead of auto-enqueue; `drainTo`; placement radius guard. |
| `execution/CodeExecutionEngine.java` *(modify)* | Custom `HostAccess` (Export + map/list), `ResourceLimits` statement limit; constructor takes a `SteveAPI`. |
| `config/SteveConfig.java` *(modify)* | New bounds: max actions, statement limit, retries, placement radius. |
| `llm/TaskPlanner.java` *(modify)* | Add `planCodeAsync(steve, command, lastError)` → `CompletableFuture<String>`. |
| `action/ActionExecutor.java` *(modify)* | Switch async path from JSON parsing to code-gen + server-thread execution + retry. |
| `action/actions/PlaceBlockAction.java` *(modify, optional Task 9)* | Place 2-tall plants (sunflower) correctly. |

**Testing reality:** Tasks 1–5 are decoupled from Minecraft and get real TDD unit tests (`src/test/java/...`, run with `./gradlew test`). Tasks 6–9 are config declaration / Minecraft-world-coupled wiring that the project verifies via compile + the established **in-game gate** (`./gradlew runClient`), consistent with the existing test strategy — do **not** fabricate mock-heavy tests for entity/world code.

---

### Task 1: CodeExtractor (strip code fences)

**Files:**
- Create: `src/main/java/com/steve/ai/llm/CodeExtractor.java`
- Test: `src/test/java/com/steve/ai/llm/CodeExtractorTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.steve.ai.llm.CodeExtractorTest`
Expected: FAIL — `CodeExtractor` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.steve.ai.llm.CodeExtractorTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/steve/ai/llm/CodeExtractor.java src/test/java/com/steve/ai/llm/CodeExtractorTest.java
git commit -m "feat: CodeExtractor strips markdown fences from LLM JS responses"
```

---

### Task 2: SteveAPI — buffer with cap + @HostAccess.Export

Convert the auto-enqueue queue into a *buffer* (committed only on script success) with a hard action cap, and make the API visible to JS via `@HostAccess.Export`.

**Files:**
- Modify: `src/main/java/com/steve/ai/execution/SteveAPI.java`
- Test: `src/test/java/com/steve/ai/execution/SteveAPIBufferTest.java`

- [ ] **Step 1: Write the failing test**

`mine()` / `build()` do not touch the entity, so a `null` Steve is fine for buffer/cap tests.

```java
package com.steve.ai.execution;

import com.steve.ai.action.Task;
import org.junit.jupiter.api.Test;
import java.util.LinkedList;
import java.util.Queue;
import static org.junit.jupiter.api.Assertions.*;

class SteveAPIBufferTest {

    @Test
    void buffersActionsWithoutCommitting() {
        SteveAPI api = new SteveAPI(null, 100, 64);
        api.mine("iron_ore", 3);
        api.mine("coal_ore", 2);
        assertEquals(2, api.getBufferedCount());
    }

    @Test
    void drainToMovesBufferIntoTargetQueue() {
        SteveAPI api = new SteveAPI(null, 100, 64);
        api.mine("iron_ore", 3);
        Queue<Task> target = new LinkedList<>();
        api.drainTo(target);
        assertEquals(1, target.size());
        assertEquals("mine", target.peek().getAction());
        assertEquals(0, api.getBufferedCount());
    }

    @Test
    void throwsWhenActionCapExceeded() {
        SteveAPI api = new SteveAPI(null, 2, 64);
        api.mine("iron_ore", 1);
        api.mine("iron_ore", 1);
        assertThrows(IllegalStateException.class, () -> api.mine("iron_ore", 1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.steve.ai.execution.SteveAPIBufferTest`
Expected: FAIL — constructor `SteveAPI(SteveEntity,int,int)` and methods `getBufferedCount`/`drainTo` do not exist.

- [ ] **Step 3: Implement**

In `SteveAPI.java`:

(a) Replace the imports/field block (lines 12–28) — swap the `LinkedBlockingQueue` for a buffer, store the caps, and import `HostAccess`:

```java
import org.graalvm.polyglot.HostAccess;

import java.util.*;

/**
 * Safe API bridge between LLM-generated code and Minecraft.
 * Actions are buffered (not executed) and committed to the real action queue
 * only after the generating script completes without error.
 *
 * This class is exposed to JavaScript as the `steve` global object; every method
 * callable from JS MUST carry {@link HostAccess.Export}.
 */
public class SteveAPI {
    private final SteveEntity steve;
    private final List<Task> buffer;
    private final int maxActions;
    private final int placementRadius;

    public SteveAPI(SteveEntity steve, int maxActions, int placementRadius) {
        this.steve = steve;
        this.buffer = new ArrayList<>();
        this.maxActions = maxActions;
        this.placementRadius = placementRadius;
    }

    /** Buffer one task, enforcing the per-script action cap. */
    private void enqueue(Task task) {
        if (buffer.size() >= maxActions) {
            throw new IllegalStateException(
                "Action limit reached (" + maxActions + "); script enqueued too many actions");
        }
        buffer.add(task);
    }
```

(b) In every action method, replace `actionQueue.add(new Task(...));` with `enqueue(new Task(...));`. The call sites are: `move`, `build(String,Map)`, `mine`, `attack`, `craft`, `place`, `follow`, `gather`. (`build(String)` delegates to `build(String,Map)`, leave as-is.)

(c) Add `@HostAccess.Export` immediately above every JS-callable public method: `move`, both `build` overloads, `mine`, `attack`, `craft`, `place`, `say`, `follow`, `gather`, `getPosition`, `getNearbyBlocks`, `getNearbyEntities`, `isIdle`, `getPendingActionCount`, `wait`. Example:

```java
    @HostAccess.Export
    public void move(double x, double y, double z) {
```

(d) Replace the internal-methods block (lines 283–327, the `isIdle`/`getPendingActionCount`/`getActionQueue`/`clearActions` group) with buffer-based versions plus the new drain/count:

```java
    @HostAccess.Export
    public boolean isIdle() {
        return buffer.isEmpty();
    }

    @HostAccess.Export
    public int getPendingActionCount() {
        return buffer.size();
    }

    /** Number of actions currently buffered (test/diagnostic helper). */
    public int getBufferedCount() {
        return buffer.size();
    }

    /** Commit all buffered actions into the target queue and clear the buffer. */
    public void drainTo(Queue<Task> target) {
        target.addAll(buffer);
        buffer.clear();
    }

    /** Discard all buffered actions (used when a script fails). */
    public void clearBuffer() {
        buffer.clear();
    }

    SteveEntity getSteveEntity() {
        return steve;
    }
```

Remove the now-deleted `getActionQueue()` / `clearActions()` (no remaining callers after Task 8; verify with `grep -rn "getActionQueue\|clearActions" src/main`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.steve.ai.execution.SteveAPIBufferTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/steve/ai/execution/SteveAPI.java src/test/java/com/steve/ai/execution/SteveAPIBufferTest.java
git commit -m "feat: SteveAPI buffers actions with a cap and @HostAccess.Export"
```

---

### Task 3: SteveAPI — placement radius guard

Reject `place()` calls farther than the configured radius from Steve (runaway-script containment).

**Files:**
- Modify: `src/main/java/com/steve/ai/execution/SteveAPI.java`
- Test: `src/test/java/com/steve/ai/execution/SteveAPIRadiusTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.steve.ai.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SteveAPIRadiusTest {

    @Test
    void withinRadiusTrueForNearbyBlock() {
        assertTrue(SteveAPI.withinRadius(0, 64, 0, 10, 64, 10, 64));
    }

    @Test
    void withinRadiusFalseForFarBlock() {
        assertFalse(SteveAPI.withinRadius(0, 64, 0, 500, 64, 0, 64));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.steve.ai.execution.SteveAPIRadiusTest`
Expected: FAIL — `withinRadius` does not exist.

- [ ] **Step 3: Implement**

Add the static helper to `SteveAPI.java` (above `getSteveEntity()`):

```java
    /** True if (x,y,z) is within {@code radius} blocks (Euclidean) of Steve's position. */
    public static boolean withinRadius(double sx, double sy, double sz,
                                       int x, int y, int z, int radius) {
        double dx = x - sx, dy = y - sy, dz = z - sz;
        return (dx * dx + dy * dy + dz * dz) <= (double) radius * radius;
    }
```

In `place(...)`, after the existing position-null check and before building `params`, enforce the radius (skip the check when `steve` is null, e.g. in unit tests):

```java
        int px = position.get("x").intValue();
        int py = position.get("y").intValue();
        int pz = position.get("z").intValue();

        if (steve != null) {
            var p = steve.position();
            if (!withinRadius(p.x, p.y, p.z, px, py, pz, placementRadius)) {
                throw new IllegalArgumentException(
                    "Cannot place block beyond " + placementRadius + " blocks from Steve");
            }
        }

        Map<String, Object> params = new HashMap<>();
        params.put("block", blockType.toLowerCase());
        params.put("x", px);
        params.put("y", py);
        params.put("z", pz);
        enqueue(new Task("place", params));
```

(Replace the old `params.put("x", position.get("x").intValue());` lines accordingly.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.steve.ai.execution.SteveAPIRadiusTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/steve/ai/execution/SteveAPI.java src/test/java/com/steve/ai/execution/SteveAPIRadiusTest.java
git commit -m "feat: SteveAPI rejects placements beyond the configured radius"
```

---

### Task 4: CodeExecutionEngine — real sandbox + host access

Make the API visible to JS (custom `HostAccess`) and add a statement limit to kill infinite loops. The engine takes a pre-built `SteveAPI` so it is unit-testable without Minecraft.

**Files:**
- Modify: `src/main/java/com/steve/ai/execution/CodeExecutionEngine.java`
- Test: `src/test/java/com/steve/ai/execution/CodeExecutionEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.steve.ai.execution.CodeExecutionEngineTest`
Expected: FAIL — constructor `CodeExecutionEngine(SteveAPI,long)` does not exist; with the current `allowHostAccess(null)` the API call would also not buffer.

- [ ] **Step 3: Implement**

Rewrite the head of `CodeExecutionEngine.java` (imports through the constructor, lines 1–62). Note: the `console.log` polyfill must NOT reference `java.lang.System` anymore (Java access is denied) — route it through an exported API or drop it; we drop it to keep the sandbox closed.

```java
package com.steve.ai.execution;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;

/**
 * Executes LLM-generated JavaScript in a sandboxed GraalVM context.
 *
 * Safety: no file/network/native/thread/process access, no Java class lookup,
 * only @HostAccess.Export-annotated SteveAPI members are reachable, and a
 * statement limit aborts runaway/infinite scripts.
 */
public class CodeExecutionEngine {
    private final SteveAPI steveAPI;
    private final Context graalContext;

    public CodeExecutionEngine(SteveAPI api, long statementLimit) {
        this.steveAPI = api;

        HostAccess access = HostAccess.newBuilder()
            .allowAccessAnnotatedBy(HostAccess.Export.class) // only @Export members
            .allowListAccess(true)                            // JS can read List returns
            .allowMapAccess(true)                             // JS can read Map returns
            .allowArrayAccess(true)
            .build();

        ResourceLimits limits = ResourceLimits.newBuilder()
            .statementLimit(statementLimit, null)             // null filter = all sources
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
            .option("js.timer-resolution", "1")
            .build();

        graalContext.getBindings("js").putMember("steve", steveAPI);
    }

    /** Convenience for production: build the API from a live entity. */
    public static CodeExecutionEngine forEntity(com.steve.ai.entity.SteveEntity steve,
                                                int maxActions, int placementRadius,
                                                long statementLimit) {
        return new CodeExecutionEngine(new SteveAPI(steve, maxActions, placementRadius), statementLimit);
    }
```

Then delete the old `DEFAULT_TIMEOUT_MS` field and the `execute(String)`/`execute(String,long)` overload pair, replacing them with a single `execute(String)` (the statement limit, not a timeout, bounds runtime):

```java
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
```

Keep `validateSyntax`, `getAPI()`, `close()`, and the `ExecutionResult` nested class unchanged. Delete the now-removed `import java.time.Duration;` and `import java.util.concurrent.TimeoutException;`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.steve.ai.execution.CodeExecutionEngineTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/steve/ai/execution/CodeExecutionEngine.java src/test/java/com/steve/ai/execution/CodeExecutionEngineTest.java
git commit -m "feat: real GraalVM sandbox — export-only host access + statement limit"
```

---

### Task 5: CodePromptBuilder

**Files:**
- Create: `src/main/java/com/steve/ai/llm/CodePromptBuilder.java`
- Test: `src/test/java/com/steve/ai/llm/CodePromptBuilderTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.steve.ai.llm.CodePromptBuilderTest`
Expected: FAIL — `CodePromptBuilder` does not exist.

- [ ] **Step 3: Implement**

```java
package com.steve.ai.llm;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.WorldKnowledge;

/**
 * Builds prompts that ask the LLM to write a JavaScript program against the
 * sandboxed {@code steve} API. The program PLANS actions (they execute later,
 * block-by-block); it must not assume anything runs instantly.
 */
public final class CodePromptBuilder {

    private CodePromptBuilder() {}

    public static String buildSystemPrompt() {
        return """
            You are a Minecraft agent controller. Respond with ONLY a single JavaScript
            program in a ```js code block — no prose, no explanation.

            The program calls a global `steve` API. Calls are QUEUED and executed later,
            block-by-block, in order. Plan with loops; compute coordinates from steve.getPosition().

            Action API (queued):
            - steve.move(x, y, z)
            - steve.place(blockType, {x, y, z})      // one block; e.g. "sunflower", "oak_planks"
            - steve.mine(blockType, count)           // ores: iron_ore, diamond_ore, coal_ore, ...
            - steve.build(structureType)             // prebuilt: house, castle, tower, barn, modern, wall, platform, box
            - steve.craft(itemName, count)
            - steve.attack(entityType)               // e.g. "hostile", "zombie"
            - steve.follow(playerName)
            - steve.gather(resourceType, count)

            Perception API (returns immediately):
            - steve.getPosition()                    // -> {x, y, z}
            - steve.getNearbyBlocks(radius)          // -> [names], radius 1..16
            - steve.getNearbyEntities(radius)        // -> [names], radius 1..32

            Rules:
            1. Use integer block coordinates for place().
            2. Keep placements within ~32 blocks of steve.getPosition().
            3. For a "field"/"grid", loop over an area and place() each cell.
            4. Prefer steve.build('house') etc. for the listed prebuilt structures.

            Example — "make a 5x5 sunflower field":
            ```js
            var p = steve.getPosition();
            var x0 = Math.floor(p.x), y = Math.floor(p.y), z0 = Math.floor(p.z);
            for (var dx = 0; dx < 5; dx++) {
              for (var dz = 0; dz < 5; dz++) {
                steve.place('sunflower', {x: x0 + dx, y: y, z: z0 + dz});
              }
            }
            ```
            """;
    }

    public static String buildUserPrompt(String command, String worldContext, String lastError) {
        StringBuilder sb = new StringBuilder();
        sb.append("Command: ").append(command).append("\n\n");
        sb.append("World context:\n").append(worldContext).append("\n");
        if (lastError != null && !lastError.isBlank()) {
            sb.append("\nYour previous attempt failed with:\n")
              .append(lastError)
              .append("\nReturn corrected JavaScript only.\n");
        }
        return sb.toString();
    }

    /** Production overload: derive the world context from the entity. */
    public static String buildUserPrompt(SteveEntity steve, String command,
                                         WorldKnowledge worldKnowledge, String lastError) {
        var p = steve.position();
        String ctx = String.format(
            "steve position: x=%d y=%d z=%d%n",
            (int) p.x, (int) p.y, (int) p.z);
        return buildUserPrompt(command, ctx, lastError);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.steve.ai.llm.CodePromptBuilderTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/steve/ai/llm/CodePromptBuilder.java src/test/java/com/steve/ai/llm/CodePromptBuilderTest.java
git commit -m "feat: CodePromptBuilder for JS code-generation prompts"
```

---

### Task 6: SteveConfig — execution bounds (config declaration, no unit test)

**Files:**
- Modify: `src/main/java/com/steve/ai/config/SteveConfig.java`

- [ ] **Step 1: Declare the fields**

Add to the field list (after `MAX_ACTIVE_STEVES`, line ~16):

```java
    public static final ModConfigSpec.IntValue CODE_MAX_ACTIONS;
    public static final ModConfigSpec.IntValue CODE_STATEMENT_LIMIT;
    public static final ModConfigSpec.IntValue CODE_MAX_RETRIES;
    public static final ModConfigSpec.IntValue CODE_PLACEMENT_RADIUS;
```

- [ ] **Step 2: Initialize them**

Inside the static initializer, after the `behavior` group's existing values and before `builder.pop()` / `SPEC = builder.build();`, add a new section:

```java
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
```

(Match the existing push/pop nesting — confirm the `behavior` group is popped before pushing `codeExecution`, or nest as a sibling consistently with the current file.)

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/steve/ai/config/SteveConfig.java
git commit -m "feat: config bounds for code execution (actions, statements, retries, radius)"
```

---

### Task 7: TaskPlanner.planCodeAsync (compose tested units; verify in-game)

Adds a single-shot async call that returns extracted JS. Reuses the existing async Claude client; no new client wiring.

**Files:**
- Modify: `src/main/java/com/steve/ai/llm/TaskPlanner.java`

- [ ] **Step 1: Add the method**

Add imports if missing (`CodePromptBuilder`, `CodeExtractor` are same-package, no import needed). Add after `planTasksAsync`:

```java
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
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/steve/ai/llm/TaskPlanner.java
git commit -m "feat: TaskPlanner.planCodeAsync returns extracted LLM JavaScript"
```

> Functional verification of the live LLM round-trip happens in Task 8's in-game gate (it needs a configured API key and a running client).

---

### Task 8: ActionExecutor — run generated code on the server thread + retry

Replace the async JSON-planning path with code execution. The tick consume-block runs the engine on the server thread, commits on success, and re-prompts (with the error) up to `CODE_MAX_RETRIES` times.

**Files:**
- Modify: `src/main/java/com/steve/ai/action/ActionExecutor.java`

- [ ] **Step 1: Swap the planning fields**

Replace lines 44–47:

```java
    // Async code-generation planning (non-blocking LLM calls)
    private CompletableFuture<String> codeFuture;
    private boolean isPlanning = false;
    private String pendingCommand;
    private int codeAttempts;
    private String lastCodeError;
```

Update imports: remove `import com.steve.ai.llm.ResponseParser;`, add `import com.steve.ai.execution.CodeExecutionEngine;` (the package is already wildcard-imported via `com.steve.ai.execution.*` at line 9, so no change needed — confirm). In the constructor, replace `this.planningFuture = null;` with `this.codeFuture = null;` and add `this.codeAttempts = 0; this.lastCodeError = null;`.

- [ ] **Step 2: Rewrite `processNaturalLanguageCommand` planning start**

Replace lines 133–156 (the `try { ... }` that set `pendingCommand`/`isPlanning` and called `planTasksAsync`) with:

```java
        try {
            this.pendingCommand = command;
            this.isPlanning = true;
            this.codeAttempts = 0;
            this.lastCodeError = null;

            sendToGUI(steve.getSteveName(), "Thinking...");

            codeFuture = getTaskPlanner().planCodeAsync(steve, command, null);

            SteveMod.LOGGER.info("Steve '{}' started code planning for: {}", steve.getSteveName(), command);

        } catch (NoClassDefFoundError e) {
            SteveMod.LOGGER.error("Failed to initialize AI components", e);
            sendToGUI(steve.getSteveName(), "Sorry, I'm having trouble with my AI systems!");
            isPlanning = false;
            codeFuture = null;
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error starting code planning", e);
            sendToGUI(steve.getSteveName(), "Oops, something went wrong!");
            isPlanning = false;
            codeFuture = null;
        }
```

- [ ] **Step 3: Rewrite the tick consume-block**

Replace the whole `if (isPlanning && planningFuture != null && planningFuture.isDone()) { ... }` block (lines 222–255) with:

```java
        if (isPlanning && codeFuture != null && codeFuture.isDone()) {
            String code = null;
            try {
                code = codeFuture.get();
            } catch (Exception e) {
                SteveMod.LOGGER.error("Steve '{}' failed to get generated code", steve.getSteveName(), e);
            }
            codeFuture = null;

            if (code == null || code.isBlank()) {
                handleCodeFailure("no program produced");
            } else {
                runGeneratedCode(code);
            }
        }
```

- [ ] **Step 4: Add the two helper methods**

Add below `tick()` (or near the other private methods):

```java
    /** Run a generated program on the server thread; commit on success, retry/give-up on failure. */
    private void runGeneratedCode(String code) {
        int maxActions = SteveConfig.CODE_MAX_ACTIONS.get();
        int radius = SteveConfig.CODE_PLACEMENT_RADIUS.get();
        long stmtLimit = SteveConfig.CODE_STATEMENT_LIMIT.get();

        CodeExecutionEngine engine =
            CodeExecutionEngine.forEntity(steve, maxActions, radius, stmtLimit);
        try {
            CodeExecutionEngine.ExecutionResult result = engine.execute(code);
            if (result.isSuccess()) {
                taskQueue.clear();
                engine.getAPI().drainTo(taskQueue);
                currentGoal = pendingCommand;
                steve.getMemory().setCurrentGoal(currentGoal);
                if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                    sendToGUI(steve.getSteveName(), "Okay!");
                }
                SteveMod.LOGGER.info("Steve '{}' code planning complete: {} tasks queued",
                    steve.getSteveName(), taskQueue.size());
                isPlanning = false;
                pendingCommand = null;
            } else {
                handleCodeFailure(result.getError());
            }
        } finally {
            engine.close();
        }
    }

    /** Either re-prompt the LLM with the error (retry) or surface a give-up message. */
    private void handleCodeFailure(String error) {
        SteveMod.LOGGER.warn("Steve '{}' generated program failed: {}", steve.getSteveName(), error);
        if (codeAttempts < SteveConfig.CODE_MAX_RETRIES.get()) {
            codeAttempts++;
            lastCodeError = error;
            SteveMod.LOGGER.info("Steve '{}' retrying code generation (attempt {})",
                steve.getSteveName(), codeAttempts);
            codeFuture = getTaskPlanner().planCodeAsync(steve, pendingCommand, lastCodeError);
            // isPlanning stays true — next tick processes the retry future
        } else {
            sendToGUI(steve.getSteveName(), "Das kriege ich nicht hin.");
            isPlanning = false;
            pendingCommand = null;
        }
    }
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (The deprecated `processNaturalLanguageCommandSync` and `PromptBuilder`/`ResponseParser` remain in the tree but are no longer on the live path — leave them; do not delete pre-existing code.)

- [ ] **Step 6: Run the full unit suite**

Run: `./gradlew test`
Expected: PASS — all prior tests (Tasks 1–5) plus the existing async tests stay green.

- [ ] **Step 7: In-game gate (functional verification)**

Run: `./gradlew runClient`. With an API key configured in `run/config/steve-common.toml`:
1. Spawn/enter the world; press **K**, type `make a sunflower field`.
2. Expected log: `[Code] Requesting JS program ...` → `code planning complete: N tasks queued` → Steve walks and places sunflowers block-by-block.
3. Try a prebuilt: `build a house` → still works via `steve.build('house')`.
4. Try something the model may botch once to confirm a retry logs `retrying code generation (attempt 1)` and then succeeds or cleanly says "Das kriege ich nicht hin."

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/steve/ai/action/ActionExecutor.java
git commit -m "feat: execute LLM-generated programs on the server thread with retry"
```

---

### Task 9 (optional, deferred): 2-tall plant placement for sunflowers

Only needed if sunflowers render as a single broken half. Non-blocking per the spec.

**Files:**
- Modify: `src/main/java/com/steve/ai/action/actions/PlaceBlockAction.java`

- [ ] **Step 1: In-game check**

Place a sunflower field (Task 8). If sunflowers appear whole, **skip this task**. If only the bottom half / nothing appears, proceed.

- [ ] **Step 2: Implement upper-half placement**

In `PlaceBlockAction` after the successful lower-block `setBlock` (around line 62), if the placed block is a `DoublePlantBlock`, set its upper half:

```java
        steve.level().setBlock(targetPos, blockToPlace.defaultBlockState(), 3);

        if (blockToPlace instanceof net.minecraft.world.level.block.DoublePlantBlock) {
            net.minecraft.world.level.block.DoublePlantBlock.placeAt(
                steve.level(), blockToPlace.defaultBlockState(), targetPos, 3);
        }
```

(Confirm the exact `DoublePlantBlock.placeAt` signature against the 26.1 jar via `javap -p net.minecraft.world.level.block.DoublePlantBlock` in `build/moddev/artifacts/...` before relying on it; adjust if the helper differs.)

- [ ] **Step 3: In-game verify**

`./gradlew runClient` → place sunflowers → they appear as full 2-block plants.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/steve/ai/action/actions/PlaceBlockAction.java
git commit -m "fix: place 2-tall plants (sunflowers) with both halves"
```

---

## Self-Review

**Spec coverage:**
- Code-gen replaces JSON path → Tasks 7, 8. ✓
- Immersive block-by-block (buffer → existing tick drain) → Tasks 2, 8. ✓
- GraalVM host-access fix (`@HostAccess.Export` + custom HostAccess) → Tasks 2, 4. ✓
- Threading B1 (run on server thread in tick) → Task 8. ✓
- Safety: statement limit, action cap, placement radius → Tasks 2, 3, 4, 6. ✓
- Atomic enqueue (buffer commits only on success) → Tasks 2, 8. ✓
- Retry up to 2 with error fed back → Tasks 5 (lastError in prompt), 7, 8. ✓
- Haiku stays default → Task 6 leaves model config untouched (only adds bounds). ✓
- Tests for API buffer/cap, engine sandbox/limit, code extraction, retry-prompt → Tasks 1–5. ✓ (ActionExecutor retry wiring verified in-game — Task 8 — honestly noted.)
- Old PromptBuilder/ResponseParser left unused, not deleted → Task 8 note. ✓
- Sunflower 2-tall open detail → Task 9. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code or an exact edit. ✓

**Type consistency:** `SteveAPI(SteveEntity,int,int)`, `getBufferedCount()`, `drainTo(Queue)`, `clearBuffer()`, `withinRadius(double,double,double,int,int,int,int)`, `CodeExecutionEngine(SteveAPI,long)` + `forEntity(...)`, `execute(String)`, `CodePromptBuilder.buildSystemPrompt()` / `buildUserPrompt(String,String,String)` / `buildUserPrompt(SteveEntity,String,WorldKnowledge,String)`, `TaskPlanner.planCodeAsync(SteveEntity,String,String) -> CompletableFuture<String>`, `ActionExecutor.codeFuture/codeAttempts/lastCodeError` — names used consistently across tasks. ✓
