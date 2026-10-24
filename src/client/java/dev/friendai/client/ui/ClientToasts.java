package dev.friendai.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** The mod's toast channel: one slot, so progress and errors replace each other instead of stacking. */
public final class ClientToasts {
    private static final SystemToast.SystemToastId ID = new SystemToast.SystemToastId();
    private static final Component TITLE = Component.translatable("friendai.config.title");

    private ClientToasts() {}

    /** Must be called on the client thread. */
    public static void show(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui == null) {
            return;
        }
        SystemToast.addOrUpdate(minecraft.gui.toastManager(), ID, TITLE, message);
    }
}
