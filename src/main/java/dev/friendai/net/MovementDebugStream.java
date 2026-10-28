package dev.friendai.net;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.DebugService;
import dev.friendai.debug.DebugTarget;
import dev.friendai.debug.MovementDebug;
import dev.friendai.debug.WorkSet;
import dev.friendai.debug.WorkSetBudget;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.navigation.minecraft.nav.Navigator;
import dev.friendai.features.navigation.pathing.Move;
import dev.friendai.features.navigation.pathing.Path;
import dev.friendai.features.navigation.pathing.PathStep;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.task.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Feeds the movement overlay to operators who switched it on: the route, the goal and the movement numbers. */
public final class MovementDebugStream {
    private static final int INTERVAL_TICKS = 4;
    private static int counter;

    private MovementDebugStream() {}

    public static void tick(MinecraftServer server) {
        if (++counter % INTERVAL_TICKS != 0) {
            return;
        }
        DebugService debug = FriendaiServerState.get(server).sessions().debug();
        for (UUID owner : debug.movementViewers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null || !Operators.isOperator(player)) {
                debug.setMovementDebug(owner, false);
                continue;
            }
            send(player, true);
        }
    }

    /** Switches the overlay for the player; switching off sends an empty view so the client clears it. */
    public static void set(ServerPlayer player, boolean on) {
        FriendaiServerState.get(player.level().getServer()).sessions().debug().setMovementDebug(player.getUUID(), on);
        send(player, on);
    }

    public static void onDisconnect(ServerPlayer player) {
        FriendaiServerState.get(player.level().getServer())
                .sessions()
                .debug()
                .setMovementDebug(player.getUUID(), false);
    }

    private static void send(ServerPlayer player, boolean on) {
        if (!ServerPlayNetworking.canSend(player, MovementDebugS2CPayload.TYPE)) {
            return;
        }
        byte[] data = new byte[0];
        if (on) {
            Optional<CompanionEntity> companion =
                    CompanionManager.get(player.level().getServer()).find(player.getUUID());
            if (companion.isPresent()) {
                data = build(companion.get()).encode();
            }
        }
        ServerPlayNetworking.send(player, new MovementDebugS2CPayload(data));
    }

    static MovementDebug build(CompanionEntity c) {
        Navigator nav = c.activeNavigator();
        List<MovementDebug.Segment> route = new ArrayList<>();
        List<MovementDebug.Cell> breaks = new ArrayList<>();
        List<MovementDebug.Cell> places = new ArrayList<>();
        List<MovementDebug.Cell> goalCells = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        int stepIndex = -1;
        String task = c.executionCoordinator().current().map(Task::describe).orElse("none");
        lines.add("task: " + task);
        c.executionCoordinator().currentQueueIntent().ifPresent(intent -> lines.add("intent: " + intent));
        var ownerId = c.ownerUuid();
        if (ownerId != null) {
            var freeTime =
                    FriendaiServerState.get(c.level().getServer()).freeTime().state(ownerId);
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
        List<DebugTarget> targets = MovementDebug.combineTargets(
                c.executionCoordinator().current().map(Task::debugTargets).orElse(List.of()), breaks, places);
        String taskStatus =
                c.executionCoordinator().current().map(Task::debugStatus).orElse("");
        if (!taskStatus.isBlank()) {
            lines.add(
                    taskStatus.length() > MovementDebug.MAX_LINE_CHARS
                            ? taskStatus.substring(0, MovementDebug.MAX_LINE_CHARS)
                            : taskStatus);
        }
        List<WorkSet> workSets = WorkSetBudget.select(
                c.executionCoordinator().current().map(Task::debugWorkSets).orElse(List.of()),
                c.blockPosition().getX(),
                c.blockPosition().getY(),
                c.blockPosition().getZ());
        List<String> upcoming =
                MovementDebug.upcomingSteps(c.executionCoordinator().queue().jobs());
        return new MovementDebug(
                c.getId(), stepIndex, route, breaks, places, goalCells, lines, targets, upcoming, workSets);
    }
}
