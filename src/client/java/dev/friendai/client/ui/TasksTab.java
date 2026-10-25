package dev.friendai.client.ui;

import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.TaskRecord;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** The task queue as counters and rows, and the history of finished tasks as a coloured list. */
final class TasksTab implements MenuTab {
    private static final int PAD = 8;
    private static final int GAP = 6;
    private static final int MAX_SUMMARY_LINES = 3;

    private final ScrollView history = new ScrollView(false);

    @Override
    public void init(Host host, int left, int top, int width, int height) {}

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        var snapshot = CompanionClientState.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        CompanionSnapshot s = snapshot.get();
        int innerWidth = width - 2 * PAD;
        String queue = s.present() ? s.currentTask() : "";
        int queueHeight = QueuePanel.draw(null, font, queue, 0, 0, innerWidth);
        int cardHeight = Math.min(height / 2, 22 + queueHeight + 4);
        Ui.card(g, left, top, width, cardHeight);
        Ui.heading(g, font, Component.translatable("friendai.menu.activity"), left + PAD, top + 6, innerWidth);
        g.enableScissor(left + 1, top + 18, left + width - 1, top + cardHeight - 1);
        QueuePanel.draw(g, font, queue, left + PAD, top + 20, innerWidth);
        g.disableScissor();

        int listTop = top + cardHeight + GAP;
        Ui.heading(g, font, Component.translatable("friendai.menu.history"), left, listTop, width);
        listTop += 14;
        int listHeight = top + height - listTop;
        List<TaskRecord> records = s.history();
        if (records.isEmpty()) {
            g.text(font, Component.translatable("friendai.menu.history_empty"), left + 2, listTop + 4, Ui.DIM);
            return;
        }
        int rowWidth = width - 6;
        int[] heights = new int[records.size()];
        List<List<FormattedCharSequence>> summaries = new java.util.ArrayList<>();
        int total = 0;
        for (int i = 0; i < records.size(); i++) {
            List<FormattedCharSequence> lines = font.split(Component.literal(records.get(i).summary()), rowWidth - 2 * PAD - 4);
            if (lines.size() > MAX_SUMMARY_LINES) {
                lines = lines.subList(0, MAX_SUMMARY_LINES);
            }
            summaries.add(lines);
            heights[i] = 18 + lines.size() * (font.lineHeight + 1) + 3;
            total += heights[i] + 3;
        }
        history.begin(g, left, listTop, width, listHeight, total);
        int y = history.top();
        for (int i = 0; i < records.size(); i++) {
            if (history.visible(y, heights[i])) {
                drawRecord(g, font, records.get(i), summaries.get(i), left, y, rowWidth, heights[i]);
            }
            y += heights[i] + 3;
        }
        history.end(g);
    }

    private static void drawRecord(GuiGraphicsExtractor g, Font font, TaskRecord r, List<FormattedCharSequence> summary,
            int x, int y, int w, int h) {
        int color = statusColor(r.status());
        Ui.card(g, x, y, w, h);
        g.fill(x, y, x + 3, y + h, color);
        int textX = x + PAD + 2;
        g.text(font, "#" + r.id(), textX, y + 5, Ui.DIM);
        int idWidth = font.width("#" + r.id()) + 5;
        Component status = Component.translatable(statusKey(r.status()));
        int badgeWidth = Ui.badgeWidth(font, status.getString());
        Ui.badge(g, font, status.getString(), x + w - badgeWidth - 5, y + 3, color);
        g.text(font, Ui.clip(font, r.toolName(), w - idWidth - badgeWidth - 2 * PAD - 8), textX + idWidth, y + 5, Ui.TEXT);
        int lineY = y + 18;
        for (FormattedCharSequence line : summary) {
            g.text(font, line, textX, lineY, Ui.DIM);
            lineY += font.lineHeight + 1;
        }
    }

    private static int statusColor(String status) {
        return switch (status) {
            case "SUCCESS" -> Ui.GOOD;
            case "FAILED" -> Ui.BAD;
            default -> Ui.DIM;
        };
    }

    private static String statusKey(String status) {
        return switch (status) {
            case "SUCCESS" -> "friendai.menu.status.success";
            case "FAILED" -> "friendai.menu.status.failed";
            default -> "friendai.menu.status.cancelled";
        };
    }

    @Override
    public boolean scroll(double x, double y, double amount) {
        return history.contains(x, y) && history.scroll(amount);
    }
}
