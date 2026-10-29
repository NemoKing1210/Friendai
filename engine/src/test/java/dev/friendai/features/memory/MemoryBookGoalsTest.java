package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class MemoryBookGoalsTest {
    @Test
    void memoryRecordDoesNotContainGoalState() {
        String json = new Gson().toJson(new MemoryBook().toFile());
        assertFalse(json.contains("goals"));
    }
}
