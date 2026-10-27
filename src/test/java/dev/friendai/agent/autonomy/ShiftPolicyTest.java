package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.friendai.brain.AgentSettings.Autonomy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShiftPolicyTest {
    private static final AutonomySettings S = AutonomySettings.DEFAULTS;
    private static final long QUIET = Facts.NOW - 120_000;

    private static FreeTime on() {
        return new FreeTime(true, FreeTime.Origin.COMMAND, "", 0, null, false, 1, List.of(), List.of(), Map.of(),
                List.of());
    }

    private static FreeTime withShift(boolean reflex, NeedKind need) {
        return new FreeTime(true, FreeTime.Origin.COMMAND, "", 0,
                new Shift(1, "g", List.of(), "", 5, 0, 0, reflex, need), false, 2, List.of(), List.of(), Map.of(),
                List.of());
    }

    private static ShiftInputs in(FreeTime state, Facts facts) {
        return new ShiftInputs(state, facts.build(), S, Autonomy.ASSISTIVE, false, true, false, false, false, QUIET);
    }

    private static ShiftPolicy.Decision decide(ShiftInputs in) {
        return ShiftPolicy.decide(in, new ShiftBudget());
    }

    @Test
    void offIsNone() {
        assertInstanceOf(ShiftPolicy.None.class, decide(in(FreeTime.OFF, Facts.base())));
    }

    @Test
    void passiveCeilingIsNone() {
        var in = new ShiftInputs(on(), Facts.base().build(), S, Autonomy.PASSIVE, false, true, false, false, false,
                QUIET);
        assertInstanceOf(ShiftPolicy.None.class, decide(in));
    }

    @Test
    void disabledIsNone() {
        var off = new AutonomySettings(false, true, 5, 60, true, 20, 6, 2, 10, true, true, List.of());
        var in = new ShiftInputs(on(), Facts.base().build(), off, Autonomy.ASSISTIVE, false, true, false, false,
                false, QUIET);
        assertInstanceOf(ShiftPolicy.None.class, decide(in));
    }

    @Test
    void staleShiftIsClosed() {
        var in = new ShiftInputs(withShift(false, NeedKind.GEAR), Facts.base().build(), S, Autonomy.ASSISTIVE,
                false, false, false, false, false, QUIET);
        assertInstanceOf(ShiftPolicy.CloseStale.class, decide(in));
    }

    @Test
    void planningShiftIsNotStale() {
        var in = new ShiftInputs(withShift(false, NeedKind.GEAR), Facts.base().build(), S, Autonomy.ASSISTIVE,
                false, false, true, false, false, QUIET);
        assertInstanceOf(ShiftPolicy.None.class, decide(in));
    }

    @Test
    void reflexBeatsDirector() {
        var d = decide(in(on(), Facts.base().freeSlots(1).storage("s", 9).storageFreeSlots(9)));
        assertEquals(NeedKind.UNLOAD, assertInstanceOf(ShiftPolicy.Reflex.class, d).need().kind());
    }

    @Test
    void surviveCancelsRunningDirectorShift() {
        var d = decide(in(withShift(false, NeedKind.GEAR), Facts.base().health(3)));
        assertEquals(NeedKind.SURVIVE, assertInstanceOf(ShiftPolicy.Reflex.class, d).need().kind());
    }

    @Test
    void shelterDoesNotCancelAShelterShift() {
        var d = decide(in(withShift(true, NeedKind.SHELTER), Facts.base().dark().home("home", 40)));
        assertInstanceOf(ShiftPolicy.None.class, d);
    }

    @Test
    void runningShiftWaits() {
        var d = decide(in(withShift(false, NeedKind.GEAR), Facts.base().freeSlots(1).storage("s", 9)
                .storageFreeSlots(9)));
        assertInstanceOf(ShiftPolicy.None.class, d);
    }

    @Test
    void ownerWorkWaits() {
        var in = new ShiftInputs(on(), Facts.base().build(), S, Autonomy.ASSISTIVE, false, true, false, true, false,
                QUIET);
        assertInstanceOf(ShiftPolicy.None.class, decide(in));
    }

    @Test
    void activeOwnerGoalBlocksShifts() {
        var in = new ShiftInputs(on(), Facts.base().build(), S, Autonomy.ASSISTIVE, true, true, false, false, false,
                QUIET);
        assertInstanceOf(ShiftPolicy.None.class, decide(in));
    }

    @Test
    void thinkingOrAwaitingOrCombatWaits() {
        var thinking = new ShiftInputs(on(), Facts.base().build(), S, Autonomy.ASSISTIVE, false, true, false, false,
                true, QUIET);
        assertInstanceOf(ShiftPolicy.None.class, decide(thinking));
        var awaiting = new FreeTime(true, FreeTime.Origin.COMMAND, "", 0, null, true, 1, List.of(), List.of(),
                Map.of(), List.of());
        assertInstanceOf(ShiftPolicy.None.class, decide(in(awaiting, Facts.base())));
        assertInstanceOf(ShiftPolicy.None.class, decide(in(on(), Facts.base().inCombat())));
    }

    @Test
    void quietPeriodWaits() {
        var spoke30 = new ShiftInputs(on(), Facts.base().build(), S, Autonomy.ASSISTIVE, false, true, false, false,
                false, Facts.NOW - 30_000);
        assertInstanceOf(ShiftPolicy.None.class, decide(spoke30));
        var spoke61 = new ShiftInputs(on(), Facts.base().build(), S, Autonomy.ASSISTIVE, false, true, false, false,
                false, Facts.NOW - 61_000);
        assertInstanceOf(ShiftPolicy.Director.class, decide(spoke61));
    }

    @Test
    void anEmptyAgendaIsNone() {
        FreeTime coolingExplore = new FreeTime(true, FreeTime.Origin.COMMAND, "", 0, null, false, 1, List.of(),
                List.of(), Map.of(NeedKind.EXPLORE, Facts.NOW + 60_000), List.of());
        Facts settled = Facts.base().everything().home("h", 2).storage("s", 3).storageFreeSlots(9);
        assertInstanceOf(ShiftPolicy.None.class, decide(in(coolingExplore, settled)));
    }

    @Test
    void offlineWithoutPermissionIsNone() {
        var noOffline = new AutonomySettings(true, true, 5, 60, false, 20, 6, 2, 10, true, true, List.of());
        var in = new ShiftInputs(on(), Facts.base().ownerOffline().freeSlots(1).storage("s", 2).storageFreeSlots(9)
                .build(), noOffline, Autonomy.ASSISTIVE, false, true, false, false, false, QUIET);
        assertInstanceOf(ShiftPolicy.None.class, decide(in));
    }

    @Test
    void offlineUsesTheOfflineLimit() {
        ShiftBudget budget = new ShiftBudget();
        budget.record(Facts.NOW - 10_000);
        budget.record(Facts.NOW - 5_000);
        var in = in(on(), Facts.base().ownerOffline().everything().home("h", 2).storage("s", 3).storageFreeSlots(4));
        assertInstanceOf(ShiftPolicy.WaitBudget.class, ShiftPolicy.decide(in, budget));
        var online = in(on(), Facts.base().everything().home("h", 2).storage("s", 3).storageFreeSlots(4));
        assertInstanceOf(ShiftPolicy.Director.class, ShiftPolicy.decide(online, budget));
    }

    @Test
    void budgetSpentFallsBackToTemplate() {
        ShiftBudget budget = new ShiftBudget();
        for (int i = 0; i < 6; i++) {
            budget.record(Facts.NOW - i);
        }
        var d = ShiftPolicy.decide(in(on(), Facts.base().stock(StockKind.LOGS, 2).pickaxe(
                dev.friendai.crafting.obtain.Tier.WOOD).craftingTable().home("h", 2)), budget);
        assertEquals(NeedKind.STOCK, assertInstanceOf(ShiftPolicy.Fallback.class, d).need().kind());
    }

    @Test
    void budgetSpentWithoutTemplateWaits() {
        ShiftBudget budget = new ShiftBudget();
        for (int i = 0; i < 6; i++) {
            budget.record(Facts.NOW - i);
        }
        var d = ShiftPolicy.decide(in(on(), Facts.base().everything().home("h", 2).storage("s", 3)
                .storageFreeSlots(5)), budget);
        assertInstanceOf(ShiftPolicy.WaitBudget.class, d);
    }

    @Test
    void cancelsRunningShiftOnlyForSafety() {
        assertEquals(true, ShiftPolicy.cancelsRunningShift(NeedKind.SURVIVE));
        assertEquals(true, ShiftPolicy.cancelsRunningShift(NeedKind.SHELTER));
        assertEquals(false, ShiftPolicy.cancelsRunningShift(NeedKind.UNLOAD));
    }
}
