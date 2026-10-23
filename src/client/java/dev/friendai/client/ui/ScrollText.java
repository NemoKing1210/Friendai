package dev.friendai.client.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Word-wrapped, scrollable plain text. Wrapping is cached until the lines or the width change. */
final class ScrollText {
    private static final int COLOR = 0xFFFFFFFF;

    private final boolean anchorBottom;
    private List<String> source = List.of();
    private List<FormattedCharSequence> wrapped = List.of();
    private int wrappedWidth = -1;
    private int offset;

    /** @param anchorBottom keep the end of the text in view (a log) instead of the start. */
    ScrollText(boolean anchorBottom) {
        this.anchorBottom = anchorBottom;
    }

    void setLines(List<String> lines) {
        if (!lines.equals(source)) {
            source = List.copyOf(lines);
            wrappedWidth = -1;
        }
    }

    boolean scroll(double amount) {
        offset += (int) Math.round(anchorBottom ? amount * 3 : -amount * 3);
        offset = Math.max(0, offset);
        return true;
    }

    List<String> lines() {
        return source;
    }

    void render(GuiGraphicsExtractor g, Font font, int x, int y, int width, int height) {
        if (wrappedWidth != width) {
            List<FormattedCharSequence> out = new ArrayList<>();
            for (String line : source) {
                out.addAll(font.split(Component.literal(line), width));
            }
            wrapped = out;
            wrappedWidth = width;
        }
        int lineHeight = font.lineHeight + 1;
        int fit = Math.max(1, height / lineHeight);
        int maxOffset = Math.max(0, wrapped.size() - fit);
        offset = Math.min(offset, maxOffset);
        int start = anchorBottom ? maxOffset - offset : offset;
        for (int i = 0; i < fit && start + i < wrapped.size(); i++) {
            g.text(font, wrapped.get(start + i), x, y + i * lineHeight, COLOR);
        }
    }
}
