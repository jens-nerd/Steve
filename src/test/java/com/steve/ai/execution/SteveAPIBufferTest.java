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
