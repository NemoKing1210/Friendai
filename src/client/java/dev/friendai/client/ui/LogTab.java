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
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * The brain log: filter chips per entry kind above colour-coded, timestamped rows. The text can be selected with the
 * mouse (drag, or double-click for a whole entry) and copied with Ctrl+C or the Copy button.
 */
final class LogTab implements MenuTab {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int CHIP_HEIGHT = 14;
    private static final int GAP = 4;

    private final Set<LogKind> enabled = EnumSet.allOf(LogKind.class);
    private final ScrollView view = new ScrollView(true);
    private final List<Chip> chips = new ArrayList<>();

    private List<Row> rows = List.of();
    private String rowsKey = "";

    private record Chip(LogKind kind, int x, int y, int w) {}

    /** A wrapped line of an entry's text: the {@code [start, end)} range of {@link LogEntry#text()}. */
    private record Span(int start, int end) {}

    private record Row(LogEntry entry, List<Span> lines, int height) {}

    /** A caret position: a character offset into one entry's text. */
    private record Pos(long id, int offset) {}

    private static final int AUTOSCROLL_MAX = 12;

    private Pos anchor;
    private Pos caret;
    private boolean selecting;
    private int textX;
    private int areaTop;
    private int areaBottom;

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
            case TOOL_RESULT -> Ui.PALETTE[5];
            case ERROR -> Ui.BAD;
            case TASK -> Ui.WARN;
            case MANUAL -> Ui.PALETTE[6];
        };
    }

    private static String kindName(LogKind kind) {
        return Component.translatable(kindKey(kind)).getString();
    }

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        int copyWidth = 52;
        host.add(new FlatButton(
                left + width - copyWidth,
                top,
                copyWidth,
                CHIP_HEIGHT,
                Component.translatable("friendai.menu.log.copy"),
                Ui.ACCENT,
                this::copy));
        int chipsHeight = layoutChips(host.font(), left, top, width);
        host.add(new HitArea(left, top, width - copyWidth - GAP, chipsHeight, this::clickChip));
        int areaTop = top + chipsHeight + GAP + 2;
        host.add(new SelectArea(left, areaTop, width, top + height - areaTop, new SelectArea.Handler() {
            @Override
            public void press(double x, double y, boolean doubleClick) {
                LogTab.this.press(x, y, doubleClick);
            }

            @Override
            public void drag(double x, double y) {
                if (selecting) {
                    caret = hit(x, y);
                }
            }

            @Override
            public void release() {
                selecting = false;
            }

            @Override
            public boolean key(KeyEvent event) {
                return LogTab.this.key(event);
            }
        }));
    }

    private void press(double x, double y, boolean doubleClick) {
        Pos at = hit(x, y);
        anchor = at;
        caret = at;
        selecting = at != null;
        if (at != null && doubleClick) {
            int index = rowIndex(at.id());
            String text = rows.get(index).entry().text();
            anchor = new Pos(at.id(), 0);
            caret = new Pos(at.id(), text.length());
            selecting = false;
        }
    }

    private boolean key(KeyEvent event) {
        if (event.isCopy()) {
            String text = selectedText();
            if (!text.isEmpty()) {
                Minecraft.getInstance().keyboardHandler.setClipboard(text);
            }
            return true;
        }
        if (event.isSelectAll() && !rows.isEmpty()) {
            Row first = rows.get(0);
            Row last = rows.get(rows.size() - 1);
            anchor = new Pos(first.entry().id(), 0);
            caret = new Pos(last.entry().id(), last.entry().text().length());
            return true;
        }
        return false;
    }

    private int rowIndex(long id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).entry().id() == id) {
                return i;
            }
        }
        return -1;
    }

    /** The text position under a screen point, clamped to the first and last row; null when the log is empty. */
    private Pos hit(double x, double y) {
        if (rows.isEmpty()) {
            return null;
        }
        Font font = Minecraft.getInstance().font;
        int rowY = view.contentY(y) - 2;
        int rowTop = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean above = i == 0 && rowY < 0;
            if (above || rowY < rowTop + row.height() || i == rows.size() - 1) {
                int line = Math.max(0, Math.min(row.lines().size() - 1, (rowY - rowTop - 2) / (font.lineHeight + 1)));
                Span span = row.lines().get(line);
                String text = row.entry().text();
                int offset = span.start();
                if (x > textX) {
                    String shown = text.substring(span.start(), span.end());
                    offset += Math.min(
                            shown.length(),
                            font.getSplitter().plainIndexAtWidth(shown, (int) (x - textX), Style.EMPTY));
                }
                return new Pos(row.entry().id(), offset);
            }
            rowTop += row.height();
        }
        return null;
    }

    private int compare(Pos a, Pos b) {
        int byRow = Integer.compare(rowIndex(a.id()), rowIndex(b.id()));
        return byRow != 0 ? byRow : Integer.compare(a.offset(), b.offset());
    }

    private String selectedText() {
        if (anchor == null || caret == null || rowIndex(anchor.id()) < 0 || rowIndex(caret.id()) < 0) {
            return "";
        }
        Pos from = compare(anchor, caret) <= 0 ? anchor : caret;
        Pos to = from == anchor ? caret : anchor;
        int first = rowIndex(from.id());
        int last = rowIndex(to.id());
        StringBuilder out = new StringBuilder();
        for (int i = first; i <= last; i++) {
            String text = rows.get(i).entry().text();
            int start = i == first ? Math.min(from.offset(), text.length()) : 0;
            int end = i == last ? Math.min(to.offset(), text.length()) : text.length();
            if (end <= start && first != last) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            if (end > start) {
                out.append(text, start, end);
            }
        }
        return out.toString();
    }

    private List<LogEntry> visibleEntries() {
        return CompanionClientState.log().view(enabled);
    }

    /** Copies the selection, or the whole visible log when nothing is selected. */
    private void copy() {
        String selected = selectedText();
        if (!selected.isEmpty()) {
            Minecraft.getInstance().keyboardHandler.setClipboard(selected);
            return;
        }
        List<String> lines = new ArrayList<>();
        for (LogEntry e : visibleEntries()) {
            lines.add("[" + TIME.format(Instant.ofEpochMilli(e.timeMillis())) + "] " + kindName(e.kind()) + ": "
                    + e.text());
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
            boolean hot = mouseX >= chip.x()
                    && mouseX < chip.x() + chip.w()
                    && mouseY >= chip.y()
                    && mouseY < chip.y() + CHIP_HEIGHT;
            int color = kindColor(chip.kind());
            if (on) {
                g.fill(
                        chip.x(),
                        chip.y(),
                        chip.x() + chip.w(),
                        chip.y() + CHIP_HEIGHT,
                        hot ? Ui.alpha(color, 0xE0) : color);
                Ui.text(g, font, kindName(chip.kind()), chip.x() + 6, chip.y() + 3, Ui.ON_WELL);
            } else {
                g.fill(chip.x(), chip.y(), chip.x() + chip.w(), chip.y() + CHIP_HEIGHT, hot ? Ui.CARD_HOVER : Ui.CARD);
                Ui.border(g, chip.x(), chip.y(), chip.w(), CHIP_HEIGHT, Ui.BORDER);
                Ui.text(g, font, kindName(chip.kind()), chip.x() + 6, chip.y() + 3, Ui.DIM);
            }
        }
    }

    private void rebuildRows(Font font, List<LogEntry> entries, int width) {
        String key = entries.size() + ":"
                + (entries.isEmpty() ? 0 : entries.get(entries.size() - 1).id()) + ":"
                + (entries.isEmpty() ? 0 : entries.get(0).id()) + ":" + width;
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
            List<Span> lines = new ArrayList<>();
            font.getSplitter()
                    .splitLines(
                            e.text(),
                            Math.max(30, width - prefix - 12),
                            Style.EMPTY,
                            false,
                            (style, start, end) -> lines.add(new Span(start, end)));
            if (lines.isEmpty()) {
                lines.add(new Span(0, 0));
            }
            built.add(new Row(e, lines, lines.size() * (font.lineHeight + 1) + 5));
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
        Ui.well(g, left, listTop, width, listHeight);
        List<LogEntry> entries = visibleEntries();
        if (entries.isEmpty()) {
            rows = List.of();
            rowsKey = "";
            Ui.text(g, font, Component.translatable("friendai.menu.log.empty"), left + 8, listTop + 8, Ui.ON_WELL_DIM);
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
        int timeWidth = font.width("00:00:00");
        textX = left + 8 + timeWidth + 6 + badgeWidth + 6;
        areaTop = listTop;
        areaBottom = listTop + listHeight;
        if (selecting) {
            // Dragging past an edge scrolls the log, faster the farther the pointer is, and keeps extending.
            if (mouseY < areaTop) {
                view.nudge(-Math.min(AUTOSCROLL_MAX, 2 + (areaTop - mouseY) / 4));
            } else if (mouseY > areaBottom) {
                view.nudge(Math.min(AUTOSCROLL_MAX, 2 + (mouseY - areaBottom) / 4));
            }
            caret = hit(mouseX, mouseY);
        }
        Pos from = null;
        Pos to = null;
        if (anchor != null
                && caret != null
                && rowIndex(anchor.id()) >= 0
                && rowIndex(caret.id()) >= 0
                && !anchor.equals(caret)) {
            from = compare(anchor, caret) <= 0 ? anchor : caret;
            to = from == anchor ? caret : anchor;
        }
        int firstSelected = from == null ? -1 : rowIndex(from.id());
        int lastSelected = to == null ? -1 : rowIndex(to.id());
        view.begin(g, left + 1, listTop + 1, width - 2, listHeight - 2, total + 4);
        int y = view.top() + 2;
        int index = 0;
        for (Row row : rows) {
            if (view.visible(y, row.height())) {
                int color = kindColor(row.entry().kind());
                if (index % 2 == 1) {
                    g.fill(left + 1, y, left + width - 1, y + row.height(), Ui.alpha(0xFFFFFF, 0x08));
                }
                g.fill(left + 1, y, left + 3, y + row.height(), Ui.alpha(color, 0xC0));
                int x = left + 8;
                Ui.text(g, font, TIME.format(Instant.ofEpochMilli(row.entry().timeMillis())), x, y + 2, Ui.ON_WELL_DIM);
                Ui.badge(g, font, kindName(row.entry().kind()), x + timeWidth + 6, y + 1, color);
                String text = row.entry().text();
                int lineY = y + 2;
                for (Span span : row.lines()) {
                    String line = text.substring(span.start(), span.end());
                    if (index >= firstSelected && index <= lastSelected) {
                        int selStart = index == firstSelected ? Math.max(from.offset(), span.start()) : span.start();
                        int selEnd = index == lastSelected ? Math.min(to.offset(), span.end()) : span.end();
                        if (selEnd > selStart) {
                            int x0 = textX + font.width(text.substring(span.start(), selStart));
                            int x1 = textX + font.width(text.substring(span.start(), selEnd));
                            g.fill(x0, lineY - 1, x1, lineY + font.lineHeight, Ui.alpha(Ui.ACCENT, 0x70));
                        }
                    }
                    Ui.text(
                            g,
                            font,
                            line,
                            textX,
                            lineY,
                            row.entry().kind() == LogKind.ERROR ? Ui.ON_WELL_BAD : Ui.ON_WELL);
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
                anchor = null;
                caret = null;
                return;
            }
        }
    }

    @Override
    public boolean scroll(double x, double y, double amount) {
        return view.contains(x, y) && view.scroll(amount);
    }
}
