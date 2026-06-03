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

    @Test
    void withinRadiusInclusiveAtExactBoundary() {
        // A block exactly `radius` away must be allowed (the contract is <=, not <).
        assertTrue(SteveAPI.withinRadius(0, 0, 0, 64, 0, 0, 64));
    }
}
