package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.item.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressionLadderTest {
    @Test
    void emptyBagIsWoodStage() {
        LadderView view = ProgressionLadder.evaluate(Facts.base().build());
        assertEquals("WOOD", view.stage());
        assertTrue(view.missingGear().contains("wooden pickaxe"));
        assertTrue(view.missingGear().contains("crafting table"));
        assertTrue(view.missingStock().contains("logs 0/16"));
    }

    @Test
    void stoneStageListsMissing() {
        LadderView view = ProgressionLadder.evaluate(Facts.base()
                .pickaxe(Tier.WOOD)
                .craftingTable()
                .stock(StockKind.LOGS, 20)
                .stock(StockKind.COAL, 4)
                .build());
        assertEquals("STONE", view.stage());
        assertTrue(view.missingGear().contains("stone pickaxe"));
        assertTrue(view.missingGear().contains("furnace"));
        assertTrue(view.missingStock().contains("coal 4/16"));
        assertTrue(view.line().startsWith("Development: stage STONE; missing "));
        assertTrue(view.line().contains("coal 4/16"));
    }

    @Test
    void missingStockIsCountedPerKind() {
        var missing = ProgressionLadder.missingStock(Facts.base()
                .pickaxe(Tier.WOOD)
                .craftingTable()
                .stock(StockKind.LOGS, 16)
                .stock(StockKind.COBBLE, 4)
                .build());
        assertEquals(28, missing.get(StockKind.COBBLE));
        assertEquals(16, missing.get(StockKind.COAL));
        assertEquals(32, missing.get(StockKind.TORCHES));
        assertEquals(3, missing.size());
    }

    @Test
    void betterToolsCountForLowerStages() {
        LadderView view = ProgressionLadder.evaluate(Facts.base()
                .pickaxe(Tier.IRON)
                .craftingTable()
                .stock(StockKind.LOGS, 16)
                .build());
        assertEquals("STONE", view.stage());
        assertTrue(view.missingGear().stream().noneMatch(m -> m.contains("pickaxe")));
    }

    @Test
    void ironStageNeedsArmorAndShield() {
        LadderView view = ProgressionLadder.evaluate(Facts.base()
                .pickaxe(Tier.STONE)
                .axe(Tier.STONE)
                .sword(Tier.STONE)
                .craftingTable()
                .furnace()
                .stock(StockKind.LOGS, 16)
                .stock(StockKind.COBBLE, 32)
                .stock(StockKind.COAL, 16)
                .stock(StockKind.TORCHES, 32)
                .armor(Tier.IRON, 1)
                .build());
        assertEquals("IRON", view.stage());
        assertTrue(view.missingGear().contains("iron pickaxe"));
        assertTrue(view.missingGear().contains("iron armor 1/2 pieces"));
        assertTrue(view.missingGear().contains("shield"));
        assertTrue(view.missingGear().contains("bucket"));
    }

    @Test
    void fullDiamondIsComplete() {
        LadderView view = ProgressionLadder.evaluate(Facts.base().everything().build());
        assertEquals("DIAMOND", view.stage());
        assertTrue(view.complete());
        assertEquals("Development: stage DIAMOND complete", view.line());
    }

    @Test
    void stagesAreData() {
        assertEquals(
                List.of("WOOD", "STONE", "IRON", "DIAMOND"),
                ProgressionLadder.STAGES.stream()
                        .map(ProgressionLadder.Stage::name)
                        .toList());
    }
}
