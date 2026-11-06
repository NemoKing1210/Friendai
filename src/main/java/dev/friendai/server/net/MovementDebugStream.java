package dev.friendai.server.net;

import dev.friendai.FriendaiMod;
import dev.friendai.debug.DebugService;
import dev.friendai.debug.DebugTarget;
import dev.friendai.debug.MovementDebug;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkSetBudget;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.foundation.spatial.Move;
import dev.friendai.foundation.spatial.Path;
import dev.friendai.foundation.spatial.PathStep;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.debug.MovementDebugProtocolViewCodec;
import dev.friendai.protocol.debug.MovementDebugView;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.task.Task;
import dev.friendai.transport.protocol.MovementDebugProtocolAdapter;
import dev.friendai.world.movement.Navigator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Feeds the movement overlay to operators who switched it on: the route, the goal and the movement numbers. */
public final class MovementDebugStream {
    private static final int INTERVAL_TICKS = 4;
    /** A failing view is retried every interval; one line per ten seconds is enough to see it without flooding. */
    private static final int FAILURE_LOG_INTERVAL_TICKS = 200;

    private final Map<UUID, Long> lastFailureLog = new HashMap<>();
    private int counter;

    public MovementDebugStream() {}

    public void tick(ServerRuntime runtime) {
        var server = runtime.server();
        if (++counter % INTERVAL_TICKS != 0) {
            return;
        }
        DebugService debug = runtime.sessions().debug();
        for (UUID owner : debug.movementViewers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) {
                drop(debug, owner);
                continue;
            }
            boolean allowed = Operators.isOperator(player);
            if (!allowed) {
                drop(debug, owner);
            }
            try {
                // A viewer that lost permission still gets the clear marker, so the client switch does not stay on.
                send(runtime, player, allowed, 0);
            } catch (RuntimeException failure) {
                report(owner, failure, server.getTickCount());
            }
        }
    }

    /** Switches the overlay for the player; switching off sends an empty view so the client clears it. */
    public void set(ServerRuntime runtime, ServerPlayer player, boolean on) {
        set(runtime, player, on, 0);
    }

    public void set(ServerRuntime runtime, ServerPlayer player, boolean on, long requestId) {
        runtime.sessions().debug().setMovementDebug(player.getUUID(), on);
        try {
            send(runtime, player, on, requestId);
        } catch (RuntimeException failure) {
            report(player.getUUID(), failure, runtime.server().getTickCount());
        }
    }

    public void onDisconnect(ServerRuntime runtime, ServerPlayer player) {
        runtime.sessions().debug().setMovementDebug(player.getUUID(), false);
        lastFailureLog.remove(player.getUUID());
    }

    private void drop(DebugService debug, UUID owner) {
        debug.setMovementDebug(owner, false);
        lastFailureLog.remove(owner);
    }

    private void send(ServerRuntime runtime, ServerPlayer player, boolean on, long requestId) {
        MovementDebugView view = clearView();
        if (on) {
            Optional<CompanionEntity> companion = runtime.companions().find(player.getUUID());
            if (companion.isPresent()) {
                view = MovementDebugProtocolAdapter.toProtocol(buildSafely(runtime, player, companion.get()));
            }
        }
        byte[] frame;
        try {
            frame = MovementDebugProtocolViewCodec.encode(view, requestId);
        } catch (RuntimeException encodingFailure) {
            // A frame the codec rejects must not take the tick down: report it in-band with a minimal view.
            report(player.getUUID(), encodingFailure, runtime.server().getTickCount());
            frame = MovementDebugProtocolViewCodec.encode(
                    failureView(player.getUUID(), runtime, encodingFailure), requestId);
        }
        if (!runtime.protocolConnections().sendView(player, ProtocolFamily.DEBUG, frame)) {
            // The handshake may still be in flight; keep the viewer and retry on the next interval.
            report(
                    player.getUUID(),
                    new IllegalStateException("debug view was not sent"),
                    runtime.server().getTickCount());
        }
    }

    /**
     * One task's broken debug hook must not hide the route, the movement numbers or the other hooks: each read is
     * guarded and its failure is reported as one line the operator can see.
     */
    MovementDebug buildSafely(ServerRuntime runtime, ServerPlayer player, CompanionEntity companion) {
        try {
            return build(runtime, companion);
        } catch (RuntimeException failure) {
            report(player.getUUID(), failure, runtime.server().getTickCount());
            List<String> lines = new ArrayList<>();
            lines.add("task: unavailable");
            lines.add("debug: view unavailable (" + failure.getClass().getSimpleName() + ")");
            return new MovementDebug(
                    companion.getId(),
                    -1,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    lines,
                    List.of(),
                    List.of(),
                    List.of());
        }
    }

    static MovementDebug build(ServerRuntime runtime, CompanionEntity c) {
        Navigator nav = c.activeNavigator();
        List<MovementDebug.Segment> route = new ArrayList<>();
        List<MovementDebug.Cell> breaks = new ArrayList<>();
        List<MovementDebug.Cell> places = new ArrayList<>();
        List<MovementDebug.Cell> goalCells = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> hookFailures = new ArrayList<>();
        int stepIndex = -1;
        Task task = c.executionCoordinator().current().orElse(null);
        String taskName = task == null ? "none" : guarded(hookFailures, "description", task::describe, "unavailable");
        lines.add("task: " + taskName);
        c.executionCoordinator().currentQueueIntent().ifPresent(intent -> lines.add("intent: " + intent));
        var ownerId = c.ownerUuid();
        if (ownerId != null) {
            var freeTime = runtime.freeTime().state(ownerId);
            if (freeTime.shift() != null && !freeTime.shift().goal().isBlank()) {
                lines.add("free-time shift: " + freeTime.shift().goal());
            } else if (freeTime.awaitingChoice()) {
                lines.add("free-time: choosing activity");
            } else if (freeTime.on() && !freeTime.wish().isBlank()) {
                lines.add("free-time wish: " + freeTime.wish());
            }
        }
        if (nav == null) {
            lines.add("navigator: idle");
        } else {
            stepIndex = nav.stepIndex();
            lines.add("navigator: " + nav.state().name().toLowerCase(Locale.ROOT)
                    + (nav.searching() ? " (searching)" : "") + ", failed plans " + nav.failedPlans());
            lines.add("goal: " + nav.goalText());
            Path plan = nav.plan();
            if (plan != null) {
                int total = plan.steps().size();
                lines.add("route: step " + Math.max(0, stepIndex) + " / " + total
                        + ", cost " + Math.round(plan.cost())
                        + ", breaks " + plan.breaks() + ", places " + plan.places()
                        + (plan.partial() ? ", partial" : ""));
                for (int i = 0; i < total && route.size() < MovementDebug.MAX_SEGMENTS; i++) {
                    PathStep step = plan.steps().get(i);
                    route.add(new MovementDebug.Segment(
                            step.from().x(),
                            step.from().y(),
                            step.from().z(),
                            step.to().x(),
                            step.to().y(),
                            step.to().z(),
                            step.move().ordinal()));
                    if (i >= Math.max(0, stepIndex)) {
                        for (Pos p : step.breaks()) {
                            breaks.add(new MovementDebug.Cell(p.x(), p.y(), p.z()));
                        }
                        for (Pos p : step.places()) {
                            places.add(new MovementDebug.Cell(p.x(), p.y(), p.z()));
                        }
                    }
                }
                int now = Math.min(Math.max(0, stepIndex), total - 1);
                if (total > 0) {
                    Move move = plan.steps().get(now).move();
                    lines.add("current step: " + move.name().toLowerCase(Locale.ROOT));
                }
            }
            if (!nav.lastSearch().isEmpty()) {
                lines.add(nav.lastSearch());
            }
            nav.goalCells().forEach(p -> goalCells.add(new MovementDebug.Cell(p.x(), p.y(), p.z())));
        }
        Vec3 v = c.getDeltaMovement();
        lines.add(String.format(Locale.ROOT, "pos %.2f %.2f %.2f", c.getX(), c.getY(), c.getZ()));
        lines.add(String.format(Locale.ROOT, "vel %.3f %.3f %.3f (%.3f b/t)", v.x, v.y, v.z, Math.hypot(v.x, v.z)));
        lines.add("ground " + c.onGround() + ", water " + c.isInWater() + ", head in water "
                + c.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) + ", pathing " + c.pathing());
        lines.add(String.format(
                Locale.ROOT,
                "yaw %.1f, pitch %.1f, speed %.2f, sprint %b",
                c.getYRot(),
                c.getXRot(),
                c.getSpeed(),
                c.isSprinting()));
        lines.add(String.format(
                Locale.ROOT,
                "facing: %s, want yaw %.1f pitch %.1f, body %.1f, head %.1f, pitch %.1f",
                c.facing().source().name().toLowerCase(Locale.ROOT),
                c.facing().desiredYaw(),
                c.facing().desiredPitch(),
                c.getYRot(),
                c.getYHeadRot(),
                c.getXRot()));
        Supplier<List<DebugTarget>> targetReader = task == null ? null : task::debugTargets;
        Supplier<String> statusReader = task == null ? null : task::debugStatus;
        Supplier<List<WorkSet>> workSetReader = task == null ? null : task::debugWorkSets;
        List<DebugTarget> taskTargets = guarded(hookFailures, "targets", targetReader, List.of());
        List<DebugTarget> targets = MovementDebug.combineTargets(taskTargets, breaks, places);
        String taskStatus = guarded(hookFailures, "status", statusReader, "");
        if (!taskStatus.isBlank()) {
            lines.add(
                    taskStatus.length() > MovementDebug.MAX_LINE_CHARS
                            ? taskStatus.substring(0, MovementDebug.MAX_LINE_CHARS)
                            : taskStatus);
        }
        List<WorkSet> workSets = WorkSetBudget.select(
                guarded(hookFailures, "work sets", workSetReader, List.of()),
                c.blockPosition().getX(),
                c.blockPosition().getY(),
                c.blockPosition().getZ());
        List<String> upcoming =
                MovementDebug.upcomingSteps(c.executionCoordinator().queue().jobs());
        // Keep hook failures near the top: the codec trims the oldest lines last, and these are the newest information.
        lines.addAll(Math.min(1, lines.size()), hookFailures);
        return new MovementDebug(
                c.getId(), stepIndex, route, breaks, places, goalCells, lines, targets, upcoming, workSets);
    }

    private static <T> T guarded(List<String> failures, String part, Supplier<T> read, T fallback) {
        if (read == null) {
            return fallback;
        }
        try {
            T value = read.get();
            return value == null ? fallback : value;
        } catch (RuntimeException failure) {
            failures.add(
                    "debug: " + part + " unavailable (" + failure.getClass().getSimpleName() + ")");
            return fallback;
        }
    }

    private static MovementDebugView clearView() {
        return new MovementDebugView(
                0, -1, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static MovementDebugView failureView(UUID owner, ServerRuntime runtime, RuntimeException failure) {
        int companionId =
                runtime.companions().find(owner).map(CompanionEntity::getId).orElse(0);
        List<String> lines =
                List.of("debug: view unavailable (" + failure.getClass().getSimpleName() + ")");
        return new MovementDebugView(
                companionId, -1, List.of(), List.of(), List.of(), List.of(), lines, List.of(), List.of(), List.of());
    }

    private void report(UUID owner, RuntimeException failure, long tick) {
        Long last = lastFailureLog.get(owner);
        if (last != null && tick - last < FAILURE_LOG_INTERVAL_TICKS) {
            return;
        }
        lastFailureLog.put(owner, tick);
        FriendaiMod.LOGGER.warn("Movement debug update failed for {}", owner, failure);
    }
}
