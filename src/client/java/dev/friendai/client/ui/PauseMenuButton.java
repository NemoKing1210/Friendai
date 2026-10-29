package dev.friendai.client.ui;

import dev.friendai.FriendaiMod;
import dev.friendai.client.FriendaiClient;
import dev.friendai.presentation.layout.PauseRowPlacement;
import dev.friendai.presentation.layout.Rect;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

/** Adds the companion button to the icon row of the pause menu. */
public final class PauseMenuButton {
    private static final int SIZE = 20;
    private static final int SPRITE_SIZE = 15;
    private static boolean failureLogged;

    private PauseMenuButton() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
                try {
                    add(pause, width);
                } catch (RuntimeException e) {
                    if (!failureLogged) {
                        failureLogged = true;
                        FriendaiMod.LOGGER.warn("Could not add the companion button to the pause menu", e);
                    }
                }
            }
        });
    }

    private static void add(PauseScreen pause, int screenWidth) {
        List<AbstractWidget> widgets = Screens.getWidgets(pause);
        List<Rect> icons = new ArrayList<>();
        for (AbstractWidget w : widgets) {
            if (w instanceof SpriteIconButton) {
                icons.add(new Rect(w.getX(), w.getY(), w.getWidth(), w.getHeight()));
            }
        }
        Rect spot = PauseRowPlacement.place(icons, screenWidth, SIZE);
        boolean supported = CompanionRequests.serverSupported();
        Component label =
                Component.translatable(supported ? "friendai.menu.button" : "friendai.menu.button_unavailable");
        SpriteIconButton button = SpriteIconButton.builder(
                        label,
                        b -> net.minecraft.client.Minecraft.getInstance()
                                .setScreenAndShow(new CompanionControlScreen(pause, FriendaiClient.companionSession())),
                        true)
                .width(spot.width())
                .sprite(FriendaiMod.id("companion"), SPRITE_SIZE, SPRITE_SIZE)
                .withTootip()
                .build();
        button.setPosition(spot.x(), spot.y());
        button.active = supported;
        widgets.add(button);
    }
}
