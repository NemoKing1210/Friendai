package dev.friendai.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.execution.tool.AccessLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class FriendaiToolsTest {
    @Test
    void featureContributorsPreserveLegacyToolGroupOrder() {
        List<String> names = FriendaiTools.build(() -> 8, MemoryAccess.DISABLED).registry().specs().stream()
                .map(ToolSpec::name)
                .toList();

        assertBefore(names, "follow_player", "fell_tree");
        assertBefore(names, "fell_tree", "give_items");
        assertBefore(names, "give_items", "place_block");
        assertBefore(names, "place_block", "store_items");
        assertBefore(names, "store_items", "craft_item");
    }

    @Test
    void combatSurvivalAndAnimalToolsKeepTheirAccessOwnership() {
        var registry = FriendaiTools.build(() -> 8, MemoryAccess.DISABLED).registry();
        List<String> trusted =
                registry.specs(AccessLevel.TRUSTED).stream().map(ToolSpec::name).toList();
        List<String> owner =
                registry.specs(AccessLevel.OWNER).stream().map(ToolSpec::name).toList();

        assertTrue(trusted.containsAll(List.of("attack", "eat", "get_food", "sleep", "breed_animals", "fish")));
        assertFalse(trusted.contains("attack_player"));
        assertFalse(trusted.contains("set_combat_settings"));
        assertFalse(trusted.contains("allow_food"));
        assertTrue(owner.containsAll(List.of("attack_player", "set_combat_settings", "allow_food", "get_food_rules")));
    }

    @Test
    void builtinsExposeCatalogAndRestoreEligibilityFromFeatureContributions() {
        FriendaiTools.Builtins builtins = FriendaiTools.build(() -> 8, MemoryAccess.DISABLED);

        assertTrue(builtins.catalog().meta("mine_blocks").isPresent());
        assertTrue(builtins.catalog().meta("mine_blocks").orElseThrow().group() == ToolGroup.GATHERING);
        assertTrue(builtins.restorableTaskNames().contains("mine_blocks"));
        assertTrue(builtins.restorableTaskNames().contains("trade_item"));
    }

    private static void assertBefore(List<String> names, String first, String second) {
        assertTrue(names.indexOf(first) >= 0, "missing tool " + first);
        assertTrue(names.indexOf(second) >= 0, "missing tool " + second);
        assertTrue(names.indexOf(first) < names.indexOf(second), first + " must precede " + second);
    }
}
