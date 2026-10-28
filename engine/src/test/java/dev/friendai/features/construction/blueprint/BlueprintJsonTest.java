package dev.friendai.features.construction.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BlueprintJsonTest {
    private static final Blueprint HOUSE = new Blueprint(
            "small_house",
            3,
            2,
            1,
            List.of(
                    new BlockSpec(0, 0, 0, "minecraft:cobblestone"),
                    new BlockSpec(1, 0, 0, "minecraft:cobblestone"),
                    new BlockSpec(0, 1, 0, "minecraft:oak_stairs[facing=east,half=bottom]")));

    @Test
    void jsonRoundTrip() {
        String json = BlueprintJson.toJson(HOUSE);
        Optional<Blueprint> parsed = BlueprintJson.fromJson(json);
        assertTrue(parsed.isPresent(), "the saved blueprint must parse");
        assertEquals(HOUSE, parsed.get());
    }

    @Test
    void jsonShape() {
        String json = BlueprintJson.toJson(HOUSE);
        assertTrue(json.contains("\"name\":\"small_house\""), json);
        assertTrue(json.contains("\"size\":[3,2,1]"), json);
        assertTrue(json.contains("[0,1,0,\"minecraft:oak_stairs[facing=east,half=bottom]\"]"), json);
    }

    @Test
    void rejectsBrokenJson() {
        assertTrue(BlueprintJson.fromJson("{\"name\":").isEmpty());
        assertTrue(BlueprintJson.fromJson("{\"name\":\"x\",\"size\":[1,1,1]}").isEmpty());
    }
}
