package dev.friendai.client.debug;

import dev.friendai.FriendaiMod;
import dev.friendai.debug.MovementDebug;
import dev.friendai.pathing.Move;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Draws the movement debug view: the route in the world and a block of numbers on the HUD. */
public final class MovementDebugRenderer {
    private static final int GOAL = 0xFFFFD83A;
    private static final int BREAK = 0xFFFF4040;
    private static final int PLACE = 0xFF40FF70;
    private static final int TO_NEXT = 0xFFFFFFFF;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int PANEL = 0xA0000000;
    private static final float LINE_WIDTH = 3f;

    private static boolean failed;

    private MovementDebugRenderer() {}

    public static void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> drawWorld());
        HudElementRegistry.addLast(FriendaiMod.id("movement_debug"), MovementDebugRenderer::drawHud);
    }

    private static int color(int move, boolean done) {
        Move[] all = Move.values();
        Move kind = move >= 0 && move < all.length ? all[move] : Move.WALK;
        int rgb = switch (kind) {
            case WALK, WALK_DIAGONAL -> 0x40E060;
            case ASCEND, JUMP_GAP -> 0x40D0FF;
            case DESCEND -> 0xFFE040;
            case SWIM -> 0x4070FF;
            case CLIMB, DOOR -> 0xFFFFFF;
            case DIG, DIG_ASCEND, DIG_DESCEND -> 0xFF9030;
            default -> 0xE050FF;
        };
        return (done ? 0x50 : 0xFF) << 24 | rgb;
    }

    private static void drawWorld() {
        var view = MovementDebugState.view();
        if (view.isEmpty() || failed) {
            return;
        }
        try {
            draw(view.get());
        } catch (RuntimeException e) {
            // A drawing problem must not break the frame; the overlay stays off until the client restarts.
            failed = true;
            FriendaiMod.LOGGER.warn("Movement debug drawing failed and is disabled", e);
        }
    }

    private static void draw(MovementDebug view) {
        int current = Math.max(0, view.stepIndex());
        for (int i = 0; i < view.route().size(); i++) {
            MovementDebug.Segment s = view.route().get(i);
            int color = color(s.move(), i < current);
            Vec3 from = new Vec3(s.fromX() + 0.5, s.fromY() + 0.1, s.fromZ() + 0.5);
            Vec3 to = new Vec3(s.toX() + 0.5, s.toY() + 0.1, s.toZ() + 0.5);
            if (i == current) {
                Gizmos.arrow(from, to, color, LINE_WIDTH + 1f).setAlwaysOnTop();
            } else {
                Gizmos.line(from, to, color, LINE_WIDTH).setAlwaysOnTop();
            }
        }
        for (MovementDebug.Cell c : view.breaks()) {
            Gizmos.cuboid(new BlockPos(c.x(), c.y(), c.z()), GizmoStyle.stroke(BREAK, 2f)).setAlwaysOnTop();
        }
        for (MovementDebug.Cell c : view.places()) {
            Gizmos.cuboid(new BlockPos(c.x(), c.y(), c.z()), GizmoStyle.stroke(PLACE, 2f)).setAlwaysOnTop();
        }
        for (MovementDebug.Cell c : view.goalCells()) {
            Gizmos.cuboid(new BlockPos(c.x(), c.y(), c.z()), GizmoStyle.stroke(GOAL, 3f)).setAlwaysOnTop();
            Gizmos.billboardText("goal", new Vec3(c.x() + 0.5, c.y() + 1.4, c.z() + 0.5),
                    TextGizmo.Style.forColorAndCentered(GOAL)).setAlwaysOnTop();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && current < view.route().size()) {
            Entity companion = mc.level.getEntity(view.companionId());
            if (companion != null) {
                MovementDebug.Segment s = view.route().get(current);
                Gizmos.line(companion.position().add(0, 0.2, 0),
                        new Vec3(s.toX() + 0.5, s.toY() + 0.1, s.toZ() + 0.5), TO_NEXT, 2f).setAlwaysOnTop();
            }
        }
    }

    private static void drawHud(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        var view = MovementDebugState.view();
        if (view.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        var lines = view.get().lines();
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        int x = 4;
        int y = 4;
        graphics.fill(x - 2, y - 2, x + width + 2, y + lines.size() * (font.lineHeight + 1) + 1, PANEL);
        for (String line : lines) {
            graphics.text(font, line, x, y, TEXT);
            y += font.lineHeight + 1;
        }
    }
}
