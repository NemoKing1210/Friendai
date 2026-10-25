package dev.friendai.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A flat, tinted button that matches the companion menu's panels. */
final class FlatButton extends AbstractWidget {
    private final Runnable action;
    private int color;

    FlatButton(int x, int y, int width, int height, Component label, int color, Runnable action) {
        super(x, y, width, height, label);
        this.action = action;
        this.color = color;
    }

    void setColor(int color) {
        this.color = color;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        action.run();
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hot = active && isHoveredOrFocused();
        int tint = active ? color : Ui.DIM;
        g.fill(x, y, x + w, y + h, Ui.alpha(tint, hot ? 0x70 : active ? 0x40 : 0x20));
        Ui.border(g, x, y, w, h, Ui.alpha(tint, hot ? 0xFF : active ? 0xB0 : 0x60));
        var font = Minecraft.getInstance().font;
        String label = Ui.clip(font, getMessage().getString(), w - 8);
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - font.lineHeight) / 2 + 1,
                active ? Ui.TEXT : Ui.DIM);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
