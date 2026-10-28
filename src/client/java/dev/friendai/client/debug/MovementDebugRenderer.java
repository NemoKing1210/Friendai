package dev.friendai.client.debug;

import dev.friendai.FriendaiMod;
import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugDetail;
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
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Draws the companion's route and planned block interactions in the world and on the HUD. */
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
            disableAfterDrawingFailure(e);
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
            Gizmos.cuboid(new BlockPos(c.x(), c.y(), c.z()), GizmoStyle.stroke(BREAK, 2f))
                    .setAlwaysOnTop();
        }
        for (MovementDebug.Cell c : view.places()) {
            Gizmos.cuboid(new BlockPos(c.x(), c.y(), c.z()), GizmoStyle.stroke(PLACE, 2f))
                    .setAlwaysOnTop();
        }
        for (MovementDebug.Cell c : view.goalCells()) {
            Gizmos.cuboid(new BlockPos(c.x(), c.y(), c.z()), GizmoStyle.stroke(GOAL, 3f))
                    .setAlwaysOnTop();
            Gizmos.billboardText(
                            Component.translatable("friendai.debug.goal").getString(),
                            new Vec3(c.x() + 0.5, c.y() + 1.4, c.z() + 0.5),
                            TextGizmo.Style.forColorAndCentered(GOAL))
                    .setAlwaysOnTop();
        }
        if (view.workSets().isEmpty()) {
            for (var target : view.targets()) {
                int color = actionColor(target.action());
                Gizmos.cuboid(new BlockPos(target.x(), target.y(), target.z()), GizmoStyle.stroke(color, 3f))
                        .setAlwaysOnTop();
                String action = actionLabel(target.action());
                String detail = detailLabel(target.detail());
                String label = detail.isBlank() ? action : action + " · " + detail;
                Gizmos.billboardText(clipLabel(label), new Vec3(target.x() + 0.5, target.y() + 1.35,
                        target.z() + 0.5), TextGizmo.Style.forColorAndCentered(color)).setAlwaysOnTop();
            }
        }
        for (var set : view.workSets()) {
            var anchor = set.cells().stream().filter(cell -> cell.status() == dev.friendai.debug.WorkStatus.NEXT)
                    .findFirst().orElse(set.cells().isEmpty() ? null : set.cells().getFirst());
            if (anchor != null && visibleCell(anchor.x(), anchor.y(), anchor.z())) {
                Gizmos.billboardText(clipLabel(set.label()), new Vec3(anchor.x() + 0.5, anchor.y() + 1.65,
                        anchor.z() + 0.5), TextGizmo.Style.forColorAndCentered(0xFFE8D7FF)).setAlwaysOnTop();
            }
            for (var cell : set.cells()) {
                if (!visibleCell(cell.x(), cell.y(), cell.z())) continue;
                int color = workColor(cell.status());
                Gizmos.cuboid(new BlockPos(cell.x(), cell.y(), cell.z()), GizmoStyle.stroke(color, 2.5f))
                        .setAlwaysOnTop();
                if (cell.status() == dev.friendai.debug.WorkStatus.NEXT) {
                    Gizmos.billboardText(clipLabel(detailLabel(cell.detail())),
                            new Vec3(cell.x() + 0.5, cell.y() + 1.35, cell.z() + 0.5),
                            TextGizmo.Style.forColorAndCentered(color)).setAlwaysOnTop();
                }
            }

        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && current < view.route().size()) {
            Entity companion = mc.level.getEntity(view.companionId());
            if (companion != null) {
                MovementDebug.Segment s = view.route().get(current);
                Gizmos.line(
                                companion.position().add(0, 0.2, 0),
                                new Vec3(s.toX() + 0.5, s.toY() + 0.1, s.toZ() + 0.5),
                                TO_NEXT,
                                2f)
                        .setAlwaysOnTop();
            }
        }
    }

    private static void drawHud(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        var view = MovementDebugState.view();
        if (view.isEmpty() || failed) {
            return;
        }
        try {
            drawHudPanel(graphics, view.get());
        } catch (RuntimeException e) {
            disableAfterDrawingFailure(e);
        }
    }

    private static void drawHudPanel(GuiGraphicsExtractor graphics, MovementDebug view) {
        Font font = Minecraft.getInstance().font;
        var lines = hudLines(view);
        int width = 0;
        for (String line : lines) width = Math.max(width, font.width(line));
        int x = 4;
        int y = 4;
        graphics.fill(x - 4, y - 4, x + width + 6, y + lines.size() * (font.lineHeight + 2) + 4, PANEL);
        graphics.fill(x - 4, y - 4, x + 2, y + lines.size() * (font.lineHeight + 2) + 4, 0xFF9B7BFF);
        int index = 0;
        for (String line : lines) {
            graphics.text(font, line, x + 2, y, index++ == 0 ? 0xFFD7C8FF : TEXT);
            y += font.lineHeight + 2;
        }
    }

    private static java.util.List<String> hudLines(MovementDebug view) {
        java.util.List<String> lines = new java.util.ArrayList<>(5);
        lines.add(Component.translatable("friendai.debug.hud.title").getString());
        String task = lineValue(view.lines(), "task: ");
        if (!task.isBlank() && !"none".equals(task)) {
            lines.add(Component.translatable("friendai.debug.hud.task", task).getString());
        }
        String intent = lineValue(view.lines(), "intent: ");
        if (intent.isBlank()) intent = lineValue(view.lines(), "free-time shift: ");
        if (intent.isBlank()) intent = lineValue(view.lines(), "free-time wish: ");
        if (!intent.isBlank()) lines.add(Component.translatable("friendai.debug.hud.intent", intent).getString());
        String navigation = lineValue(view.lines(), "navigator: ");
        if (!navigation.isBlank()) {
            lines.add(Component.translatable("friendai.debug.hud.navigation", navigationLabel(navigation))
                    .getString());
        }
        String current = lineValue(view.lines(), "current step: ");
        if (!current.isBlank()) {
            lines.add(Component.translatable("friendai.debug.hud.path", current).getString());
        }
        if (!view.targets().isEmpty() && view.workSets().isEmpty()) {
            var target = view.targets().getFirst();
            String action = actionLabel(target.action());
            String detail = target.detail().isBlank() ? "" : " · " + detailLabel(target.detail());
            lines.add(Component.translatable("friendai.debug.hud.action", action + detail)
                    .getString());
        }
        if (!view.upcomingSteps().isEmpty()) {
            String steps = view.upcomingSteps().stream()
                    .map(MovementDebugRenderer::stepLabel)
                    .collect(java.util.stream.Collectors.joining("  ›  "));
            lines.add(Component.translatable("friendai.debug.hud.next", steps).getString());
        }
        for (var set : view.workSets()) {
            String suffix = set.truncated()
                    ? " · " + Component.translatable("friendai.debug.work.truncated").getString() : "";
            lines.add(Component.translatable("friendai.debug.hud.work_set", set.label(), set.done(), set.next(),
                    set.pending(), set.skipped(), suffix).getString());
        }
        return lines.stream().map(MovementDebugRenderer::clipHudLine).toList();
    }

    private static int workColor(dev.friendai.debug.WorkStatus status) {
        return switch (status) {
            case DONE -> 0x8055C878;
            case NEXT -> 0xFFFFD34D;
            case PENDING -> 0xFF55B9FF;
            case SKIPPED -> 0xFF9A9A9A;
        };
    }

    private static boolean visibleCell(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameRenderer == null) return false;
        Vec3 center = new Vec3(x + 0.5, y + 0.5, z + 0.5);
        Vec3 offset = center.subtract(mc.player.getEyePosition());
        return offset.lengthSqr() <= 48 * 48 && mc.player.getLookAngle().dot(offset) > 0.0;
    }

    private static String lineValue(java.util.List<String> lines, String prefix) {
        return lines.stream()
                .filter(line -> line.startsWith(prefix))
                .findFirst()
                .map(line -> line.substring(prefix.length()))
                .orElse("");
    }

    private static String navigationLabel(String navigation) {
        String state = navigation.split("[ (,]", 2)[0];
        return Component.translatable(
                        switch (state) {
                            case "searching" -> "friendai.debug.navigation.searching";
                            case "moving" -> "friendai.debug.navigation.moving";
                            case "gathering" -> "friendai.debug.navigation.gathering";
                            case "arrived" -> "friendai.debug.navigation.arrived";
                            case "unreachable" -> "friendai.debug.navigation.unreachable";
                            default -> "friendai.debug.navigation.idle";
                        })
                .getString();
    }

    private static int actionColor(DebugAction action) {
        return switch (action) {
            case BREAK -> 0xFFFF5555;
            case PLACE -> 0xFF4DDE86;
            case HARVEST -> 0xFFFFC857;
            case USE -> 0xFF42D6D0;
            case CRAFT -> 0xFFB48AFF;
            case SMELT -> 0xFFFF914D;
            case COLLECT -> 0xFF69AFFF;
        };
    }

    private static String actionLabel(DebugAction action) {
        return Component.translatable(
                        switch (action) {
                            case BREAK -> "friendai.debug.action.break";
                            case PLACE -> "friendai.debug.action.place";
                            case HARVEST -> "friendai.debug.action.harvest";
                            case USE -> "friendai.debug.action.use";
                            case CRAFT -> "friendai.debug.action.craft";
                            case SMELT -> "friendai.debug.action.smelt";
                            case COLLECT -> "friendai.debug.action.collect";
                        })
                .getString();
    }

    private static String stepLabel(String tool) {
        return switch (tool) {
            case "mine_blocks" ->
                Component.translatable("friendai.debug.step.mine_blocks").getString();
            case "mine_block_at" ->
                Component.translatable("friendai.debug.step.mine_block_at").getString();
            case "place_block" ->
                Component.translatable("friendai.debug.step.place_block").getString();
            case "use_block" ->
                Component.translatable("friendai.debug.step.use_block").getString();
            case "build_structure" ->
                Component.translatable("friendai.debug.step.build_structure").getString();
            case "build_special" ->
                Component.translatable("friendai.debug.step.build_special").getString();
            case "craft_item" ->
                Component.translatable("friendai.debug.step.craft_item").getString();
            case "smelt_items" ->
                Component.translatable("friendai.debug.step.smelt_items").getString();
            case "cook_food" ->
                Component.translatable("friendai.debug.step.cook_food").getString();
            case "collect_smelted" ->
                Component.translatable("friendai.debug.step.collect_smelted").getString();
            case "collect_station" ->
                Component.translatable("friendai.debug.step.collect_station").getString();
            case "farm" -> Component.translatable("friendai.debug.step.farm").getString();
            case "tend_farm" ->
                Component.translatable("friendai.debug.step.tend_farm").getString();
            case "use_bonemeal" ->
                Component.translatable("friendai.debug.step.use_bonemeal").getString();
            case "obtain_item" ->
                Component.translatable("friendai.debug.step.obtain_item").getString();
            default -> Component.translatable("friendai.debug.step.generic").getString();
        };
    }

    private static String detailLabel(String detail) {
        DebugDetail resolved = DebugDetail.from(detail);
        if (resolved.translationKey().isBlank()) {
            return detail;
        }
        String argument = resolved.argument();
        if (argument.contains(":")) {
            int separator = argument.indexOf(':');
            String namespace = argument.substring(0, separator);
            String path = argument.substring(separator + 1).replace('/', '.');
            argument = Component.translatable("item." + namespace + "." + path).getString();
        } else if (!argument.isBlank()) {
            String block = switch (argument) {
                case "furnace",
                        "crafting table",
                        "stonecutter",
                        "smithing table",
                        "blast furnace",
                        "smoker",
                        "campfire" -> "block.minecraft." + argument.replace(' ', '_');
                default -> null;
            };
            if (block != null) {
                argument = Component.translatable(block).getString();
            }
        }
        return resolved.argument().isBlank()
                ? Component.translatable(resolved.translationKey()).getString()
                : Component.translatable(resolved.translationKey(), argument).getString();
    }

    private static String clipLabel(String label) {
        return label.length() <= 38 ? label : label.substring(0, 37) + "…";
    }

    private static String clipHudLine(String line) {
        return line.length() <= 68 ? line : line.substring(0, 67) + "…";
    }

    private static void disableAfterDrawingFailure(RuntimeException e) {
        failed = true;
        FriendaiMod.LOGGER.warn("Movement debug drawing failed and is disabled", e);
    }
}
