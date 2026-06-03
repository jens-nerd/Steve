package com.steve.ai.execution;

import com.steve.ai.action.Task;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import static org.junit.jupiter.api.Assertions.*;

class SteveAPITaskContractTest {

    private Task firstTask(SteveAPI api) {
        Queue<Task> q = new LinkedList<>();
        api.drainTo(q);
        return q.peek();
    }

    @Test
    void mineEmitsBlockAndQuantityKeys() {
        SteveAPI api = new SteveAPI(null, 1000, 64);
        api.mine("iron_ore", 5);
        Task t = firstTask(api);
        assertEquals("mine", t.getAction());
        assertEquals("iron_ore", t.getStringParameter("block"));
        assertEquals(5, t.getIntParameter("quantity", -1));
    }

    @Test
    void followEmitsPlayerKey() {
        SteveAPI api = new SteveAPI(null, 1000, 64);
        api.follow("Steve");
        Task t = firstTask(api);
        assertEquals("follow", t.getAction());
        assertEquals("Steve", t.getStringParameter("player"));
    }

    @Test
    void craftEmitsItemAndQuantityKeys() {
        SteveAPI api = new SteveAPI(null, 1000, 64);
        api.craft("oak_planks", 3);
        Task t = firstTask(api);
        assertEquals("craft", t.getAction());
        assertEquals("oak_planks", t.getStringParameter("item"));
        assertEquals(3, t.getIntParameter("quantity", -1));
    }

    @Test
    void gatherEmitsResourceAndQuantityKeys() {
        SteveAPI api = new SteveAPI(null, 1000, 64);
        api.gather("wood", 4);
        Task t = firstTask(api);
        assertEquals("gather", t.getAction());
        assertEquals("wood", t.getStringParameter("resource"));
        assertEquals(4, t.getIntParameter("quantity", -1));
    }

    @Test
    void placeEmitsBlockAndCoordKeys() {
        SteveAPI api = new SteveAPI(null, 1000, 64);
        Map<String, Double> pos = new HashMap<>();
        pos.put("x", 1.0); pos.put("y", 64.0); pos.put("z", 2.0);
        api.place("sunflower", pos);
        Task t = firstTask(api);
        assertEquals("place", t.getAction());
        assertEquals("sunflower", t.getStringParameter("block"));
        assertEquals(1, t.getIntParameter("x", -1));
        assertEquals(64, t.getIntParameter("y", -1));
        assertEquals(2, t.getIntParameter("z", -1));
    }
}
