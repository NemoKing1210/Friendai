package dev.friendai.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * A row a tab paints itself, made a real widget so the keyboard can work it: Tab reaches it, the narrator reads its
 * text, Enter or Space selects it, and it paints a focus ring while the keyboard is on it. At rest it draws nothing,
 * so the row looks exactly as the tab painted it, and it takes a click on the same pixels a plain hit box did.
 *
 * <p>{@link #place} clips the widget to the list it belongs to: a row scrolled half out of its view only takes a
 * click, and only shows the ring, over the visible half. A row scrolled out entirely keeps zero height and stays
 * focusable, so Tab can reach it and the tab can scroll it back in with {@link #rowTop()}.
 */
final class FocusRow extends AbstractWidget {
    private final Runnable action;
    private int ringWidth;
    private int rowTop;
    private int rowHeight;

    FocusRow(Component message, Runnable action) {
        super(0, 0, 0, 0, message);
        this.action = action;
    }

    /**
     * Puts the row at its place in the list and clips it to the list's viewport.
     *
     * @param y the row's own top, even when it is scrolled out of the view
     */
    void place(int x, int y, int width, int height, int viewportTop, int viewportBottom) {
        rowTop = y;
        rowHeight = height;
        int top = Math.max(y, viewportTop);
        int bottom = Math.min(y + height, viewportBottom);
        setX(x);
        setY(top);
        setWidth(width);
        setHeight(Math.max(0, bottom - top));
    }

    /** The width of the painted ring, when the clickable row is wider than the card it draws. */
    FocusRow withRingWidth(int ringWidth) {
        this.ringWidth = ringWidth;
        return this;
    }

    /** The row's own top, even when it is scrolled out of view; the tab scrolls a focused row back in with it. */
    int rowTop() {
        return rowTop;
    }

    int rowBottom() {
        return rowTop + rowHeight;
    }

    int rowHeight() {
        return rowHeight;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        action.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isActive() && event.isSelection()) {
            action.run();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void playDownSound(SoundManager sounds) {}

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (!isFocused() || getHeight() <= 0) {
            return;
        }
        // Two pixels of accent so the ring reads on top of the row's own border, in the screens' accent colour.
        Ui.border(g, getX(), getY(), ringWidth, getHeight(), Ui.ACCENT);
        if (ringWidth > 2 && getHeight() > 2) {
            Ui.border(g, getX() + 1, getY() + 1, ringWidth - 2, getHeight() - 2, Ui.ACCENT);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
        output.add(
                NarratedElementType.USAGE,
                Component.translatable(
                        isFocused() ? "narration.button.usage.focused" : "narration.button.usage.hovered"));
    }
}
