package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.features.crafting.RecipeBook;
import dev.friendai.features.trading.TradeBudget;
import dev.friendai.features.trading.TradeCost;
import dev.friendai.features.trading.TradeItem;
import dev.friendai.features.trading.TradeOffer;
import dev.friendai.features.trading.TraderRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradeObtainTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID TRADER_ID = new UUID(0, 2);
    private static final TradeCost COST = new TradeCost("minecraft:emerald", "{}", 3);
    private static final TradeItem OUTPUT = new TradeItem("minecraft:bread", "{}", 2);
    private static final TradeOffer OFFER = new TradeOffer(
            "offer",
            TradeOffer.fingerprint(List.of(COST), OUTPUT, 0),
            List.of(COST),
            List.of(COST),
            OUTPUT,
            0,
            12,
            1,
            OWNER,
            100);
    private static final TraderRecord TRADER = new TraderRecord(
            TRADER_ID,
            "minecraft:villager",
            "minecraft:overworld",
            1,
            2,
            3,
            "Ada",
            "minecraft:farmer",
            2,
            1,
            100,
            100,
            1,
            TraderRecord.Status.VERIFIED,
            List.of(OFFER));
    private static final RecipeBook RECIPES = new RecipeBook() {
        @Override
        public List<dev.friendai.features.crafting.Recipe> producing(String item) {
            return List.of();
        }

        @Override
        public String stationItem(dev.friendai.features.crafting.Station station) {
            return null;
        }
    };
    private static final SourceBook SOURCES = new SourceBook() {
        @Override
        public List<Source> sourcesOf(String item) {
            return List.of();
        }

        @Override
        public Optional<String> toolItem(ToolNeed need) {
            return Optional.empty();
        }

        @Override
        public ToolNeed toolOf(String item) {
            return ToolNeed.HAND;
        }
    };

    @Test
    void tradingIsDisabledWithoutCapturedContextAndUsesSharedInputsWhenPresent() {
        var inventory = Map.of("minecraft:emerald", 6);
        var ordinary = ObtainPlanner.plan("minecraft:bread", 4, inventory, RECIPES, SOURCES, WorldFacts.empty());
        assertFalse(ordinary.ok());
        var context = new TradingContext(
                UUID.randomUUID(),
                List.of(TRADER),
                new TradeBudget(Map.of("minecraft:emerald", 6), Map.of(), Map.of()),
                OWNER,
                100);
        var authorized =
                ObtainPlanner.plan("minecraft:bread", 4, inventory, RECIPES, SOURCES, WorldFacts.empty(), context);
        assertTrue(authorized.ok(), authorized.toString());
        assertEquals(1, authorized.steps().size());
        assertInstanceOf(ObtainStep.Trade.class, authorized.steps().getFirst());
        assertEquals(2, ((ObtainStep.Trade) authorized.steps().getFirst()).count());
    }
}
