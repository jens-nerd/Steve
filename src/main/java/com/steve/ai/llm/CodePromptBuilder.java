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
