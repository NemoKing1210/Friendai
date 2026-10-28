package dev.friendai.features.creative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CreativeCommandPolicyTest {
    @Test
    void allowsOnlyValidatedGameplayFormsAndNormalizesOneSlash() {
        assertAllowed(" /time set day ", "time set day");
        assertAllowed("weather rain 1200", "weather rain 1200");
        assertAllowed("tp @s ~ ~2 ~-3", "tp @s ~ ~2 ~-3");
        assertAllowed("effect give @s minecraft:speed 60 1 true", "effect give @s minecraft:speed 60 1 true");
        assertAllowed("give Owner minecraft:diamond 64", "give Owner minecraft:diamond 64");
        assertAllowed("enchant Owner minecraft:sharpness 3", "enchant Owner minecraft:sharpness 3");
    }

    @Test
    void rejectsDelegationWrappersAndEveryUnlistedRoot() {
        for (String command : new String[] {
            "execute as @s run time set day",
            "function friendai:do_thing",
            "schedule function x 1t",
            "say hi",
            "op Owner",
            "time set day; weather clear",
            "//time set day"
        }) {
            assertFalse(CreativeCommandPolicy.evaluate(command, "Owner").allowed(), command);
        }
    }

    @Test
    void restrictsPlayerTargetsToTheCompanionOrExplicitOwner() {
        assertFalse(CreativeCommandPolicy.evaluate("tp @a 1 2 3", "Owner").allowed());
        assertFalse(CreativeCommandPolicy.evaluate("effect give @e minecraft:speed", "Owner")
                .allowed());
        assertFalse(CreativeCommandPolicy.evaluate("give Other minecraft:diamond", "Owner")
                .allowed());
        assertFalse(CreativeCommandPolicy.evaluate("enchant @s minecraft:sharpness", "Owner")
                .allowed());
    }

    @Test
    void boundsCountsDurationsAndCoordinates() {
        for (String command : new String[] {
            "give Owner minecraft:diamond 65", "weather clear 24001", "effect give @s minecraft:speed 0",
            "tp @s 30000001 80 0", "time add -1", "enchant Owner minecraft:sharpness 0"
        }) {
            assertFalse(CreativeCommandPolicy.evaluate(command, "Owner").allowed(), command);
        }
        assertTrue(CreativeCommandPolicy.evaluate("time add 24000", "Owner").allowed());
    }

    @Test
    void rejectsLongControlContainingAndMalformedInputs() {
        assertFalse(CreativeCommandPolicy.evaluate("time set day\nweather clear", "Owner")
                .allowed());
        assertFalse(
                CreativeCommandPolicy.evaluate("time set day\u0001", "Owner").allowed());
        assertFalse(CreativeCommandPolicy.evaluate("x".repeat(2049), "Owner").allowed());
        assertFalse(CreativeCommandPolicy.evaluate("tp @s NaN 1 2", "Owner").allowed());
        assertFalse(
                CreativeCommandPolicy.evaluate("weather clear 1 extra", "Owner").allowed());
        assertEquals(
                "time set day",
                CreativeCommandPolicy.evaluate("/time set day", "Owner").command());
    }

    private static void assertAllowed(String raw, String expected) {
        CreativeCommandPolicy.Decision decision = CreativeCommandPolicy.evaluate(raw, "Owner");
        assertTrue(decision.allowed(), decision.reason());
        assertEquals(expected, decision.command());
    }
}
