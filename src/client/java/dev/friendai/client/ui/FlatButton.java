package dev.friendai.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The menu's only button: a vanilla {@link Button.Plain}, so it draws the game's widget sprite, label, focus and
 * hover exactly like any other screen, with two additions the menu needs — a status dot on the right (a toggle is
 * on, a delete is armed, this category is the current one) and a selected state for the navigation rail.
 */
final class FlatButton extends Button.Plain {
    private int status;
    private boolean selected;

    FlatButton(int x, int y, int width, int height, Component label, int status, Runnable action) {
        super(x, y, width, height, label, button -> action.run(), DEFAULT_NARRATION);
        this.status = status;
    }

    /** Draws as a rail entry: selected entries keep the highlighted vanilla sprite, and say so in narration. */
    FlatButton asTab(boolean selected) {
        this.selected = selected;
        setOverrideRenderHighlightedSprite(() -> this.selected);
        return this;
    }

    /** The dot on the right edge; it is an indicator, not a second button style. */
    void setStatus(int color) {
        this.status = color;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractContents(g, mouseX, mouseY, partialTick);
        if (status != 0) {
            Ui.dot(g, getX() + getWidth() - 6, getY() + (getHeight() - 4) / 2, 4, active ? status : Ui.DIM);
        }
    }

    @Override
    protected MutableComponent createNarrationMessage() {
        MutableComponent message = super.createNarrationMessage();
        if (selected) {
            message.append(", ").append(Component.translatable("friendai.menu.selected"));
        }
        return message;
    }
}
