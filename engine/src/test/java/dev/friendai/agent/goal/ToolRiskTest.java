package dev.friendai.agent.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRiskTest {
    @Test
    void readingAndSelfCareAreSafe() {
        assertEquals(RiskClass.SAFE, ToolRisk.of(List.of()));
        assertEquals(RiskClass.SAFE, ToolRisk.of(List.of("look_around", "get_inventory", "find_trees")));
        assertEquals(RiskClass.SAFE, ToolRisk.of(List.of("set_goal", "queue_show", "plan_trade", "sneak")));
    }

    @Test
    void ordinaryWorldWorkIsCareful() {
        assertEquals(RiskClass.CAREFUL, ToolRisk.of(List.of("go_to")));
        assertEquals(RiskClass.CAREFUL, ToolRisk.of(List.of("mine_blocks", "place_block", "farm")));
        assertEquals(RiskClass.CAREFUL, ToolRisk.of(List.of("store_items", "craft_item", "trade_item")));
    }

    @Test
    void fightingCreativePowersAndClearingAreDestructive() {
        assertEquals(RiskClass.DESTRUCTIVE, ToolRisk.of(List.of("go_to", "attack")));
        assertEquals(RiskClass.DESTRUCTIVE, ToolRisk.of(List.of("attack_player")));
        assertEquals(RiskClass.DESTRUCTIVE, ToolRisk.of(List.of("create_item", "fly_to", "execute_command")));
        assertEquals(RiskClass.DESTRUCTIVE, ToolRisk.of(List.of("clear_area")));
    }

    @Test
    void oneDestructiveToolWinsWhateverTheOrder() {
        List<String> mixed = List.of("look_around", "get_inventory", "mine_blocks", "execute_command");
        assertEquals(RiskClass.DESTRUCTIVE, ToolRisk.of(mixed));
        assertEquals(RiskClass.DESTRUCTIVE, ToolRisk.of(mixed.reversed()));
        assertEquals(RiskClass.DESTRUCTIVE, RiskClass.SAFE.max(RiskClass.DESTRUCTIVE));
        assertEquals(RiskClass.CAREFUL, RiskClass.SAFE.max(RiskClass.CAREFUL));
        assertEquals(RiskClass.CAREFUL, RiskClass.CAREFUL.max(RiskClass.CAREFUL));
    }

    @Test
    void unknownAndBlankToolsAreNeverAssumedSafe() {
        assertEquals(RiskClass.CAREFUL, ToolRisk.ofTool("teleport_everything"));
        assertEquals(RiskClass.CAREFUL, ToolRisk.ofTool(""));
        assertEquals(RiskClass.CAREFUL, ToolRisk.ofTool(null));
    }

    @Test
    void onlyDestructiveWorkNeedsTheOwnerAndTheTokenSaysSo() {
        assertFalse(RiskClass.SAFE.needsOwner());
        assertFalse(RiskClass.CAREFUL.needsOwner());
        assertTrue(RiskClass.DESTRUCTIVE.needsOwner());
        assertEquals("risk: safe", RiskClass.SAFE.token());
        assertEquals("risk: careful", RiskClass.CAREFUL.token());
        assertEquals("risk: destructive; ask owner first", RiskClass.DESTRUCTIVE.token());
    }
}
