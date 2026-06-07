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
