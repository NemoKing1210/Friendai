package dev.friendai.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.friendai.protocol.control.ControlRequest;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

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
                toggle();
            }
        });
    }

    /** Flips the switch of the companion in the current world; the server confirms it on the action bar. */
    static void toggle() {
        CompanionRequests.send(new ControlRequest.ToggleOutline());
    }
}
