package dev.friendai.client.tts;

import com.mojang.blaze3d.platform.InputConstants;
import dev.friendai.client.ui.ClientKeys;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/** Stops a reply that is already playing. Unbound by default: the player picks a key in Controls. */
public final class SpeechKeybind {
    private SpeechKeybind() {}

    public static void register() {
        KeyMapping key = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.friendai.stop_speaking",
                        InputConstants.Type.KEYBOARD,
                        InputConstants.UNKNOWN.getValue(),
                        ClientKeys.category()));
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (key.consumeClick()) {
                SpeechRuntime.stop();
            }
        });
    }
}
