package dev.friendai.client.ui;

import dev.friendai.debug.LogEntry;
import dev.friendai.debug.LogKind;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** The brain log: filter chips per entry kind above colour-coded, timestamped rows. */
final class LogTab implements MenuTab {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int CHIP_HEIGHT = 14;
    private static final int GAP = 4;

    private final Set<LogKind> enabled = EnumSet.allOf(LogKind.class);
    private final ScrollView view = new ScrollView(true);
    private final List<Chip> chips = new ArrayList<>();

    private List<Row> rows = List.of();
    private String rowsKey = "";

    private record Chip(LogKind kind, int x, int y, int w) {}

    private record Row(LogEntry entry, List<FormattedCharSequence> lines, int height) {}

    private static String kindKey(LogKind kind) {
        return switch (kind) {
            case USER -> "friendai.menu.log.kind.user";
            case ASSISTANT -> "friendai.menu.log.kind.assistant";
            case TOOL_CALL -> "friendai.menu.log.kind.tool_call";
            case TOOL_RESULT -> "friendai.menu.log.kind.tool_result";
            case ERROR -> "friendai.menu.log.kind.error";
            case TASK -> "friendai.menu.log.kind.task";
            case MANUAL -> "friendai.menu.log.kind.manual";
        };
    }

    private static int kindColor(LogKind kind) {
        return switch (kind) {
            case USER -> Ui.ACCENT;
            case ASSISTANT -> Ui.GOOD;
            case TOOL_CALL -> Ui.PURPLE;
            case TOOL_RESULT -> 0xFF7FD6D6;
            case ERROR -> Ui.BAD;
            case TASK -> Ui.WARN;
            case MANUAL -> 0xFFC0A080;
        };
    }

    private static String kindName(LogKind kind) {
        return Component.translatable(kindKey(kind)).getString();
    }

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        int copyWidth = 52;
        host.add(new FlatButton(left + width - copyWidth, top, copyWidth, CHIP_HEIGHT,
                Component.translatable("friendai.menu.log.copy"), Ui.ACCENT, this::copy));
        int chipsHeight = layoutChips(host.font(), left, top, width);
        host.add(new HitArea(left, top, width - copyWidth - GAP, chipsHeight, this::clickChip));
    }

    private List<LogEntry> visibleEntries() {
        return CompanionClientState.log().view(enabled);
    }

    private void copy() {
        List<String> lines = new ArrayList<>();
        for (LogEntry e : visibleEntries()) {
            lines.add("[" + TIME.format(Instant.ofEpochMilli(e.timeMillis())) + "] " + kindName(e.kind()) + ": " + e.text());
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\n", lines));
    }

    /** Places the chips left to right, wrapping before the Copy button; returns the height they take. */
    private int layoutChips(Font font, int left, int top, int width) {
        chips.clear();
        int right = left + width - 52 - GAP;
        int x = left;
        int y = top;
        for (LogKind kind : LogKind.values()) {
            int w = font.width(kindName(kind)) + 14;
            if (x + w > right && x > left) {
                x = left;
                y += CHIP_HEIGHT + GAP;
            }
            chips.add(new Chip(kind, x, y, w));
            x += w + GAP;
        }
        return y + CHIP_HEIGHT - top;
    }

    private void drawChips(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        for (Chip chip : chips) {
            boolean on = enabled.contains(chip.kind());
            boolean hot = mouseX >= chip.x() && mouseX < chip.x() + chip.w()
                    && mouseY >= chip.y() && mouseY < chip.y() + CHIP_HEIGHT;
            int color = kindColor(chip.kind());
            int tint = on ? color : Ui.DIM;
            g.fill(chip.x(), chip.y(), chip.x() + chip.w(), chip.y() + CHIP_HEIGHT,
                    Ui.alpha(tint, on ? (hot ? 0x60 : 0x38) : (hot ? 0x30 : 0x14)));
            Ui.border(g, chip.x(), chip.y(), chip.w(), CHIP_HEIGHT, Ui.alpha(tint, on ? 0xB0 : 0x50));
            Ui.dot(g, chip.x() + 4, chip.y() + CHIP_HEIGHT / 2 - 2, 4, on ? color : Ui.alpha(Ui.DIM, 0x80));
            g.text(font, kindName(chip.kind()), chip.x() + 11, chip.y() + 3, on ? Ui.TEXT : Ui.DIM);
        }
    }

    private void rebuildRows(Font font, List<LogEntry> entries, int width) {
        String key = entries.size() + ":" + (entries.isEmpty() ? 0 : entries.get(entries.size() - 1).id())
                + ":" + (entries.isEmpty() ? 0 : entries.get(0).id()) + ":" + width;
        if (key.equals(rowsKey)) {
            return;
        }
        rowsKey = key;
        int badgeWidth = 0;
        for (LogKind kind : LogKind.values()) {
            badgeWidth = Math.max(badgeWidth, Ui.badgeWidth(font, kindName(kind)));
        }
        int prefix = font.width("00:00:00") + 6 + badgeWidth + 6;
        List<Row> built = new ArrayList<>();
        for (LogEntry e : entries) {
            List<FormattedCharSequence> lines = font.split(Component.literal(e.text()), Math.max(30, width - prefix - 12));
            built.add(new Row(e, lines, Math.max(1, lines.size()) * (font.lineHeight + 1) + 5));
        }
        rows = built;
    }

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        int headerHeight = layoutChips(font, left, top, width);
        drawChips(g, font, mouseX, mouseY);
        int listTop = top + headerHeight + GAP + 2;
        int listHeight = top + height - listTop;
        Ui.card(g, left, listTop, width, listHeight, Ui.alpha(Ui.TRACK, 0xA0), Ui.BORDER);
        List<LogEntry> entries = visibleEntries();
        if (entries.isEmpty()) {
            g.text(font, Component.translatable("friendai.menu.log.empty"), left + 8, listTop + 8, Ui.DIM);
            return;
        }
        rebuildRows(font, entries, width - 8);
        int badgeWidth = 0;
        for (LogKind kind : LogKind.values()) {
            badgeWidth = Math.max(badgeWidth, Ui.badgeWidth(font, kindName(kind)));
        }
        int total = 0;
        for (Row row : rows) {
            total += row.height();
        }
        view.begin(g, left + 1, listTop + 1, width - 2, listHeight - 2, total + 4);
        int y = view.top() + 2;
        int index = 0;
        int timeWidth = font.width("00:00:00");
        for (Row row : rows) {
            if (view.visible(y, row.height())) {
                int color = kindColor(row.entry().kind());
                if (index % 2 == 1) {
                    g.fill(left + 1, y, left + width - 1, y + row.height(), Ui.alpha(0xFFFFFF, 0x08));
                }
                g.fill(left + 1, y, left + 3, y + row.height(), Ui.alpha(color, 0xC0));
                int x = left + 8;
                g.text(font, TIME.format(Instant.ofEpochMilli(row.entry().timeMillis())), x, y + 2, Ui.DIM);
                Ui.badge(g, font, kindName(row.entry().kind()), x + timeWidth + 6, y + 1, color);
                int textX = x + timeWidth + 6 + badgeWidth + 6;
                int lineY = y + 2;
                for (FormattedCharSequence line : row.lines()) {
                    g.text(font, line, textX, lineY, row.entry().kind() == LogKind.ERROR ? Ui.BAD : Ui.TEXT);
                    lineY += font.lineHeight + 1;
                }
            }
            y += row.height();
            index++;
        }
        view.end(g);
    }

    private void clickChip(double x, double y) {
        for (Chip chip : chips) {
            if (x >= chip.x() && x < chip.x() + chip.w() && y >= chip.y() && y < chip.y() + CHIP_HEIGHT) {
                if (!enabled.add(chip.kind())) {
                    enabled.remove(chip.kind());
                }
                rowsKey = "";
                return;
            }
        }
    }

    @Override
    public boolean scroll(double x, double y, double amount) {
        return view.contains(x, y) && view.scroll(amount);
    }
}
