package dev.friendai.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.goal.GoalView;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.brain.Persona;
import dev.friendai.brain.PromptBuilder;
import dev.friendai.brain.TurnKind;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolHintsSectionTest {
    private static final Persona PERSONA = new Persona("F", "d", "auto");
    private static final Caller OWNER = new Caller(UUID.randomUUID(), "S", AccessLevel.OWNER);
    private final ToolCatalog catalog = ToolCatalog.defaults();

    private String prompt(Set<ToolGroup> visible, int budget) {
        return PromptBuilder.system(
                PERSONA, "Position: 1 2 3", OWNER, "", TurnKind.PLAYER, GoalView.EMPTY, budget, catalog.hints(visible));
    }

    @Test
    void treeRulesAppearWhenTreeToolsAreVisible() {
        String s = prompt(EnumSet.of(ToolGroup.GATHERING), 0);
        assertTrue(s.contains("Tool notes:"), s);
        assertTrue(s.contains("find_trees") && s.contains("fell_tree") && s.contains("near: \"me\""), s);
        assertTrue(s.contains("Use mine_blocks for a kind of block"), s);
    }

    @Test
    void treeRulesAbsentWhenOnlyCoreIsVisible() {
        String s = prompt(EnumSet.noneOf(ToolGroup.class), 0);
        assertFalse(s.contains("To chop a tree"), s);
        assertFalse(s.contains("Use mine_blocks for a kind of block"), s);
        assertTrue(s.contains("call find_place first"), "the core find_place hint is always shown: " + s);
        assertTrue(s.contains("Rules:"), s);
    }

    @Test
    void everyMovedRuleSurvivesWithAllGroupsVisible() {
        String s = prompt(EnumSet.allOf(ToolGroup.class), 0);
        for (String rule : List.of(
                "To get an item you don't have, prefer obtain_item",
                "To chop a tree, call find_trees",
                "near: \"me\" is the default for the tree tools",
                "Use mine_blocks for a kind of block",
                "preview_structure before build_structure",
                "call set_pace",
                "otherwise call get_food")) {
            assertTrue(s.contains(rule), "lost rule: " + rule);
        }
    }

    @Test
    void budgetCutsToolHintsBeforeTheRules() {
        String full = prompt(EnumSet.allOf(ToolGroup.class), 0);
        String tight = prompt(EnumSet.allOf(ToolGroup.class), full.length() - 20);
        assertFalse(tight.contains("Tool notes:"), "the hints are the first thing cut");
        assertTrue(tight.contains("Rules:") && tight.contains("Current state:") && tight.contains("Speech:"));
    }
}
