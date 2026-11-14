package dev.friendai.server.agent;

import dev.friendai.FriendaiMod;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.agent.turn.AgentSettings;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.infrastructure.config.ServerConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/**
 * Once a second: ticks routines and free time, then nudges idle companions toward an unfinished goal as the
 * autonomy setting allows. All work stays on the server thread.
 */
final class AgentIdleTick {
    private final MinecraftServer server;
    private final Supplier<ServerConfig> config;
    private final RoutineDriver routines;
    private final FreeTimeDriver freeTime;
    private final GoalService goals;
    /** Creates the owner's brain when free time needs its task listener, or empty without a companion. */
    private final Function<UUID, Optional<CompanionSession>> entryFor;
    /** Reads a session without creating one. */
    private final Function<UUID, CompanionSession> lookup;

    private final Supplier<List<CompanionSession>> sessions;
    private final BiConsumer<UUID, BrainEvent> poster;

    private long lastAgentCheck;

    AgentIdleTick(
            MinecraftServer server,
            Supplier<ServerConfig> config,
            RoutineDriver routines,
            FreeTimeDriver freeTime,
            GoalService goals,
            Function<UUID, Optional<CompanionSession>> entryFor,
            Function<UUID, CompanionSession> lookup,
            Supplier<List<CompanionSession>> sessions,
            BiConsumer<UUID, BrainEvent> poster) {
        this.server = server;
        this.config = config;
        this.routines = routines;
        this.freeTime = freeTime;
        this.goals = goals;
        this.entryFor = entryFor;
        this.lookup = lookup;
        this.sessions = sessions;
        this.poster = poster;
    }

    void tick() {
        long now = server.getTickCount() * 50L;
        if (now - lastAgentCheck < 1_000L) {
            return;
        }
        lastAgentCheck = now;
        // Routines are tracked even when autonomy is passive: a paused occurrence must be reported once, and a
        // running owner-scheduled plan must reach its safe boundary.
        routines.tick();
        AgentSettings.Autonomy autonomy = AgentSettings.Autonomy.parse(config.get().agent.autonomy);
        if (autonomy == AgentSettings.Autonomy.PASSIVE) {
            return;
        }
        long wall = FreeTimeDriver.now();
        for (CompanionEntity companion : CompanionManager.get(server).all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null) {
                continue;
            }
            var primary = goals.board(owner).primary();
            // Free time needs the task listener a brain installs, even before the owner has said anything.
            CompanionSession brain =
                    freeTime.state(owner).on() ? entryFor.apply(owner).orElse(null) : lookup.apply(owner);
            try {
                freeTime.tick(
                        owner,
                        companion,
                        brain != null && brain.hasPendingWork(),
                        primary.isPresent() && primary.get().status() == dev.friendai.agent.goal.GoalStatus.ACTIVE,
                        wall);
            } catch (RuntimeException e) {
                FriendaiMod.LOGGER.warn("Free time failed for {}", owner, e);
            }
        }
        for (CompanionSession session : sessions.get()) {
            UUID owner = session.owner();
            if (session.brain() == null) continue;
            CompanionEntity companion = CompanionManager.get(server).find(owner).orElse(null);
            if (companion == null) {
                continue;
            }
            var board = goals.board(owner);
            var primary = board.primary();
            boolean active = primary.isPresent() && primary.get().status() == dev.friendai.agent.goal.GoalStatus.ACTIVE;
            long last = session.lastGoalTick();
            if (last == 0L) {
                last = now;
                session.lastGoalTick(now);
            }
            boolean idle = queueIdleOrBlocked(companion);
            if (!idle) {
                session.resetNudgeStreak(); // a new idle spell starts with a fresh interval
            }
            int streak = session.nudgeStreak();
            if (dev.friendai.agent.tick.NudgeBackoff.exhausted(streak)) {
                continue;
            }
            var facts = new dev.friendai.agent.tick.TickFacts(
                    active,
                    idle,
                    companion.owner().isPresent(),
                    companion.executionCoordinator().paused()
                            || companion.executionCoordinator().suspended()
                            // After a stop order the goal waits for new work instead of restarting it itself.
                            || companion.executionCoordinator().stoppedByOrder()
                            || companion.isSleeping()
                            || companion.getTarget() != null,
                    session.isThinking(),
                    now,
                    last,
                    autonomy,
                    dev.friendai.agent.tick.NudgeBackoff.intervalSec(config.get().agent.goalTickSec, streak));
            if (dev.friendai.agent.tick.GoalTickPolicy.shouldAskWhatNext(facts)) {
                session.lastGoalTick(now);
                session.incrementNudgeStreak();
                poster.accept(owner, new BrainEvent.IdleNudge());
            } else if (dev.friendai.agent.tick.GoalTickPolicy.shouldTick(facts)) {
                session.lastGoalTick(now);
                session.incrementNudgeStreak();
                var guard = new dev.friendai.task.queue.CompanionGuardFacts(companion);
                guard.refresh();
                poster.accept(
                        owner,
                        new BrainEvent.GoalTick(
                                primary.get().text(),
                                board.unmet(primary.get().id(), guard).stream()
                                        .map(dev.friendai.agent.goal.Criterion::text)
                                        .toList()));
            }
        }
    }

    /** Nothing runs and no plan moves by itself; a plan waiting for a decision counts as blocked. */
    private static boolean queueIdleOrBlocked(CompanionEntity companion) {
        var runner = companion.executionCoordinator();
        var queue = runner.queue();
        if (queue.jobCount() == 0) {
            return !runner.hasWork();
        }
        if (queue.active().isPresent()) {
            return false;
        }
        boolean directWork = queue.jobs().stream()
                .anyMatch(job -> job.planId() == 0 && !dev.friendai.execution.queue.JobQueue.terminal(job));
        boolean planMoving = queue.plans().stream()
                .anyMatch(plan -> queue.planStatus(plan.id()) == dev.friendai.execution.queue.PlanStatus.ACTIVE);
        return !directWork && !planMoving;
    }
}
