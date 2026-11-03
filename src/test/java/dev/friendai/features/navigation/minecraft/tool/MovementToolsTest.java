package dev.friendai.features.navigation.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The arguments and the catalogue entry of every movement tool, checked before the game is running. */
class MovementToolsTest {

    private static FeatureToolRegistry registry() {
        return new FeatureToolRegistry(new ToolRegistry<>(), MovementToolsTest::stub);
    }

    private static Tool<CompanionEntity> stub(TaskToolDefinition definition) {
        return new Tool<>() {
            @Override
            public String name() {
                return definition.name();
            }

            @Override
            public String description() {
                return definition.description();
            }

            @Override
            public JsonObject schema() {
                return definition.schema();
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return ToolResult.ok("stub");
            }
        };
    }

    private static JsonObject argsWith(String first, Object firstValue) {
        JsonObject args = new JsonObject();
        add(args, first, firstValue);
        return args;
    }

    private static void add(JsonObject args, String name, Object value) {
        if (value instanceof Number number) {
            args.addProperty(name, number);
        } else {
            args.addProperty(name, String.valueOf(value));
        }
    }

    @Test
    void aWalkNeedsAllThreeCoordinates() {
        JsonObject args = argsWith("x", 1);
        add(args, "y", 64);

        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> MovementTools.point(args, -64, 320));

        assertTrue(refused.getMessage().contains("x,y,z"), refused.getMessage());
    }

    @Test
    void aNonFiniteCoordinateIsRejectedBeforeATaskExists() {
        JsonObject infinite = argsWith("x", Double.POSITIVE_INFINITY);
        add(infinite, "y", 64);
        add(infinite, "z", 0);
        JsonObject nan = argsWith("x", Double.NaN);
        add(nan, "y", 64);
        add(nan, "z", 0);

        assertThrows(IllegalArgumentException.class, () -> MovementTools.point(infinite, -64, 320));
        assertThrows(IllegalArgumentException.class, () -> MovementTools.point(nan, -64, 320));
    }

    @Test
    void aHeightOutsideTheDimensionIsRejected() {
        JsonObject above = argsWith("x", 1);
        add(above, "y", 500);
        add(above, "z", 1);
        JsonObject below = argsWith("x", 1);
        add(below, "y", -80);
        add(below, "z", 1);

        assertThrows(IllegalArgumentException.class, () -> MovementTools.point(above, -64, 320));
        assertThrows(IllegalArgumentException.class, () -> MovementTools.point(below, -64, 320));
    }

    @Test
    void aPointInsideTheDimensionIsAccepted() {
        JsonObject args = argsWith("x", 1.5);
        add(args, "y", 64.0);
        add(args, "z", -3.25);

        Vec3 point = MovementTools.point(args, -64, 320);

        assertEquals(1.5, point.x);
        assertEquals(64.0, point.y);
        assertEquals(-3.25, point.z);
    }

    @Test
    void aTargetAndCoordinatesTogetherAreRejected() {
        JsonObject args = argsWith("target", "owner");
        add(args, "x", 1);
        add(args, "y", 64);
        add(args, "z", 1);

        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> MovementTools.goTo(null, args));

        assertTrue(refused.getMessage().contains("either"), refused.getMessage());
    }

    @Test
    void harmlessMovementIsCreativeSafeAndTerrainChangingMovementIsNot() {
        FeatureToolRegistry registry = registry();
        new MovementTools().register(registry);
        Map<String, TaskToolDefinition> definitions = registry.taskDefinitions();

        assertFalse(definitions.get("stay").creativeSensitive(), "waiting in place changes nothing");
        assertFalse(definitions.get("jump").creativeSensitive(), "a jump changes nothing");
        assertTrue(definitions.get("go_to").creativeSensitive(), "a walk may dig and bridge");
        assertTrue(definitions.get("follow_player").creativeSensitive(), "following may dig and bridge");
        assertTrue(definitions.get("travel_through_portal").creativeSensitive(), "a portal trip moves the companion");
    }

    @Test
    void everyMovementToolSaysWhenToUseIt() {
        FeatureToolRegistry registry = registry();
        new MovementTools().register(registry);

        for (var meta : registry.catalogMetadata()) {
            assertFalse(meta.whenToUse().isBlank(), meta.name() + " has no when-to-use hint");
        }
    }
}
