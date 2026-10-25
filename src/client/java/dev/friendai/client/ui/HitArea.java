package dev.friendai.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;

/**
 * An invisible widget that makes a region the tab draws itself (a list, a row of chips) clickable. Being a real
 * screen widget it gets clicks through the same path as the buttons.
 */
final class HitArea extends AbstractWidget {
    /** Receives the click position in screen coordinates. */
    @FunctionalInterface
    interface Handler {
        void click(double x, double y);
    }

    private final Handler handler;

    HitArea(int x, int y, int width, int height, Handler handler) {
        super(x, y, width, height, CommonComponents.EMPTY);
        this.handler = handler;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        handler.click(event.x(), event.y());
    }

    @Override
    public void playDownSound(SoundManager sounds) {}

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {}

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
