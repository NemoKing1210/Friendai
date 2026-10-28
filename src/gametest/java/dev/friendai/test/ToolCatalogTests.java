package dev.friendai.test;

import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.llm.model.ToolSpec;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ToolCatalogTests {
    @GameTest
    public void every_registered_tool_has_a_catalog_entry(GameTestHelper h) {
        ToolCatalog catalog = ToolCatalog.defaults();
        List<String> missing = FriendaiTools.create(() -> 32).specs().stream()
                .map(ToolSpec::name)
                .filter(name -> catalog.meta(name).isEmpty())
                .toList();
        h.assertTrue(
                missing.isEmpty(), "tools without a ToolCatalog entry (add them to ToolCatalog.defaults): " + missing);
        h.succeed();
    }
}
