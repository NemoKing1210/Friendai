package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReflexPlansTest {
    private static final AutonomySettings S = AutonomySettings.DEFAULTS;

    private static Need need(NeedKind kind) {
        return new Need(kind, 50, Executor.REFLEX, "");
    }

    private static List<String> tools(List<ReflexStep> steps) {
        return steps.stream().map(ReflexStep::tool).toList();
    }

    @Test
    void unloadGoesToStorageThenDeposits() {
        var steps = ReflexPlans.plan(need(NeedKind.UNLOAD), Facts.base().home("home", 9).storage("chests", 20)
                .build(), S).orElseThrow();
        assertEquals(List.of("go_to_place", "deposit_items"), tools(steps));
        assertEquals("chests", steps.get(0).args().get("name").getAsString());
        assertEquals("chests", steps.get(1).args().get("place").getAsString());
    }

    @Test
    void unloadUsesHomeWithoutStorage() {
        var steps = ReflexPlans.plan(need(NeedKind.UNLOAD), Facts.base().home("дом", 9).build(), S).orElseThrow();
        assertEquals("дом", steps.get(1).args().get("place").getAsString());
    }

    @Test
    void shelterGoesHomeAndStays() {
        var steps = ReflexPlans.plan(need(NeedKind.SHELTER), Facts.base().home("home", 40).build(), S).orElseThrow();
        assertEquals(List.of("go_to_place", "stay"), tools(steps));
        assertEquals(List.of("go_to_place", "eat", "stay"),
                tools(ReflexPlans.plan(need(NeedKind.SURVIVE), Facts.base().home("home", 40).build(), S)
                        .orElseThrow()));
    }

    @Test
    void shelterWithoutHomeNeverFollowsTheOwner() {
        var online = ReflexPlans.plan(need(NeedKind.SHELTER), Facts.base().home("home", 40).otherDimension().build(),
                S).orElseThrow();
        assertEquals(List.of("light_area", "stay"), tools(online));
        var survive = ReflexPlans.plan(need(NeedKind.SURVIVE), Facts.base().ownerOffline().build(), S).orElseThrow();
        assertEquals(List.of("light_area", "eat", "stay"), tools(survive));
    }

    @Test
    void hungerEatsCarriedFoodOrGetsSome() {
        assertEquals(List.of("eat"), tools(ReflexPlans.plan(need(NeedKind.HUNGER),
                Facts.base().hunger(5).foodPoints(6).build(), S).orElseThrow()));
        assertEquals(List.of("get_food"), tools(ReflexPlans.plan(need(NeedKind.HUNGER),
                Facts.base().hunger(5).foodPoints(0).build(), S).orElseThrow()));
    }

    @Test
    void sleepGoesHome() {
        var steps = ReflexPlans.plan(need(NeedKind.SLEEP), Facts.base().home("home", 20).dark().bedAtHome().build(),
                S).orElseThrow();
        assertEquals(List.of("go_to_place", "sleep"), tools(steps));
        assertEquals("home", steps.get(0).args().get("name").getAsString());
    }

    @Test
    void farmCreationTillsAndSows() {
        var steps = ReflexPlans.plan(need(NeedKind.FARM_CREATE),
                Facts.base().home("home", 8).build(), S).orElseThrow();
        assertEquals(List.of("go_to_place", "farm"), tools(steps));
        assertEquals(true, steps.get(1).args().get("till").getAsBoolean());
        assertEquals("minecraft:wheat_seeds", steps.get(1).args().get("crop").getAsString());
    }

    @Test
    void collectGoesHome() {
        assertEquals(List.of("go_to_place", "collect_smelted"),
                tools(ReflexPlans.plan(need(NeedKind.COLLECT), Facts.base().home("home", 4).build(), S)
                        .orElseThrow()));
    }

    @Test
    void directorNeedsHaveNoReflex() {
        assertTrue(ReflexPlans.plan(need(NeedKind.GEAR), Facts.base().build(), S).isEmpty());
        assertTrue(ReflexPlans.plan(need(NeedKind.UNLOAD), Facts.base().build(), S).isEmpty(),
                "unloading needs somewhere to go");
    }

    @Test
    void stoneStockFallbackGoesForTheMissingStone() {
        AutonomyFacts facts = Facts.base().pickaxe(dev.friendai.crafting.obtain.Tier.WOOD).craftingTable()
                .stock(StockKind.LOGS, 16).stock(StockKind.COBBLE, 4).build();
        var steps = ReflexPlans.fallback(need(NeedKind.STOCK), facts).orElseThrow();
        assertEquals(List.of("obtain_item", "obtain_item", "obtain_item"), tools(steps));
        assertEquals("minecraft:cobblestone", steps.get(0).args().get("item").getAsString());
        assertEquals(28, steps.get(0).args().get("count").getAsInt());
        assertEquals("minecraft:coal", steps.get(1).args().get("item").getAsString());
        assertEquals("minecraft:torch", steps.get(2).args().get("item").getAsString());
    }

    @Test
    void woodStockFallbackStillFellsATree() {
        var steps = ReflexPlans.fallback(need(NeedKind.STOCK), Facts.base().build()).orElseThrow();
        assertEquals(List.of("fell_tree"), tools(steps));
    }

    @Test
    void fallbackTemplates() {
        assertEquals(List.of("fell_tree"),
                tools(ReflexPlans.fallback(need(NeedKind.STOCK), Facts.base().build()).orElseThrow()));
        var farm = ReflexPlans.fallback(need(NeedKind.FARM),
                Facts.base().farm("wheat farm", "wheat", 20, 3_600_000).build()).orElseThrow();
        assertEquals(List.of("tend_farm"), tools(farm));
        assertEquals("wheat farm", farm.get(0).args().get("place").getAsString());
        assertEquals(List.of("smelt_items"),
                tools(ReflexPlans.fallback(need(NeedKind.PROCESS), Facts.base().build()).orElseThrow()));
        assertTrue(ReflexPlans.fallback(need(NeedKind.EXPLORE), Facts.base().build()).isEmpty());
    }
}
