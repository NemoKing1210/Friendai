package dev.friedai.client.ui;

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

    default boolean scroll(double amount) {
        return false;
    }
}
