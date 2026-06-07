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
    void detectsNaturalDeliveryPhrasings() {
        assertTrue(DeliveryHelper.isBringItHere("find one stack of iron and bring it to me"));
        assertTrue(DeliveryHelper.isBringItHere("mine iron and BRING IT TO ME"));
        assertTrue(DeliveryHelper.isBringItHere("mine iron and bring them here"));
        assertTrue(DeliveryHelper.isBringItHere("mine iron and bring it back"));
    }

    @Test
    void rejectsWhenPhraseAbsentOrNull() {
        assertFalse(DeliveryHelper.isBringItHere("mine some iron"));
        assertFalse(DeliveryHelper.isBringItHere(null));
    }

    @Test
    void rejectsBringWithoutADeliveryTargetAndTargetWithoutBring() {
        assertFalse(DeliveryHelper.isBringItHere("bring a torch"));   // "bring" but no target
        assertFalse(DeliveryHelper.isBringItHere("mine iron here"));  // target word but no "bring"
    }

    @Test
    void chestPositionIsOneBlockInFacingDirection() {
        BlockPos player = new BlockPos(10, 64, 20);
        assertEquals(new BlockPos(10, 64, 19), DeliveryHelper.chestPositionInFront(player, Direction.NORTH));
        assertEquals(new BlockPos(11, 64, 20), DeliveryHelper.chestPositionInFront(player, Direction.EAST));
    }
}
