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
        assertEquals(List.of("go_to_place", "stay"),
                tools(ReflexPlans.plan(need(NeedKind.SURVIVE), Facts.base().home("home", 40).build(), S)
                        .orElseThrow()));
    }

    @Test
    void shelterWithoutHomeHere() {
        var online = ReflexPlans.plan(need(NeedKind.SHELTER), Facts.base().home("home", 40).otherDimension().build(),
                S).orElseThrow();
        assertEquals(List.of("follow_player"), tools(online));
        var offline = ReflexPlans.plan(need(NeedKind.SURVIVE), Facts.base().ownerOffline().build(), S).orElseThrow();
        assertEquals(List.of("light_area", "stay"), tools(offline));
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
