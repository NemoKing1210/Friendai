package dev.friendai.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.ToolSpec;
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

    private static void assertBefore(List<String> names, String first, String second) {
        assertTrue(names.indexOf(first) >= 0, "missing tool " + first);
        assertTrue(names.indexOf(second) >= 0, "missing tool " + second);
        assertTrue(names.indexOf(first) < names.indexOf(second), first + " must precede " + second);
    }
}
