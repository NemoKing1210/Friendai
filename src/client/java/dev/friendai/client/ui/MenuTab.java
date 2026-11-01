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

        /**
         * The control the keyboard is on, or null. A tab whose content is taller than its rectangle scrolls the
         * control into view with this, so Tab can walk the whole page without a mouse.
         */
        GuiEventListener focused();
    }

    void init(Host host, int left, int top, int width, int height);

    void render(GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY);

    default void tick() {}

    /** A wheel turn with the pointer at ({@code x}, {@code y}). True when the tab scrolled. */
    default boolean scroll(double x, double y, double amount) {
        return false;
    }
}
