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
