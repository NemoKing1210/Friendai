package dev.friendai.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolSpec;
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
                new ToolMeta(
                        "mine_blocks", ToolGroup.GATHERING, false, "Use mine_blocks for ore.", List.of(), Cost.HIGH),
                new ToolMeta(
                        "craft_item", ToolGroup.CRAFTING, false, "Use craft_item for recipes.", List.of(), Cost.LOW),
                new ToolMeta("stop", ToolGroup.CORE, true, "", List.of(), Cost.LOW)));
        assertEquals(List.of("Use mine_blocks for ore."), custom.hints(EnumSet.of(ToolGroup.GATHERING)));
        assertTrue(custom.hints(Set.of()).isEmpty());
        assertEquals(
                2,
                custom.hints(EnumSet.of(ToolGroup.GATHERING, ToolGroup.CRAFTING))
                        .size());
    }

    @Test
    void namesForIncludesCoreAndRequestedGroups() {
        Set<String> names = catalog.namesFor(EnumSet.of(ToolGroup.COOKING));
        assertTrue(names.contains("smelt_items"));
        assertTrue(names.contains("stop"));
        assertFalse(names.contains("mine_blocks"));
    }

    @Test
    void combatSurvivalAndEquipmentToolsBelongToTheirOwningGroups() {
        assertEquals("COMBAT", catalog.meta("attack").orElseThrow().group().name());
        assertEquals(
                "INVENTORY", catalog.meta("equip_item").orElseThrow().group().name());
        for (String tool :
                List.of("eat", "get_food", "allow_food", "forbid_food", "reset_food_rule", "get_food_rules", "sleep")) {
            assertEquals("SURVIVAL", catalog.meta(tool).orElseThrow().group().name(), tool);
        }
        assertFalse(catalog.namesFor(EnumSet.of(ToolGroup.INTERACTION)).contains("sleep"));
    }

    @Test
    void onlyTheMutatingPlaceToolsStayInThePlaceGroup() {
        Set<String> names = catalog.namesFor(EnumSet.of(ToolGroup.PLACE));
        for (String tool : List.of("remember_place", "go_to_place", "rename_place", "forget_place")) {
            assertTrue(names.contains(tool), tool + " missing from " + names);
            assertEquals(ToolGroup.PLACE, catalog.meta(tool).orElseThrow().group(), tool);
        }
        assertEquals(2, catalog.hints(EnumSet.of(ToolGroup.PLACE)).size());
        assertFalse(catalog.namesFor(EnumSet.of(ToolGroup.PLACE)).contains("mine_blocks"));
    }

    @Test
    void findPlaceIsCoreSoAQuestionTurnSeesIt() {
        ToolMeta find = catalog.meta("find_place").orElseThrow();
        assertTrue(find.core(), "find_place must be core: reading the book unlocks no group");
        assertEquals(ToolGroup.CORE, find.group(), "find_place");
        assertEquals(List.of("find_place"), names(catalog.filter(List.of(spec("find_place")), Set.of())));
        assertEquals(List.of(catalog.meta("find_place").orElseThrow().whenToUse()), catalog.hints(Set.of()));
    }

    @Test
    void everyToolHasExactlyOneEntry() {
        long distinct = catalog.all().stream().map(ToolMeta::name).distinct().count();
        assertEquals(catalog.all().size(), distinct);
    }

    @Test
    void creativeToolsExplainWhenToUseThemAndTheirLimits() {
        List<String> hints = catalog.hints(EnumSet.of(ToolGroup.CONTROL));
        assertTrue(
                hints.stream()
                        .anyMatch(line ->
                                line.contains("create_item") && line.contains("instead of mining unnecessarily")),
                hints.toString());
        assertTrue(
                hints.stream().anyMatch(line -> line.contains("fly_to") && line.contains("collision-safe route")),
                hints.toString());
        assertTrue(
                hints.stream()
                        .anyMatch(line -> line.contains("execute_command")
                                && line.contains("without claiming a denied or failed command succeeded")),
                hints.toString());
    }
}
