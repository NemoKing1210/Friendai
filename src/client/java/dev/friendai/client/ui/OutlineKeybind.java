package dev.friendai.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.friendai.client.FriendaiClient;
import dev.friendai.infrastructure.config.ClientConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/** Toggles the companion outline. Unbound by default: the player picks a key in Controls. */
public final class OutlineKeybind {
    private OutlineKeybind() {}

    public static void register() {
        KeyMapping key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.friendai.companion_outline",
                InputConstants.Type.KEYBOARD,
                InputConstants.UNKNOWN.getValue(),
                ClientKeys.category()));
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (key.consumeClick()) {
                toggle(minecraft);
            }
        });
    }

    private static void toggle(net.minecraft.client.Minecraft minecraft) {
        ClientConfig config = FriendaiClient.clientConfig();
        config.social.outline = !config.social.outline;
        FriendaiClient.runtime().configuration().save(config);
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(
                    Component.translatable(config.social.outline ? "friendai.outline.on" : "friendai.outline.off"));
        }
    }
}
