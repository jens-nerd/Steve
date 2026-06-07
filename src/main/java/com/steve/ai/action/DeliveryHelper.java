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
