package dev.friendai.agent.autonomy;

import dev.friendai.agent.turn.AgentSettings.Autonomy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs an hour of free-time decisions in compressed time against the real pure decision stack.
 *
 * <p>This is the audit harness from TODO item 18.1. It is not a game: the world is a {@link World} script
 * that evolves when a shift completes, and no Minecraft class is involved. What it does prove is that
 * {@link NeedsAssessor}, {@link ShiftPolicy}, {@link ReflexPlans}, {@link ShiftBudget} and
 * {@link ShiftOutcome} keep an hour moving: needs are offered, shifts start and finish, the budget is
 * respected and a free time that stands still becomes a counted stall.
 */
final class FreeTimeAuditHarness {
    /** One second of simulated time. */
    private static final long STEP_MILLIS = 1_000L;
    /** The audit window, one hour. */
    static final long HOUR_SECONDS = 3_600L;
    /** A free time that has nothing running for this long is a stall, like {@code IdleWatchdog} says. */
    static final long STALL_WINDOW_SECONDS = IdleWatchdog.DEFAULT_WINDOW_SEC;
    /** A made-up token cost per director turn; enough to compare reports, not a provider measurement. */
    static final int TOKENS_PER_DIRECTOR_TURN = 900;

    /** The scripted world: facts at a moment, what can be planned, and what a finished shift changes. */
    interface World {
        AutonomyFacts facts(long nowMillis);

        /** Whether a plan can be built for this need at all (a missing capability is a failed shift). */
        boolean canPlan(NeedKind kind);

        /** How long the plan takes. */
        int planSeconds(NeedKind kind);

        /** A completed (or failed) shift changed the world. */
        void completed(NeedKind kind, boolean success);

        /** Blocks the plan placed, for the report. */
        int blocksPlaced(NeedKind kind);
    }

    /** One row of the audit table. */
    record Tally(int offered, int chosen, int completed, int failed, int seconds, int blocks) {
        Tally plusOffered() {
            return new Tally(offered + 1, chosen, completed, failed, seconds, blocks);
        }

        Tally plusChosen() {
            return new Tally(offered, chosen + 1, completed, failed, seconds, blocks);
        }

        Tally plusEnd(boolean success, int durationSeconds, int placed) {
            return new Tally(
                    offered,
                    chosen,
                    completed + (success ? 1 : 0),
                    failed + (success ? 0 : 1),
                    seconds + durationSeconds,
                    blocks + placed);
        }

        String row(NeedKind kind) {
            return "| " + kind + " | " + offered + " | " + chosen + " | " + completed + " | " + failed + " | " + seconds
                    + " | " + blocks + " |";
        }
    }

    /** The report, printable as a markdown table. */
    record Report(
            long seconds,
            int shifts,
            int directorTurns,
            int stalls,
            String finalStage,
            Map<NeedKind, Tally> table,
            List<String> events) {
        String markdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# Free-time audit: ").append(seconds).append(" simulated seconds\n\n");
            sb.append("- shifts: ").append(shifts).append('\n');
            sb.append("- director turns: ")
                    .append(directorTurns)
                    .append(" (about ")
                    .append(directorTurns * TOKENS_PER_DIRECTOR_TURN)
                    .append(" tokens)\n");
            sb.append("- stalls: ")
                    .append(stalls)
                    .append(" (windows of ")
                    .append(STALL_WINDOW_SECONDS)
                    .append(" s with nothing running)\n");
            sb.append("- final ladder stage: ").append(finalStage).append("\n\n");
            sb.append("| Need | offered | chosen | completed | failed | seconds | blocks |\n");
            sb.append("|---|---|---|---|---|---|---|\n");
            table.forEach((kind, tally) -> sb.append(tally.row(kind)).append('\n'));
            if (!events.isEmpty()) {
                sb.append("\n## Events\n\n");
                events.forEach(event -> sb.append("- ").append(event).append('\n'));
            }
            return sb.toString();
        }
    }

    private final World world;
    private final AutonomySettings settings;
    private final ShiftBudget budget = new ShiftBudget();
    private final Map<NeedKind, Tally> table = new EnumMap<>(NeedKind.class);
    private final List<String> events = new ArrayList<>();
    private long nextShiftId = 1;
    private long startMillis;
    private long directorTurns;
    private long stallSeconds;
    private long quietSince;
    private int shifts;
    private FreeTime state;
    private Shift shift;
    private long planEndsAt;
    private boolean shiftSuccess;
    private String shiftSummary = "";

    FreeTimeAuditHarness(World world, AutonomySettings settings) {
        this.world = world;
        this.settings = settings;
        for (NeedKind kind : NeedKind.values()) {
            table.put(kind, new Tally(0, 0, 0, 0, 0, 0));
        }
    }

    Report run(long startMillis, long seconds) {
        this.startMillis = startMillis;
        state = new FreeTime(
                true, FreeTime.Origin.IDLE, "", startMillis, null, false, 1, List.of(), List.of(), Map.of(), List.of());
        for (long i = 0; i < seconds; i++) {
            long now = startMillis + i * STEP_MILLIS;
            step(now);
        }
        if (shift != null) {
            endShift(shiftSuccess, shiftSummary, startMillis + seconds * STEP_MILLIS);
        }
        return new Report(
                seconds,
                shifts,
                (int) directorTurns,
                (int) (stallSeconds / STALL_WINDOW_SECONDS),
                ProgressionLadder.evaluate(world.facts(startMillis + seconds * STEP_MILLIS))
                        .stage(),
                table,
                events);
    }

    private void step(long now) {
        if (shift != null && now >= planEndsAt) {
            endShift(shiftSuccess, shiftSummary, now);
        }
        AutonomyFacts facts = world.facts(now);
        List<Need> needs = NeedsAssessor.assess(facts, state, settings);
        needs.forEach(need -> table.compute(need.kind(), (kind, tally) -> tally.plusOffered()));
        ShiftInputs in = new ShiftInputs(
                state,
                facts,
                settings,
                Autonomy.ASSISTIVE,
                false,
                shift != null,
                false,
                false,
                false,
                now - 10 * 60_000L,
                false);
        ShiftPolicy.Decision decision = ShiftPolicy.decide(in, budget);
        apply(decision, needs, facts, now);
        if (shift == null) {
            quietSince = quietSince == 0 ? now : quietSince;
            if (now - quietSince >= STALL_WINDOW_SECONDS * 1_000L) {
                stallSeconds += (now - quietSince) / 1_000L;
                events.add("stall at " + seconds(now) + " s: "
                        + (needs.isEmpty()
                                ? "empty agenda"
                                : "no decision (" + needs.get(0).kind() + ")"));
                quietSince = now;
            }
        } else {
            quietSince = 0;
        }
    }

    private long seconds(long now) {
        return (now - startMillis) / 1_000L;
    }

    private void apply(ShiftPolicy.Decision decision, List<Need> needs, AutonomyFacts facts, long now) {
        if (decision instanceof ShiftPolicy.Reflex reflex) {
            startFromTemplate(reflex.need(), facts, now, true);
        } else if (decision instanceof ShiftPolicy.Fallback fallback) {
            startFromTemplate(fallback.need(), facts, now, false);
        } else if (decision instanceof ShiftPolicy.Director) {
            directorTurns++;
            budget.record(now);
            Need chosen = needs.stream()
                    .filter(need -> need.executor() == Executor.DIRECTOR)
                    .findFirst()
                    .orElse(needs.isEmpty() ? null : needs.get(0));
            if (chosen == null) {
                budget.failed();
                return;
            }
            startShift(chosen.kind(), chosen.text(), now, world.canPlan(chosen.kind()));
        } else if (decision instanceof ShiftPolicy.CloseStale) {
            if (shift != null) {
                endShift(false, "plan is gone", now);
            }
        }
    }

    /** A reflex or fallback starts when its template exists; the harness asks the world to plan it. */
    private void startFromTemplate(Need need, AutonomyFacts facts, long now, boolean reflex) {
        java.util.Optional<List<ReflexStep>> steps =
                reflex ? ReflexPlans.plan(need, facts, settings) : ReflexPlans.fallback(need, facts);
        if (steps.isEmpty()) {
            budget.failed();
            events.add("no template for " + need.kind() + " at " + seconds(now) + " s");
            return;
        }
        startShift(need.kind(), need.kind() + " template", now, true);
    }

    private void startShift(NeedKind need, String goal, long now, boolean canPlan) {
        String stage = ProgressionLadder.evaluate(world.facts(now)).stage();
        shift = new Shift(
                nextShiftId++,
                goal,
                List.of(),
                "",
                0,
                now,
                now + settings.shiftMinutes() * 60_000L,
                need == NeedKind.SURVIVE
                        || need == NeedKind.SHELTER
                        || need == NeedKind.HUNGER
                        || need == NeedKind.SLEEP
                        || need == NeedKind.UNLOAD
                        || need == NeedKind.COLLECT
                        || need == NeedKind.FARM_CREATE,
                need);
        planEndsAt = now + world.planSeconds(need) * 1_000L;
        shiftSuccess = canPlan;
        shiftSummary = canPlan ? "done" : "no plan: capability missing";
        shifts++;
        table.compute(need, (kind, tally) -> tally.plusChosen());
        events.add("shift " + shift.id() + " " + need + " at " + seconds(now) + " s, stage " + stage);
        state = new FreeTime(
                state.on(),
                state.origin(),
                state.wish(),
                state.sinceMillis(),
                shift,
                state.awaitingChoice(),
                state.nextShiftId(),
                state.history(),
                state.lessons(),
                state.cooldownUntil(),
                state.away());
    }

    private void endShift(boolean success, String summary, long now) {
        if (shift == null) {
            return;
        }
        String before =
                ProgressionLadder.evaluate(world.facts(shift.startedMillis())).stage();
        world.completed(shift.need(), success);
        String after = ProgressionLadder.evaluate(world.facts(now)).stage();
        ShiftOutcome.Result result = ShiftOutcome.of(shift, success, summary, before, after, "", now, settings);
        int placed = success ? world.blocksPlaced(shift.need()) : 0;
        table.compute(shift.need(), (kind, tally) -> tally.plusEnd(success, world.planSeconds(kind), placed));
        if (success) {
            budget.succeeded();
        } else if (!shift.reflex() && result.cooldownUntil().isPresent()) {
            budget.failed();
        }
        if (result.cooldownUntil().isPresent()) {
            state = new FreeTime(
                    state.on(),
                    state.origin(),
                    state.wish(),
                    state.sinceMillis(),
                    null,
                    state.awaitingChoice(),
                    state.nextShiftId(),
                    state.history(),
                    state.lessons(),
                    withCooldown(shift.need(), result.cooldownUntil().get()),
                    state.away());
        } else {
            state = new FreeTime(
                    state.on(),
                    state.origin(),
                    state.wish(),
                    state.sinceMillis(),
                    null,
                    state.awaitingChoice(),
                    state.nextShiftId(),
                    state.history(),
                    state.lessons(),
                    state.cooldownUntil(),
                    state.away());
        }
        events.add("end " + shift.id() + " " + shift.need() + ": " + (success ? "ok" : "failed")
                + (summary.isBlank() ? "" : " (" + summary + ")"));
        shift = null;
        planEndsAt = 0;
    }

    private Map<NeedKind, Long> withCooldown(NeedKind kind, long until) {
        Map<NeedKind, Long> copy = new EnumMap<>(NeedKind.class);
        copy.putAll(state.cooldownUntil());
        copy.put(kind, until);
        return copy;
    }
}
