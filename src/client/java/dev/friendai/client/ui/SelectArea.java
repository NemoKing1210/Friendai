package dev.friendai.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;

/**
 * An invisible widget over text the tab draws itself, so the player can select it with the mouse (press, drag,
 * double-click) and use the copy and select-all shortcuts. Positions are in screen coordinates.
 */
final class SelectArea extends AbstractWidget {
    interface Handler {
        void press(double x, double y, boolean doubleClick);

        void drag(double x, double y);

        void release();

        /** A key while the area is focused. True when the handler used it. */
        boolean key(KeyEvent event);
    }

    private final Handler handler;

    SelectArea(int x, int y, int width, int height, Handler handler) {
        super(x, y, width, height, CommonComponents.EMPTY);
        this.handler = handler;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        handler.press(event.x(), event.y(), doubleClick);
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        handler.drag(event.x(), event.y());
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        handler.release();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return handler.key(event) || super.keyPressed(event);
    }

    @Override
    public void playDownSound(SoundManager sounds) {}

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {}

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
