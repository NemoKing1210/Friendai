package dev.friendai.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.llm.model.ToolSpec;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolCatalogTest {
    private static ToolSpec spec(String name) {
        return new ToolSpec(name, "d", new JsonObject());
    }

    private static List<String> names(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).toList();
    }

    private final ToolCatalog catalog = ToolCatalog.defaults();

    @Test
    void coreAlwaysVisible() {
        List<ToolSpec> specs = List.of(spec("stop"), spec("mine_blocks"), spec("request_tools"), spec("remember"));
        assertEquals(List.of("stop", "request_tools", "remember"), names(catalog.filter(specs, Set.of())));
    }

    @Test
    void filterKeepsOrderAndDropsOtherGroups() {
        List<ToolSpec> specs = List.of(spec("go_to"), spec("mine_blocks"), spec("craft_item"), spec("stop"));
        List<ToolSpec> kept = catalog.filter(specs, EnumSet.of(ToolGroup.GATHERING, ToolGroup.MOVEMENT));
        assertEquals(List.of("go_to", "mine_blocks", "stop"), names(kept));
    }

    @Test
    void unknownToolIsAlwaysVisible() {
        assertEquals(List.of("brand_new_tool"), names(catalog.filter(List.of(spec("brand_new_tool")), Set.of())));
    }

    @Test
    void parseGroupIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(ToolGroup.GATHERING, ToolGroup.parse(" gathering ").orElseThrow());
        assertTrue(ToolGroup.parse("teleportation").isEmpty());
        assertTrue(ToolGroup.parse(null).isEmpty());
        assertTrue(ToolGroup.parse("").isEmpty());
    }

    @Test
    void hintsHaveOneLinePerVisibleToolWithGuidance() {
        ToolCatalog custom = new ToolCatalog(List.of(
                new ToolMeta("mine_blocks", ToolGroup.GATHERING, false, "Use mine_blocks for ore.", List.of(), Cost.HIGH),
                new ToolMeta("craft_item", ToolGroup.CRAFTING, false, "Use craft_item for recipes.", List.of(), Cost.LOW),
                new ToolMeta("stop", ToolGroup.CORE, true, "", List.of(), Cost.LOW)));
        assertEquals(List.of("Use mine_blocks for ore."), custom.hints(EnumSet.of(ToolGroup.GATHERING)));
        assertTrue(custom.hints(Set.of()).isEmpty());
        assertEquals(2, custom.hints(EnumSet.of(ToolGroup.GATHERING, ToolGroup.CRAFTING)).size());
    }

    @Test
    void namesForIncludesCoreAndRequestedGroups() {
        Set<String> names = catalog.namesFor(EnumSet.of(ToolGroup.COOKING));
        assertTrue(names.contains("smelt_items"));
        assertTrue(names.contains("stop"));
        assertFalse(names.contains("mine_blocks"));
    }

    @Test
    void tradingToolsBelongToTheirOwnGroupWithSafeUsageGuidance() {
        Set<String> names = catalog.namesFor(EnumSet.of(ToolGroup.TRADING));
        assertTrue(names.containsAll(List.of("find_traders", "inspect_trader", "known_trades",
                "plan_trade", "trade_item")));
        assertFalse(names.contains("mine_blocks"));
        List<String> hints = catalog.hints(EnumSet.of(ToolGroup.TRADING));
        assertTrue(hints.stream().anyMatch(hint -> hint.contains("historical")));
        assertTrue(hints.stream().anyMatch(hint -> hint.contains("cap") && hint.contains("reserve")));
    }

    @Test
    void shoppingQuestionsMayReadMarketDataButCannotSeeSpendingTool() {
        List<ToolSpec> specs = List.of(spec("known_trades"), spec("plan_trade"), spec("trade_item"));
        assertEquals(List.of("known_trades", "plan_trade"), names(
                catalog.filter(specs, EnumSet.of(ToolGroup.TRADING), true)));
        assertTrue(catalog.hints(EnumSet.of(ToolGroup.TRADING), true).stream()
                .noneMatch(hint -> hint.contains("Spend only")));
    }

    @Test
    void everyToolHasExactlyOneEntry() {
        long distinct = catalog.all().stream().map(ToolMeta::name).distinct().count();
        assertEquals(catalog.all().size(), distinct);
    }
}
