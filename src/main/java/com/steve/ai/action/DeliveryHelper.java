package com.steve.ai.action;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Pure helpers for the "bring it here" delivery behaviour. */
public final class DeliveryHelper {

    private DeliveryHelper() {}

    /**
     * True if the command asks Steve to bring collected items to the requesting player.
     * Matches the verb "bring" together with a delivery target ("here", "to me", or "back"),
     * so natural phrasings like "bring it here", "bring it to me", and "bring it back" all trigger.
     */
    public static boolean isBringItHere(String command) {
        if (command == null) {
            return false;
        }
        String c = command.toLowerCase();
        boolean asksToBring = c.contains("bring");
        boolean hasDeliveryTarget = c.contains("here") || c.contains("to me") || c.contains("back");
        return asksToBring && hasDeliveryTarget;
    }

    /** The block one step in front of the player (where the delivery chest goes). */
    public static BlockPos chestPositionInFront(BlockPos playerPos, Direction facing) {
        return playerPos.relative(facing);
    }
}
