package dev.friendai.agent.autonomy;

import dev.friendai.brain.AgentSettings.Autonomy;
import java.util.List;
import java.util.Set;

/** Decides, once a second, whether free time should start a reflex, ask the director, or wait. */
public final class ShiftPolicy {
    /** Director needs that have a template, for when the budget is spent; the driver's watchdog uses the same set. */
    public static final Set<NeedKind> FALLBACKS = Set.of(NeedKind.STOCK, NeedKind.GEAR, NeedKind.FARM,
            NeedKind.PROCESS, NeedKind.FOOD, NeedKind.LIGHT, NeedKind.BUILD, NeedKind.STORAGE);

    /** Order a spent budget tries templates in: a base first, then ladder progress, then maintenance. */
    public static final List<NeedKind> FALLBACK_ORDER = List.of(NeedKind.BUILD, NeedKind.STORAGE, NeedKind.STOCK,
            NeedKind.GEAR, NeedKind.PROCESS, NeedKind.FOOD, NeedKind.LIGHT, NeedKind.FARM);

    /** Base of the doubling wait after a director turn chose nothing. */
    static final int BACKOFF_BASE_SEC = 60;

    private ShiftPolicy() {}

    public sealed interface Decision permits None, CloseStale, Expired, Reflex, Director, Fallback, WaitBudget {}

    /** Nothing to do now. */
    public record None() implements Decision {}

    /** The saved shift's plan is gone (restart, death, cleared queue): close it as cancelled. */
    public record CloseStale() implements Decision {}

    /** The shift outlived its own deadline: cancel the plan and record "ran out of time". */
    public record Expired() implements Decision {}

    /** Queue the reflex template for this need, cancelling a running shift first if there is one. */
    public record Reflex(Need need) implements Decision {}

    /** Ask the director to choose an activity. */
    public record Director() implements Decision {}

    /** The budget is spent: queue this need's template instead of asking the model. */
    public record Fallback(Need need) implements Decision {}

    /** The budget is spent and nothing has a template. */
    public record WaitBudget() implements Decision {}

    public static Decision decide(ShiftInputs in, ShiftBudget budget) {
        FreeTime state = in.state();
        AutonomyFacts f = in.facts();
        AutonomySettings s = in.settings();
        if (!state.on() || !s.enabled() || in.ceiling() == Autonomy.PASSIVE) {
            return new None();
        }
        Shift shift = state.shift();
        if (shift != null && f.nowMillis() > shift.deadlineMillis()) {
            return new Expired();
        }
        if (shift != null && !in.shiftPlanExists() && !in.planningInFlight()) {
            return new CloseStale();
        }
        if (f.inCombat() || in.brainThinking() || state.awaitingChoice() || in.planningInFlight()) {
            return new None();
        }
        if (!f.ownerOnline() && !s.whileOffline()) {
            return new None();
        }
        List<Need> needs = NeedsAssessor.assess(f, state, s);
        if (shift != null) {
            if (!cancelsRunningShift(shift.need())) {
                for (Need need : needs) {
                    if (need.executor() == Executor.REFLEX && cancelsRunningShift(need.kind())) {
                        return new Reflex(need);
                    }
                }
            }
            return new None();
        }
        if (in.ownerWorkQueued() || in.ownerGoalActive() || in.movementOrder()) {
            return new None();
        }
        for (Need need : needs) {
            if (need.executor() == Executor.REFLEX) {
                return new Reflex(need);
            }
        }
        if (needs.isEmpty()) {
            // every need is cooling down: a director turn with an empty agenda only spends a request
            return new None();
        }
        if (f.nowMillis() - in.lastOwnerActivityMillis() < s.resumeAfterSec() * 1000L) {
            return new None();
        }
        int perHour = f.ownerOnline() ? s.maxShiftsPerHour() : s.offlineShiftsPerHour();
        if (budget.allows(f.nowMillis(), perHour) && budget.backoffAllows(f.nowMillis(), BACKOFF_BASE_SEC)) {
            return new Director();
        }
        for (NeedKind kind : FALLBACK_ORDER) {
            for (Need need : needs) {
                if (need.kind() == kind) {
                    return new Fallback(need);
                }
            }
        }
        return new WaitBudget();
    }

    /** Safety needs interrupt whatever free time is doing; nothing else does. */
    public static boolean cancelsRunningShift(NeedKind need) {
        return need == NeedKind.SURVIVE || need == NeedKind.SHELTER || need == NeedKind.HUNGER;
    }
}
