package dev.friendai.test;

import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.server.agent.tools.FriendaiTools;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ToolCatalogTests {
    @GameTest
    public void every_registered_tool_has_a_catalog_entry(GameTestHelper h) {
        var builtins = FriendaiTools.build(() -> 32, dev.friendai.features.memory.minecraft.tool.MemoryAccess.DISABLED);
        List<String> missing = builtins.registry().specs().stream()
                .map(ToolSpec::name)
                .filter(name -> builtins.catalog().meta(name).isEmpty())
                .toList();
        h.assertTrue(missing.isEmpty(), "registered tools without contributor metadata: " + missing);
        h.succeed();
    }
}
