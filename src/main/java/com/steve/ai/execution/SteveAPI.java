package com.steve.ai.execution;

import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.graalvm.polyglot.HostAccess;

import java.util.*;

/**
 * Safe API bridge between LLM-generated code and Minecraft.
 * All operations are validated and buffered; callers commit the buffer
 * into the live action queue only after a script succeeds (atomic planning).
 *
 * This class is exposed to JavaScript code as the `steve` global object.
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

    // ====================
    // ASYNC OPERATIONS (Buffer Actions)
    // ====================

    /**
     * Navigate to a specific position
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    @HostAccess.Export
    public void move(double x, double y, double z) {
        Map<String, Object> params = new HashMap<>();
        params.put("x", x);
        params.put("y", y);
        params.put("z", z);
        enqueue(new Task("pathfind", params));
    }

    /**
     * Build a structure at a specific location
     * @param structureType Type of structure (house, castle, tower, barn, etc.)
     * @param position Map with x, y, z coordinates
     */
    @HostAccess.Export
    public void build(String structureType, Map<String, Double> position) {
        if (structureType == null || structureType.trim().isEmpty()) {
            throw new IllegalArgumentException("Structure type cannot be empty");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("structure", structureType.toLowerCase());

        // Add position if provided
        if (position != null && position.containsKey("x") && position.containsKey("y") && position.containsKey("z")) {
            params.put("x", position.get("x").intValue());
            params.put("y", position.get("y").intValue());
            params.put("z", position.get("z").intValue());
        }

        enqueue(new Task("build", params));
    }

    /**
     * Build a structure (using default location - in front of player)
     * @param structureType Type of structure
     */
    @HostAccess.Export
    public void build(String structureType) {
        build(structureType, null);
    }

    /**
     * Mine a specific resource
     * @param blockType Type of block/ore to mine (e.g., "iron_ore", "diamond_ore")
     * @param count Number of blocks to mine
     */
    @HostAccess.Export
    public void mine(String blockType, int count) {
        if (blockType == null || blockType.trim().isEmpty()) {
            throw new IllegalArgumentException("Block type cannot be empty");
        }

        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("blockType", blockType.toLowerCase());
        params.put("count", count);

        enqueue(new Task("mine", params));
    }

    /**
     * Attack a target entity type
     * @param entityType Type of entity to attack (e.g., "zombie", "skeleton")
     */
    @HostAccess.Export
    public void attack(String entityType) {
        if (entityType == null || entityType.trim().isEmpty()) {
            throw new IllegalArgumentException("Entity type cannot be empty");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("target", entityType.toLowerCase());

        enqueue(new Task("attack", params));
    }

    /**
     * Craft an item
     * @param itemName Name of item to craft (e.g., "iron_pickaxe", "crafting_table")
     * @param count Number of items to craft
     */
    @HostAccess.Export
    public void craft(String itemName, int count) {
        if (itemName == null || itemName.trim().isEmpty()) {
            throw new IllegalArgumentException("Item name cannot be empty");
        }

        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("item", itemName.toLowerCase());
        params.put("count", count);

        enqueue(new Task("craft", params));
    }

    /**
     * Place a single block at a specific position
     * @param blockType Type of block to place
     * @param position Map with x, y, z coordinates
     */
    @HostAccess.Export
    public void place(String blockType, Map<String, Double> position) {
        if (blockType == null || blockType.trim().isEmpty()) {
            throw new IllegalArgumentException("Block type cannot be empty");
        }

        if (position == null || !position.containsKey("x") || !position.containsKey("y") || !position.containsKey("z")) {
            throw new IllegalArgumentException("Position must include x, y, z coordinates");
        }

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
    }

    /**
     * Send a chat message
     * @param message Message to send
     */
    @HostAccess.Export
    public void say(String message) {
        if (message != null && !message.trim().isEmpty()) {
            // TODO: Implement chat message sending
            // For now, just log it
        }
    }

    /**
     * Follow a player by name
     * @param playerName Name of player to follow
     */
    @HostAccess.Export
    public void follow(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new IllegalArgumentException("Player name cannot be empty");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("playerName", playerName);

        enqueue(new Task("follow", params));
    }

    /**
     * Gather a resource (combines finding and collecting)
     * @param resourceType Type of resource to gather
     * @param count Number to gather
     */
    @HostAccess.Export
    public void gather(String resourceType, int count) {
        if (resourceType == null || resourceType.trim().isEmpty()) {
            throw new IllegalArgumentException("Resource type cannot be empty");
        }

        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("resource", resourceType.toLowerCase());
        params.put("count", count);

        enqueue(new Task("gather", params));
    }

    // ====================
    // SYNC READ OPERATIONS
    // ====================

    /**
     * Get Steve's current position
     * @return Map with x, y, z coordinates
     */
    @HostAccess.Export
    public Map<String, Double> getPosition() {
        Vec3 pos = steve.position();
        Map<String, Double> position = new HashMap<>();
        position.put("x", pos.x);
        position.put("y", pos.y);
        position.put("z", pos.z);
        return position;
    }

    /**
     * Get nearby blocks within a radius
     * @param radius Search radius (max 16 blocks)
     * @return List of block type names
     */
    @HostAccess.Export
    public List<String> getNearbyBlocks(int radius) {
        if (radius <= 0 || radius > 16) {
            throw new IllegalArgumentException("Radius must be between 1 and 16");
        }

        Set<String> blockTypes = new HashSet<>();
        BlockPos stevePos = steve.blockPosition();

        // Sample blocks within radius (not every block to avoid performance issues)
        for (int x = -radius; x <= radius; x += 2) {
            for (int y = -radius; y <= radius; y += 2) {
                for (int z = -radius; z <= radius; z += 2) {
                    BlockPos pos = stevePos.offset(x, y, z);
                    BlockState state = steve.level().getBlockState(pos);
                    String blockName = state.getBlock().getName().getString().toLowerCase();

                    if (!blockName.contains("air")) {
                        blockTypes.add(blockName);
                    }
                }
            }
        }

        return new ArrayList<>(blockTypes);
    }

    /**
     * Get nearby entities within a radius
     * @param radius Search radius (max 32 blocks)
     * @return List of entity type names
     */
    @HostAccess.Export
    public List<String> getNearbyEntities(int radius) {
        if (radius <= 0 || radius > 32) {
            throw new IllegalArgumentException("Radius must be between 1 and 32");
        }

        List<String> entityNames = new ArrayList<>();
        Vec3 stevePos = steve.position();
        AABB searchBox = new AABB(
            stevePos.x - radius, stevePos.y - radius, stevePos.z - radius,
            stevePos.x + radius, stevePos.y + radius, stevePos.z + radius
        );

        List<Entity> entities = steve.level().getEntities(steve, searchBox);

        for (Entity entity : entities) {
            if (entity instanceof LivingEntity) {
                String entityName = entity.getType().getDescription().getString().toLowerCase();
                entityNames.add(entityName);
            }
        }

        return entityNames;
    }

    /**
     * Check if Steve is idle (no buffered actions)
     * @return true if buffer is empty, false otherwise
     */
    @HostAccess.Export
    public boolean isIdle() {
        return buffer.isEmpty();
    }

    /**
     * Get the number of pending actions
     * @return Number of actions in buffer
     */
    @HostAccess.Export
    public int getPendingActionCount() {
        return buffer.size();
    }

    /**
     * Wait for a duration (in milliseconds)
     * NOTE: NOT exported to JS — would freeze the server thread.
     * @param milliseconds Time to wait
     */
    public void wait(int milliseconds) throws InterruptedException {
        if (milliseconds > 0 && milliseconds < 30000) {  // Max 30 seconds
            Thread.sleep(milliseconds);
        }
    }

    // ====================
    // INTERNAL METHODS
    // ====================

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

    /** True if (x,y,z) is within {@code radius} blocks (Euclidean) of Steve's position. */
    public static boolean withinRadius(double sx, double sy, double sz,
                                       int x, int y, int z, int radius) {
        double dx = x - sx, dy = y - sy, dz = z - sz;
        return (dx * dx + dy * dy + dz * dz) <= (double) radius * radius;
    }

    /**
     * Get the Steve entity (for internal use)
     */
    SteveEntity getSteveEntity() {
        return steve;
    }
}
