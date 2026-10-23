package dev.friendai.client.voice;

import dev.friendai.FriendaiMod;
import dev.friendai.core.Components;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Shows the recording / transcribing / error indicator above the hotbar. */
public final class VoiceHud {
    private static final long ERROR_MILLIS = 3_000;
    private static final int RED = 0xFFFF5555;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int ORANGE = 0xFFFFAA00;

    private VoiceHud() {}

    public static void register() {
        HudElementRegistry.addLast(FriendaiMod.id("voice"), (graphics, delta) -> {
            VoiceState.Snapshot state = VoiceState.current();
            Component text;
            int color;
            switch (state.kind()) {
                case RECORDING -> {
                    text = Component.translatable("friendai.voice.hud.recording");
                    color = RED;
                }
                case TRANSCRIBING -> {
                    text = Component.translatable("friendai.voice.hud.transcribing");
                    color = WHITE;
                }
                case ERROR -> {
                    if (System.currentTimeMillis() - state.sinceMillis() > ERROR_MILLIS) {
                        return;
                    }
                    text = Components.of(state.message());
                    color = ORANGE;
                }
                default -> {
                    return;
                }
            }
            var font = Minecraft.getInstance().font;
            graphics.text(font, text.getVisualOrderText(), 10, graphics.guiHeight() - 59, color);
        });
    }
}
