package dev.friendai.client.ui;

import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.QueueRow;
import dev.friendai.debug.QueueRows;
import dev.friendai.debug.TaskRecord;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Every queued job drawn as a chain: plans and single tasks as groups, each job a node on a vertical line with its
 * tool, arguments, why it waits and what it depends on; the history of finished tasks follows underneath.
 */
final class TasksTab implements MenuTab {
    private static final int PAD = 8;
    private static final int GAP = 3;
    private static final int RAIL = 14;
    private static final int MAX_NOTE_LINES = 2;
    private static final int MAX_SUMMARY_LINES = 3;

    private final ScrollView view = new ScrollView(false);

    /** One block of the scrolling content: its height and how to draw it at a position. */
    private record Block(int height, Painter painter) {}

    @FunctionalInterface
    private interface Painter {
        void paint(GuiGraphicsExtractor g, int x, int y, int width);
    }

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
        int contentWidth = width - 6;
        List<Block> blocks = new ArrayList<>();
        blocks.add(summaryBlock(font, s, contentWidth));
        addChain(blocks, font, s.queue(), contentWidth);
        addHistory(blocks, font, s.history(), contentWidth);

        int total = 0;
        for (Block block : blocks) {
            total += block.height() + GAP;
        }
        view.begin(g, left, top, width, height, total);
        int y = view.top();
        for (Block block : blocks) {
            if (view.visible(y, block.height())) {
                block.painter().paint(g, left, y, contentWidth);
            }
            y += block.height() + GAP;
        }
        view.end(g);
    }

    private static Block summaryBlock(Font font, CompanionSnapshot s, int width) {
        String text = s.present() ? s.currentTask() : "";
        int inner = width - 2 * PAD;
        int height = 22 + QueuePanel.draw(null, font, text, 0, 0, inner) + 4;
        return new Block(height, (g, x, y, w) -> {
            Ui.card(g, x, y, w, height);
            Ui.heading(g, font, Component.translatable("friendai.menu.activity"), x + PAD, y + 6, inner);
            QueuePanel.draw(g, font, text, x + PAD, y + 20, inner);
        });
    }

    private static void addChain(List<Block> blocks, Font font, List<QueueRow> rows, int width) {
        blocks.add(new Block(12, (g, x, y, w) ->
                Ui.heading(g, font, Component.translatable("friendai.menu.chain"), x, y, w)));
        if (rows.isEmpty()) {
            blocks.add(new Block(14, (g, x, y, w) ->
                    Ui.text(g, font, Component.translatable("friendai.menu.activity.idle"), x + 2, y + 2, Ui.DIM)));
            return;
        }
        int index = 0;
        while (index < rows.size()) {
            long plan = rows.get(index).planId();
            int end = index;
            while (end < rows.size() && rows.get(end).planId() == plan) {
                end++;
            }
            List<QueueRow> group = rows.subList(index, end);
            blocks.add(groupHeader(font, group));
            for (int i = 0; i < group.size(); i++) {
                blocks.add(node(font, group.get(i), i == 0, i == group.size() - 1, width));
            }
            index = end;
        }
        if (rows.size() >= QueueRows.MAX_ROWS) {
            blocks.add(new Block(14, (g, x, y, w) -> Ui.text(g, font,
                    Component.translatable("friendai.menu.chain.more", QueueRows.MAX_ROWS), x + 2, y + 2, Ui.DIM)));
        }
    }

    private static Block groupHeader(Font font, List<QueueRow> group) {
        QueueRow first = group.getFirst();
        boolean direct = first.planId() == 0;
        long done = group.stream().filter(r -> r.state().equals("done") || r.state().equals("skipped")).count();
        int color = direct ? Ui.ACCENT : planColor(first.planStatus());
        return new Block(26, (g, x, y, w) -> {
            Ui.card(g, x, y, w, 26);
            g.fill(x, y, x + 3, y + 26, color);
            Component title = direct
                    ? Component.translatable("friendai.menu.chain.direct")
                    : Component.literal(first.planGoal().isBlank() ? "#" + first.planId() : first.planGoal());
            int badgeWidth = 0;
            if (!direct) {
                Component status = Component.translatable(planKey(first.planStatus()));
                badgeWidth = Ui.badgeWidth(font, status.getString());
                Ui.badge(g, font, status.getString(), x + w - badgeWidth - 5, y + 3, color);
            }
            Ui.text(g, font, Ui.clip(font, title.getString(), w - badgeWidth - 2 * PAD - 6), x + PAD + 2, y + 5, Ui.TEXT);
            String progress = Component.translatable("friendai.menu.chain.progress", done, group.size()).getString();
            Ui.text(g, font, progress, x + PAD + 2, y + 15, Ui.DIM);
            int barX = x + PAD + 2 + font.width(progress) + 6;
            Ui.bar(g, barX, y + 16, Math.max(10, x + w - PAD - barX), 5, done / (float) group.size(), color);
        });
    }

    private static Block node(Font font, QueueRow row, boolean first, boolean last, int width) {
        int cardX = RAIL;
        int textWidth = width - cardX - 2 * PAD;
        List<FormattedCharSequence> note = row.note().isBlank()
                ? List.of()
                : limit(font.split(Component.literal(row.note()), textWidth), MAX_NOTE_LINES);
        String meta = meta(row);
        boolean hasArgs = !row.args().isBlank() && !row.args().equals("{}");
        int height = 18 + (hasArgs ? 10 : 0) + note.size() * (font.lineHeight + 1) + (meta.isEmpty() ? 0 : 10) + 4;
        int color = stateColor(row.state());
        return new Block(height, (g, x, y, w) -> {
            int railX = x + RAIL / 2;
            g.fill(railX, y - (first ? 0 : GAP), railX + 1, y + height + (last ? 0 : GAP), Ui.alpha(Ui.BORDER, 0xFF));
            Ui.dot(g, railX - 3, y + 6, 7, color);
            Ui.card(g, x + cardX, y, w - cardX, height);
            g.fill(x + cardX, y, x + cardX + 2, y + height, color);
            int tx = x + cardX + PAD;
            Component state = Component.translatable(jobKey(row.state()));
            int badgeWidth = Ui.badgeWidth(font, state.getString());
            Ui.badge(g, font, state.getString(), x + w - badgeWidth - 5, y + 3, color);
            Ui.text(g, font, "#" + row.id(), tx, y + 5, Ui.DIM);
            int idWidth = font.width("#" + row.id()) + 5;
            Ui.text(g, font, Ui.clip(font, row.tool(), w - cardX - PAD - idWidth - badgeWidth - 14), tx + idWidth, y + 5, Ui.TEXT);
            int lineY = y + 18;
            if (hasArgs) {
                Ui.text(g, font, Ui.clip(font, row.args(), textWidth), tx, lineY, Ui.DIM);
                lineY += 10;
            }
            for (FormattedCharSequence line : note) {
                Ui.text(g, font, line, tx, lineY, noteColor(row.state()));
                lineY += font.lineHeight + 1;
            }
            if (!meta.isEmpty()) {
                Ui.text(g, font, Ui.clip(font, meta, textWidth), tx, lineY, Ui.DIM);
            }
        });
    }

    private static String meta(QueueRow row) {
        List<String> parts = new ArrayList<>();
        if (!row.after().isEmpty()) {
            StringBuilder ids = new StringBuilder();
            for (long id : row.after()) {
                if (!ids.isEmpty()) {
                    ids.append(", ");
                }
                ids.append('#').append(id);
            }
            parts.add(Component.translatable("friendai.menu.chain.after", ids.toString()).getString());
        }
        if (row.attempts() > 0 && row.maxAttempts() > 1) {
            parts.add(Component.translatable("friendai.menu.chain.attempt", row.attempts(), row.maxAttempts()).getString());
        }
        return String.join("  ·  ", parts);
    }

    private static void addHistory(List<Block> blocks, Font font, List<TaskRecord> records, int width) {
        blocks.add(new Block(12, (g, x, y, w) ->
                Ui.heading(g, font, Component.translatable("friendai.menu.history"), x, y, w)));
        if (records.isEmpty()) {
            blocks.add(new Block(14, (g, x, y, w) ->
                    Ui.text(g, font, Component.translatable("friendai.menu.history_empty"), x + 2, y + 2, Ui.DIM)));
            return;
        }
        for (TaskRecord record : records) {
            List<FormattedCharSequence> lines =
                    limit(font.split(Component.literal(record.summary()), width - 2 * PAD - 4), MAX_SUMMARY_LINES);
            int height = 18 + lines.size() * (font.lineHeight + 1) + 3;
            blocks.add(new Block(height, (g, x, y, w) -> drawRecord(g, font, record, lines, x, y, w, height)));
        }
    }

    private static void drawRecord(GuiGraphicsExtractor g, Font font, TaskRecord r, List<FormattedCharSequence> summary,
            int x, int y, int w, int h) {
        int color = historyColor(r.status());
        Ui.card(g, x, y, w, h);
        g.fill(x, y, x + 3, y + h, color);
        int textX = x + PAD + 2;
        Ui.text(g, font, "#" + r.id(), textX, y + 5, Ui.DIM);
        int idWidth = font.width("#" + r.id()) + 5;
        Component status = Component.translatable(historyKey(r.status()));
        int badgeWidth = Ui.badgeWidth(font, status.getString());
        Ui.badge(g, font, status.getString(), x + w - badgeWidth - 5, y + 3, color);
        Ui.text(g, font, Ui.clip(font, r.toolName(), w - idWidth - badgeWidth - 2 * PAD - 8), textX + idWidth, y + 5, Ui.TEXT);
        int lineY = y + 18;
        for (FormattedCharSequence line : summary) {
            Ui.text(g, font, line, textX, lineY, Ui.DIM);
            lineY += font.lineHeight + 1;
        }
    }

    private static <T> List<T> limit(List<T> list, int max) {
        return list.size() > max ? list.subList(0, max) : list;
    }

    private static String jobKey(String state) {
        return switch (state) {
            case "running" -> "friendai.menu.job.running";
            case "ready" -> "friendai.menu.job.ready";
            case "blocked" -> "friendai.menu.job.blocked";
            case "done" -> "friendai.menu.job.done";
            case "failed" -> "friendai.menu.job.failed";
            case "skipped" -> "friendai.menu.job.skipped";
            default -> "friendai.menu.job.cancelled";
        };
    }

    private static String planKey(String status) {
        return switch (status) {
            case "needs_decision" -> "friendai.menu.plan.needs_decision";
            case "done" -> "friendai.menu.plan.done";
            case "failed" -> "friendai.menu.plan.failed";
            default -> "friendai.menu.plan.active";
        };
    }

    private static int stateColor(String state) {
        return switch (state) {
            case "running" -> Ui.ACCENT;
            case "ready" -> Ui.PURPLE;
            case "blocked" -> Ui.WARN;
            case "done" -> Ui.GOOD;
            case "failed" -> Ui.BAD;
            default -> Ui.DIM;
        };
    }

    private static int noteColor(String state) {
        return switch (state) {
            case "blocked" -> Ui.WARN;
            case "failed" -> Ui.BAD;
            default -> Ui.DIM;
        };
    }

    private static int planColor(String status) {
        return switch (status) {
            case "done" -> Ui.GOOD;
            case "failed" -> Ui.BAD;
            case "needs_decision" -> Ui.WARN;
            default -> Ui.ACCENT;
        };
    }

    private static int historyColor(String status) {
        return switch (status) {
            case "SUCCESS" -> Ui.GOOD;
            case "FAILED" -> Ui.BAD;
            default -> Ui.DIM;
        };
    }

    private static String historyKey(String status) {
        return switch (status) {
            case "SUCCESS" -> "friendai.menu.status.success";
            case "FAILED" -> "friendai.menu.status.failed";
            default -> "friendai.menu.status.cancelled";
        };
    }

    @Override
    public boolean scroll(double x, double y, double amount) {
        return view.contains(x, y) && view.scroll(amount);
    }
}
