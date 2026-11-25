package dev.friendai.agent.llm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SystemPromptTest {
    @Test
    void joinsTheStablePartFirst() {
        SystemPrompt prompt = new SystemPrompt("rules", "state");
        assertEquals("rules\nstate", prompt.text());
        assertEquals("rules", prompt.stable());
        assertEquals("state", prompt.volatileText());
        assertTrue(prompt.hasStablePart());
    }

    @Test
    void aPromptWithOnePartDoesNotCarryASeparator() {
        assertEquals("rules", new SystemPrompt("rules", "").text());
        assertEquals("state", new SystemPrompt("", "state").text());
        assertEquals("", new SystemPrompt(null, null).text());
    }

    @Test
    void aSingleStringIsTreatedAsVolatile() {
        SystemPrompt prompt = SystemPrompt.of("planner");
        assertEquals("planner", prompt.text());
        assertFalse(prompt.hasStablePart(), "one undivided string must not claim a cache breakpoint");
    }

    @Test
    void aRequestKeepsTheSplitItWasGiven() {
        SystemPrompt split = new SystemPrompt("rules", "state");
        LlmRequest request = new LlmRequest(
                "m", split, List.of(), List.of(), null, 10, false, dev.friendai.agent.llm.RequestTelemetry.NONE);
        assertEquals(split, request.system());
        assertEquals("rules\nstate", request.system().text());
    }

    @Test
    void aRequestBuiltFromAStringStaysUncached() {
        LlmRequest request = new LlmRequest("m", "planner", List.of(), List.of(), null, 10);
        assertEquals("planner", request.system().text());
        assertFalse(request.system().hasStablePart());
    }
}
