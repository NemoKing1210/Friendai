package dev.friendai.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

/** One page of the companion menu. It gets the content rectangle and adds its own widgets. */
interface MenuTab {
    interface Host {
        <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget);

        Font font();
    }

    void init(Host host, int left, int top, int width, int height);

    void render(GuiGraphicsExtractor g, Font font, int left, int top, int width, int height);

    default void tick() {}

    /** A left click inside the tab's content area, in screen coordinates. True when the tab handled it. */
    default boolean mouseClicked(double x, double y) {
        return false;
    }

    default boolean scroll(double amount) {
        return false;
    }
}
