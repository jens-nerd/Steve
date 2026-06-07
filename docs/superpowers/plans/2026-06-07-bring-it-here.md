# "bring it here" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a command contains "bring it here", Steve collects the items he mines into an inventory, places a chest in front of the requesting player, and shuttles half-stacks (32) to that chest until the mining job is done.

**Architecture:** The LLM still plans the work (a `mine` task). The phrase "bring it here" is detected deterministically in `ActionExecutor`. Steve gains a `SimpleContainer`; mining vacuums its dropped items into it. A delivery block in `tick()` (server thread) places the chest once, and — when ≥32 items are collected — pauses the mining action, runs a `DeliverToChestAction`, then resumes mining; a final deposit runs when mining completes.

**Tech Stack:** Java 25, NeoForge 26.1.2, JUnit Jupiter 5. Minecraft types are on the unit-test classpath (`testImplementation files(configurations.compileClasspath)`).

**Reference spec:** `docs/superpowers/specs/2026-06-07-bring-it-here-design.md`

---

## File Structure

| File | Responsibility |
|---|---|
| `action/DeliveryHelper.java` *(new)* | Pure helpers: detect the "bring it here" phrase; compute the chest position in front of a player. Unit-tested (no Minecraft bootstrap needed). |
| `entity/SteveEntity.java` *(modify)* | Add a `SimpleContainer` inventory + `getInventory()`, `addToInventory(ItemStack)`, `collectedCount()`. |
| `action/actions/MineBlockAction.java` *(modify)* | After breaking the target ore, vacuum the dropped `ItemEntity`s into Steve's inventory. |
| `command/SteveCommands.java` *(modify)* | `tellSteve` passes the requesting player to `processNaturalLanguageCommand`. |
| `action/actions/DeliverToChestAction.java` *(new)* | Walk Steve to the chest and move his inventory into it. |
| `action/ActionExecutor.java` *(modify)* | `deliveryMode`/`deliveryTarget`/`chestPos`/`deliveryAction`; place chest; pause-resume shuttle at the 32 threshold; final deposit. |

**Testing reality:** Task 1 is pure logic → real TDD unit tests. Tasks 2–6 are Minecraft-world-coupled (item entities, containers, pathfinding, block placement) and follow the project's established **in-game gate** (`./gradlew runClient`) plus `./gradlew compileJava`/`test` — do not fabricate mock-heavy tests for world code.

---

### Task 1: DeliveryHelper — phrase detection + chest position (pure, TDD)

**Files:**
- Create: `src/main/java/com/steve/ai/action/DeliveryHelper.java`
- Test: `src/test/java/com/steve/ai/action/DeliveryHelperTest.java`

- [ ] **Step 1: Write the failing test**

`BlockPos` and `Direction` are plain types (no registry), so no Minecraft bootstrap is needed.

```java
package com.steve.ai.action;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryHelperTest {

    @Test
    void detectsBringItHerePhraseCaseInsensitively() {
        assertTrue(DeliveryHelper.isBringItHere("mine some iron and BRING IT HERE"));
        assertTrue(DeliveryHelper.isBringItHere("bring it here"));
    }

    @Test
    void rejectsWhenPhraseAbsentOrNull() {
        assertFalse(DeliveryHelper.isBringItHere("mine some iron"));
        assertFalse(DeliveryHelper.isBringItHere(null));
    }

    @Test
    void chestPositionIsOneBlockInFacingDirection() {
        BlockPos player = new BlockPos(10, 64, 20);
        assertEquals(new BlockPos(10, 64, 19), DeliveryHelper.chestPositionInFront(player, Direction.NORTH));
        assertEquals(new BlockPos(11, 64, 20), DeliveryHelper.chestPositionInFront(player, Direction.EAST));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.steve.ai.action.DeliveryHelperTest`
Expected: FAIL — `DeliveryHelper` does not exist.

- [ ] **Step 3: Implement**

```java
package com.steve.ai.action;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Pure helpers for the "bring it here" delivery behaviour. */
public final class DeliveryHelper {

    private DeliveryHelper() {}

    /** True if the command asks Steve to bring collected items to the player. */
    public static boolean isBringItHere(String command) {
        return command != null && command.toLowerCase().contains("bring it here");
    }

    /** The block one step in front of the player (where the delivery chest goes). */
    public static BlockPos chestPositionInFront(BlockPos playerPos, Direction facing) {
        return playerPos.relative(facing);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.steve.ai.action.DeliveryHelperTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/steve/ai/action/DeliveryHelper.java src/test/java/com/steve/ai/action/DeliveryHelperTest.java
git commit -m "feat: DeliveryHelper — bring-it-here phrase + chest position (pure)"
```

---

### Task 2: Steve inventory

Give Steve a chest-sized inventory and accessors. Minecraft-coupled (entity) → compile-verified; the counting is exercised in-game via Task 6.

**Files:**
- Modify: `src/main/java/com/steve/ai/entity/SteveEntity.java`

- [ ] **Step 1: Add the inventory field + imports**

Add imports near the top:

```java
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
```

Add the field next to the other fields (after `private boolean isInvulnerable = false;`):

```java
    private final SimpleContainer inventory = new SimpleContainer(27);
```

- [ ] **Step 2: Add accessors**

Add these methods (e.g. after `getActionExecutor()`):

```java
    /** Steve's collected-items inventory (chest-sized). */
    public SimpleContainer getInventory() {
        return this.inventory;
    }

    /**
     * Add a stack to Steve's inventory.
     * @return the leftover that did not fit (empty if all fit).
     */
    public ItemStack addToInventory(ItemStack stack) {
        return this.inventory.addItem(stack);
    }

    /** Total number of collected items across all inventory slots. */
    public int collectedCount() {
        int total = 0;
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            total += this.inventory.getItem(i).getCount();
        }
        return total;
    }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/steve/ai/entity/SteveEntity.java
git commit -m "feat: SteveEntity gains a 27-slot collected-items inventory"
```

---

### Task 3: MineBlockAction collects mined drops

After breaking the target ore (which drops items to the world honouring Steve's pickaxe), vacuum those `ItemEntity`s into Steve's inventory.

**Files:**
- Modify: `src/main/java/com/steve/ai/action/actions/MineBlockAction.java`

- [ ] **Step 1: Add imports**

Add near the other imports:

```java
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import java.util.List;
```

(`List`/`ArrayList` may already be imported — keep one `import java.util.List;`.)

- [ ] **Step 2: Vacuum drops after mining the ore**

In `onTick()`, the target ore is destroyed at (currently) the line:

```java
            steve.level().destroyBlock(currentTarget, true);
```

Immediately after it, add a call to a new helper:

```java
            steve.level().destroyBlock(currentTarget, true);
            collectDropsNear(currentTarget);
```

- [ ] **Step 3: Implement the helper**

Add this private method (e.g. next to `findNextBlock`):

```java
    /** Pull any items dropped near {@code pos} into Steve's inventory. */
    private void collectDropsNear(BlockPos pos) {
        AABB box = new AABB(pos).inflate(2.0);
        List<ItemEntity> drops = steve.level().getEntitiesOfClass(ItemEntity.class, box);
        for (ItemEntity drop : drops) {
            ItemStack leftover = steve.addToInventory(drop.getItem());
            if (leftover.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(leftover); // inventory full — leave the rest on the ground
            }
        }
    }
```

(`ItemStack` is already imported in this file via `net.minecraft.world.item.ItemStack` usage; if not, add `import net.minecraft.world.item.ItemStack;`.)

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: In-game sanity check (optional now, full test in Task 6)**

Run: `./gradlew runClient`, `mine some iron`. Expected: Steve mines iron and `steve.collectedCount()` grows (visible later once the delivery block logs it; for now confirm no crash).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/steve/ai/action/actions/MineBlockAction.java
git commit -m "feat: MineBlockAction vacuums mined drops into Steve's inventory"
```

---

### Task 4: Pass the requesting player through the command path

**Files:**
- Modify: `src/main/java/com/steve/ai/action/ActionExecutor.java`
- Modify: `src/main/java/com/steve/ai/command/SteveCommands.java`

- [ ] **Step 1: Add a player-aware command overload in ActionExecutor**

Add imports to `ActionExecutor.java`:

```java
import com.steve.ai.action.DeliveryHelper;
import net.minecraft.world.entity.player.Player;
```

Add these delivery fields next to the existing planning fields (near `private String lastCodeError;`):

```java
    // "bring it here" delivery state
    private boolean deliveryMode = false;
    private Player deliveryTarget;
    private net.minecraft.core.BlockPos chestPos;
    private BaseAction deliveryAction;
    private static final int HALF_STACK = 32;
```

Change the existing `public void processNaturalLanguageCommand(String command)` so it delegates, and put the real body in a new 2-arg overload. Concretely, rename the current method body into:

```java
    public void processNaturalLanguageCommand(String command) {
        processNaturalLanguageCommand(command, null);
    }

    public void processNaturalLanguageCommand(String command, Player requester) {
```

Then, inside that 2-arg method, right after the `isPlanning` guard returns and after the current-action/idle cancellation (i.e. just before `this.pendingCommand = command;`), set the delivery state:

```java
            this.deliveryMode = DeliveryHelper.isBringItHere(command) && requester != null;
            this.deliveryTarget = requester;
            this.chestPos = null;
            if (deliveryAction != null) {
                deliveryAction.cancel();
                deliveryAction = null;
            }
```

- [ ] **Step 2: Reset delivery state in stopCurrentAction**

In `stopCurrentAction()`, add (alongside the existing resets):

```java
        deliveryMode = false;
        deliveryTarget = null;
        chestPos = null;
        if (deliveryAction != null) {
            deliveryAction.cancel();
            deliveryAction = null;
        }
```

- [ ] **Step 3: Pass the player from tellSteve**

In `SteveCommands.tellSteve`, capture the source player before the worker thread and pass it through. Add import `import net.minecraft.world.entity.player.Player;` and change the call:

```java
        if (steve != null) {
            Player requester = (source.getEntity() instanceof Player p) ? p : null;
            new Thread(() -> {
                steve.getActionExecutor().processNaturalLanguageCommand(command, requester);
            }).start();
            return 1;
        } else {
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/steve/ai/action/ActionExecutor.java src/main/java/com/steve/ai/command/SteveCommands.java
git commit -m "feat: thread the requesting player into the command path + delivery flags"
```

---

### Task 5: DeliverToChestAction

A no-task action that walks Steve to the chest and moves his inventory into it.

**Files:**
- Create: `src/main/java/com/steve/ai/action/actions/DeliverToChestAction.java`

- [ ] **Step 1: Implement the action**

```java
package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Walks Steve to a chest and deposits his collected inventory into it.
 * Constructed without a Task (like IdleFollowAction).
 */
public class DeliverToChestAction extends BaseAction {
    private final BlockPos chestPos;
    private int ticks = 0;
    private static final int MAX_TICKS = 200; // 10s to reach the chest

    public DeliverToChestAction(SteveEntity steve, BlockPos chestPos) {
        super(steve, null);
        this.chestPos = chestPos;
    }

    @Override
    protected void onStart() {
        steve.getNavigation().moveTo(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5, 1.0);
    }

    @Override
    protected void onTick() {
        ticks++;
        boolean near = steve.blockPosition().closerThan(chestPos, 3.0);

        if (!near && ticks < MAX_TICKS) {
            if (!steve.getNavigation().isInProgress()) {
                steve.getNavigation().moveTo(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5, 1.0);
            }
            return;
        }

        // Either arrived, or timed out — teleport adjacent so the deposit always lands.
        if (!near) {
            steve.teleportTo(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5);
        }

        deposit();
        result = ActionResult.success("Delivered items to chest");
    }

    private void deposit() {
        BlockEntity be = steve.level().getBlockEntity(chestPos);
        if (!(be instanceof Container chest)) {
            SteveMod.LOGGER.warn("Steve '{}' delivery chest missing at {}", steve.getSteveName(), chestPos);
            return;
        }
        var inv = steve.getInventory();
        int moved = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            int before = stack.getCount();
            ItemStack leftover = HopperBlockEntity.addItem(inv, chest, stack, null);
            inv.setItem(i, leftover);
            moved += before - leftover.getCount();
        }
        SteveMod.LOGGER.info("Steve '{}' deposited {} items into chest at {}",
            steve.getSteveName(), moved, chestPos);
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Deliver items to chest at " + chestPos;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (If `HopperBlockEntity.addItem(Container, Container, ItemStack, Direction)` has a different name/signature in this 26.1 build, verify with `javap -p net.minecraft.world.level.block.entity.HopperBlockEntity` against `build/moddev/artifacts/minecraft-patched-26.1.2.71-merged.jar` and adjust — the verified signature is `addItem(Container source, Container destination, ItemStack, Direction) -> ItemStack`.)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/steve/ai/action/actions/DeliverToChestAction.java
git commit -m "feat: DeliverToChestAction walks to a chest and deposits Steve's inventory"
```

---

### Task 6: Delivery orchestration in ActionExecutor (chest + shuttle + final deposit)

Wire the delivery loop into `tick()`: place the chest once, pause mining to shuttle at the 32 threshold, resume, and deposit the remainder when mining finishes.

**Files:**
- Modify: `src/main/java/com/steve/ai/action/ActionExecutor.java`

- [ ] **Step 1: Add imports**

```java
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
```

- [ ] **Step 2: Insert the delivery block in tick()**

In `tick()`, immediately AFTER the async-planning `if (isPlanning && codeFuture != null ...) { ... }` block and BEFORE `if (currentAction != null) {`, insert:

```java
        if (deliveryMode) {
            if (chestPos == null) {
                chestPos = placeDeliveryChest();
            }

            if (deliveryAction != null) {
                if (deliveryAction.isComplete()) {
                    deliveryAction = null;
                } else {
                    deliveryAction.tick();
                    return; // mining stays paused while delivering
                }
            }

            boolean workActive = currentAction != null || !taskQueue.isEmpty();
            boolean canDeliver = chestPos != null && deliveryAction == null;
            boolean shouldShuttle = canDeliver && steve.collectedCount() >= HALF_STACK;
            boolean shouldFinalDeposit = canDeliver && !workActive && steve.collectedCount() > 0;

            if (shouldShuttle || shouldFinalDeposit) {
                deliveryAction = new DeliverToChestAction(steve, chestPos);
                deliveryAction.start();
                return;
            }

            if (!workActive && steve.collectedCount() == 0) {
                deliveryMode = false; // job done and everything delivered
            }
        }
```

- [ ] **Step 3: Implement chest placement**

Add this private method (near `runGeneratedCode`):

```java
    /** Place a chest one block in front of the requesting player, on the ground. Returns null if impossible. */
    private net.minecraft.core.BlockPos placeDeliveryChest() {
        if (deliveryTarget == null) {
            return null;
        }
        net.minecraft.core.BlockPos base =
            DeliveryHelper.chestPositionInFront(deliveryTarget.blockPosition(), deliveryTarget.getDirection());

        // Find an air cell with a solid block beneath it, scanning a few blocks vertically.
        for (int dy = 1; dy >= -3; dy--) {
            net.minecraft.core.BlockPos spot = base.offset(0, dy, 0);
            boolean spotFree = steve.level().getBlockState(spot).canBeReplaced();
            boolean groundSolid = steve.level().getBlockState(spot.below()).isSolid();
            if (spotFree && groundSolid) {
                steve.level().setBlock(spot, Blocks.CHEST.defaultBlockState(), 3);
                SteveMod.LOGGER.info("Steve '{}' placed delivery chest at {}", steve.getSteveName(), spot);
                return spot;
            }
        }
        SteveMod.LOGGER.warn("Steve '{}' could not place delivery chest near {}", steve.getSteveName(), base);
        return null;
    }
```

- [ ] **Step 4: Verify it compiles + unit suite stays green**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL.
Run: `./gradlew test` → all existing tests + DeliveryHelperTest pass.

- [ ] **Step 5: In-game gate (the functional test)**

Run: `./gradlew runClient`. With an API key configured:
1. Stand somewhere, face an open spot, give **Steve** the command: `Steve mine 40 iron and bring it here`.
2. Expected:
   - A chest appears one block in front of you (`placed delivery chest at ...`).
   - Steve mines (descends, collects — `collectedCount` rises).
   - At 32 collected: log `Ticking action: Deliver items to chest...`, Steve walks to the chest, `deposited N items`, then resumes mining.
   - When 40 are mined, a final deposit empties his inventory into the chest.
3. Open the chest — it holds the raw iron.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/steve/ai/action/ActionExecutor.java
git commit -m "feat: bring-it-here delivery — place chest, shuttle half-stacks, final deposit"
```

---

## Self-Review

**Spec coverage:**
- Inventory + collection → Tasks 2, 3. ✓
- "bring it here" detection (deterministic, code) → Task 1 (`isBringItHere`), Task 4 (wired). ✓
- Chest in front of requesting player → Task 1 (`chestPositionInFront`), Task 4 (player plumbing), Task 6 (`placeDeliveryChest`). ✓
- Shuttle at half-stack (32), deposit all, pause/resume mining → Task 6 (`HALF_STACK`, pause via early `return`, `DeliverToChestAction`). ✓
- End at job quantity + final deposit → Task 6 (`shouldFinalDeposit`, `deliveryMode=false`). ✓
- Server-thread world ops → all world ops are in `tick()`/actions (server thread); the command thread only sets flags. ✓
- Tests: pure helpers unit-tested (Task 1); MC-coupled in-game-gated (Tasks 2–6, honestly noted). ✓

**Placeholder scan:** No TBD/TODO; every code step has complete code or an exact edit. The two "verify signature against the jar" notes are guarded with the already-verified signature, not placeholders.

**Type consistency:** `DeliveryHelper.isBringItHere(String)`, `DeliveryHelper.chestPositionInFront(BlockPos, Direction)`, `SteveEntity.getInventory()/addToInventory(ItemStack)→ItemStack/collectedCount()→int`, `DeliverToChestAction(SteveEntity, BlockPos)`, `ActionExecutor` fields `deliveryMode/deliveryTarget/chestPos/deliveryAction/HALF_STACK` and `processNaturalLanguageCommand(String, Player)` — used consistently across tasks. ✓
