package dev.friendai.client.ui;

import dev.friendai.debug.QueueSummary;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Draws the companion's task queue: counters first, then the running, next and blocked jobs. */
final class QueuePanel {
    private static final int LINE_GAP = 3;
    private static final int CHIP_GAP = 4;

    private QueuePanel() {}

    /**
     * Draws the queue summary text at ({@code x}, {@code y}) within {@code width} and returns the height used.
     * With a null {@code g} nothing is drawn and only the height is computed.
     */
    static int draw(GuiGraphicsExtractor g, Font font, String text, int x, int y, int width) {
        Optional<QueueSummary> parsed = QueueSummary.parse(text);
        if (parsed.isEmpty()) {
            return drawPlain(g, font, text, x, y, width);
        }
        QueueSummary q = parsed.get();
        int cursor = y;
        int chipX = x;
        chipX += chip(g, font, Component.translatable("friendai.menu.queue.running", q.running()).getString(),
                chipX, cursor, q.running() > 0 ? Ui.GOOD : Ui.DIM) + CHIP_GAP;
        chipX += chip(g, font, Component.translatable("friendai.menu.queue.pending", q.pending()).getString(),
                chipX, cursor, q.pending() > 0 ? Ui.ACCENT : Ui.DIM) + CHIP_GAP;
        if (q.blocked() > 0) {
            chipX += chip(g, font, Component.translatable("friendai.menu.queue.blocked", q.blocked()).getString(),
                    chipX, cursor, Ui.WARN) + CHIP_GAP;
        }
        if (q.plans() > 0) {
            chip(g, font, Component.translatable("friendai.menu.queue.plans", q.plans()).getString(),
                    chipX, cursor, Ui.PURPLE);
        }
        cursor += Ui.BADGE_HEIGHT + 6;
        cursor += job(g, font, "friendai.menu.activity.running", Ui.GOOD, q.runningLine(), x, cursor, width);
        cursor += job(g, font, "friendai.menu.activity.next", Ui.ACCENT, q.nextLine(), x, cursor, width);
        cursor += job(g, font, "friendai.menu.activity.blocked", Ui.WARN, q.blockedLine(), x, cursor, width);
        return cursor - y;
    }

    private static int chip(GuiGraphicsExtractor g, Font font, String label, int x, int y, int color) {
        if (g == null) {
            return Ui.badgeWidth(font, label);
        }
        return Ui.badge(g, font, label, x, y, color);
    }

    private static int drawPlain(GuiGraphicsExtractor g, Font font, String text, int x, int y, int width) {
        boolean idle = text == null || text.isBlank() || text.equals("none") || text.startsWith("Task queue: empty");
        Component shown = idle ? Component.translatable("friendai.menu.activity.idle") : Component.literal(text);
        int color = idle ? Ui.DIM : Ui.TEXT;
        int cursor = y;
        for (FormattedCharSequence line : font.split(shown, width)) {
            if (g != null) {
                Ui.text(g, font, line, x, cursor, color);
            }
            cursor += font.lineHeight + 1;
        }
        return cursor - y;
    }

    private static int job(
            GuiGraphicsExtractor g, Font font, String labelKey, int color, String body, int x, int y, int width) {
        if (body.isEmpty()) {
            return 0;
        }
        Component label = Component.translatable(labelKey);
        int labelWidth = Math.max(54, font.width(label) + 8);
        int textX = x + labelWidth + 4;
        int cursor = y;
        if (g != null) {
            g.fill(x, y, x + labelWidth, y + Ui.BADGE_HEIGHT, color);
            Ui.text(g, font, label, x + 5, y + 2, Ui.ON_WELL);
        }
        for (FormattedCharSequence line : font.split(Component.literal(body), Math.max(20, width - labelWidth - 4))) {
            if (g != null) {
                Ui.text(g, font, line, textX, cursor + 2, Ui.TEXT);
            }
            cursor += font.lineHeight + 1;
        }
        return Math.max(Ui.BADGE_HEIGHT, cursor - y + 2) + LINE_GAP;
    }
}
