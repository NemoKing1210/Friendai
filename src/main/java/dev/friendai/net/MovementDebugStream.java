package dev.friendai.net;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.DebugService;
import dev.friendai.debug.MovementDebug;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.pathing.Move;
import dev.friendai.pathing.Path;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.nav.Navigator;
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
        DebugService debug = FriendaiServerState.get(server).brains().debug();
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
        FriendaiServerState.get(player.level().getServer()).brains().debug().setMovementDebug(player.getUUID(), on);
        send(player, on);
    }

    public static void onDisconnect(ServerPlayer player) {
        FriendaiServerState.get(player.level().getServer()).brains().debug().setMovementDebug(player.getUUID(), false);
    }

    private static void send(ServerPlayer player, boolean on) {
        if (!ServerPlayNetworking.canSend(player, MovementDebugS2CPayload.TYPE)) {
            return;
        }
        byte[] data = new byte[0];
        if (on) {
            Optional<CompanionEntity> companion = CompanionManager.get(player.level().getServer()).find(player.getUUID());
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
        String task = c.taskRunner().current().map(Task::describe).orElse("none");
        lines.add("task: " + task);
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
                    route.add(new MovementDebug.Segment(step.from().x(), step.from().y(), step.from().z(),
                            step.to().x(), step.to().y(), step.to().z(), step.move().ordinal()));
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
        return new MovementDebug(c.getId(), stepIndex, route, breaks, places, goalCells, lines);
    }
}
