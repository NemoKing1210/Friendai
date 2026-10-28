package dev.friendai.task.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlanSaveTest {
    private static JsonObject args(String tool) {
        JsonObject json = new JsonObject();
        json.addProperty("item", tool);
        return json;
    }

    @Test
    void roundTripKeepsEverything() {
        List<PlanSave.Step> steps = List.of(
                new PlanSave.Step("drop_items", args("minecraft:dirt")),
                new PlanSave.Step("pickup_items", args("minecraft:dirt")));
        String saved = PlanSave.encode("drop and pick up", 1, List.of("dropped 1 minecraft:dirt"), null, steps);
        assertTrue(saved.startsWith(PlanSave.PREFIX), saved);

        PlanSave.Saved decoded = PlanSave.decode(saved).orElseThrow();
        assertEquals("drop and pick up", decoded.goal());
        assertEquals(1, decoded.index());
        assertEquals(List.of("dropped 1 minecraft:dirt"), decoded.done());
        assertEquals(null, decoded.active());
        assertEquals(2, decoded.steps().size());
        assertEquals("drop_items", decoded.steps().get(0).tool());
        assertEquals("minecraft:dirt", decoded.steps().get(0).args().get("item").getAsString());
    }

    @Test
    void nullGoalAndActiveAreOmitted() {
        String saved =
                PlanSave.encode(null, 0, List.of(), null, List.of(new PlanSave.Step("drop_items", new JsonObject())));
        PlanSave.Saved decoded = PlanSave.decode(saved).orElseThrow();
        assertEquals(null, decoded.goal());
        assertEquals(null, decoded.active());
        assertTrue(decoded.done().isEmpty());
    }

    @Test
    void activeTaskIsKept() {
        String saved = PlanSave.encode(
                "g",
                0,
                List.of(),
                "build:id,1,2,3,north,true,false",
                List.of(new PlanSave.Step("build_structure", new JsonObject())));
        assertEquals(
                "build:id,1,2,3,north,true,false",
                PlanSave.decode(saved).orElseThrow().active());
    }

    @Test
    void rejectsBadInput() {
        assertTrue(PlanSave.decode(null).isEmpty());
        assertTrue(PlanSave.decode("build:id,1,2,3,north,true,false").isEmpty());
        assertTrue(PlanSave.decode("plan:not json").isEmpty());
        assertTrue(PlanSave.decode("plan:{}").isEmpty());
        assertTrue(PlanSave.decode("plan:[]").isEmpty());

        String noSteps = PlanSave.encode("g", 0, List.of(), null, List.of());
        assertTrue(PlanSave.decode(noSteps).isEmpty());

        String tooFar =
                PlanSave.encode("g", 2, List.of(), null, List.of(new PlanSave.Step("drop_items", new JsonObject())));
        assertTrue(PlanSave.decode(tooFar).isEmpty());

        String blankTool = "plan:{\"index\":0,\"done\":[],\"steps\":[{\"tool\":\"\",\"args\":{}}]}";
        assertTrue(PlanSave.decode(blankTool).isEmpty());

        String badArgs = "plan:{\"index\":0,\"done\":[],\"steps\":[{\"tool\":\"a\",\"args\":\"text\"}]}";
        assertTrue(PlanSave.decode(badArgs).isEmpty());
    }

    @Test
    void unknownFieldsAreIgnored() {
        String text = "plan:{\"index\":0,\"done\":[],\"steps\":[{\"tool\":\"a\",\"args\":{}}],\"future\":1}";
        Optional<PlanSave.Saved> decoded = PlanSave.decode(text);
        assertTrue(decoded.isPresent());
        assertFalse(decoded.get().steps().isEmpty());
    }
}
