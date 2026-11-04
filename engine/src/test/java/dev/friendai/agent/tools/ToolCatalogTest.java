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
        List<String> placeHints = catalog.hints(EnumSet.of(ToolGroup.PLACE));
        assertTrue(
                placeHints.contains(catalog.meta("find_place").orElseThrow().whenToUse()),
                "the place turn sees the question tool's guidance: " + placeHints);
        assertTrue(
                placeHints.contains(catalog.meta("remember_place").orElseThrow().whenToUse()),
                "the place turn sees the teaching guidance: " + placeHints);
        assertFalse(catalog.namesFor(EnumSet.of(ToolGroup.PLACE)).contains("mine_blocks"));
    }

    @Test
    void findPlaceIsCoreSoAQuestionTurnSeesIt() {
        ToolMeta find = catalog.meta("find_place").orElseThrow();
        assertTrue(find.core(), "find_place must be core: reading the book unlocks no group");
        assertEquals(ToolGroup.CORE, find.group(), "find_place");
        assertEquals(List.of("find_place"), names(catalog.filter(List.of(spec("find_place")), Set.of())));
        assertTrue(
                catalog.hints(Set.of()).contains(find.whenToUse()),
                "a turn with no groups still sees the question tool's guidance");
    }

    @Test
    void planValidateIsNotAdvertisedOnAnOrdinaryActionTurn() {
        List<ToolSpec> specs = List.of(spec("plan_validate"), spec("mine_blocks"), spec("stop"));

        assertEquals(
                List.of("mine_blocks", "stop"),
                names(catalog.filter(specs, EnumSet.of(ToolGroup.GATHERING))),
                "queue_add validates the plan an ordinary turn is about to queue");
        assertEquals(
                List.of("plan_validate", "mine_blocks", "stop"),
                names(catalog.filter(specs, EnumSet.of(ToolGroup.GATHERING, ToolGroup.PLANNING))),
                "request_tools unlocks the planning group");
    }

    @Test
    void everyToolHasExactlyOneEntry() {
        long distinct = catalog.all().stream().map(ToolMeta::name).distinct().count();
        assertEquals(catalog.all().size(), distinct);
    }

    @Test
    void contributorMetadataOverridesDefaultsWithoutDuplicatingEntries() {
        ToolMeta contributed = new ToolMeta(
                "mine_blocks", ToolGroup.CORE, true, "Use mine_blocks immediately.", List.of("pickaxe"), Cost.LOW);

        ToolCatalog merged = catalog.withOverrides(List.of(contributed));

        assertEquals(contributed, merged.meta("mine_blocks").orElseThrow());
        assertEquals(catalog.all().size(), merged.all().size());
        assertEquals(
                catalog.all().stream().map(ToolMeta::name).toList(),
                merged.all().stream().map(ToolMeta::name).toList());
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
