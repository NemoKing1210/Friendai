package dev.friendai.client.voice;

import com.mojang.blaze3d.platform.InputConstants;
import dev.friendai.FriendaiMod;
import dev.friendai.client.FriendaiClient;
import dev.friendai.config.ClientConfig;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.PcmConverter;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Hold the key to record, release to transcribe and send the text on. */
public final class PushToTalkHandler {
    private static final int MIN_RECORDING_BYTES = 9_600; // 0.3 s at 16 kHz, 16-bit mono

    private final MicCapture mic = new MicCapture();
    private final Supplier<SpeechToText> stt;
    private final Consumer<String> onTranscript;
    private KeyMapping key;
    private boolean held;
    private boolean pressIgnored;

    public PushToTalkHandler(Supplier<SpeechToText> stt, Consumer<String> onTranscript) {
        this.stt = stt;
        this.onTranscript = onTranscript;
    }

    public void register() {
        KeyMapping.Category category = KeyMapping.Category.register(FriendaiMod.id("main"));
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.friendai.push_to_talk", InputConstants.KEY_V, category));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        boolean down = key.isDown();
        if (down && !held) {
            held = true;
            onPressed(mc);
        } else if (!down && held) {
            held = false;
            onReleased(mc);
        }
    }

    private void onPressed(Minecraft mc) {
        ClientConfig.VoiceSection voice = FriendaiClient.clientConfig().voice;
        pressIgnored = true;
        if (mc.gui.screen() != null || mc.player == null || !voice.enabled) {
            return;
        }
        if (!stt.get().isReady()) {
            VoiceState.set(VoiceState.Kind.ERROR, UserMessage.of("friendai.voice.error.no_model"));
            return;
        }
        try {
            mic.start(voice.inputDevice, voice.maxRecordSec);
            pressIgnored = false;
            VoiceState.set(VoiceState.Kind.RECORDING, null);
        } catch (Exception e) {
            FriendaiMod.LOGGER.warn("Could not open the microphone", e);
            VoiceState.set(VoiceState.Kind.ERROR, UserMessage.of("friendai.voice.error.no_mic"));
        }
    }

    private void onReleased(Minecraft mc) {
        if (pressIgnored || !mic.hasPendingRecording()) {
            return;
        }
        byte[] pcm = mic.stop();
        if (pcm.length < MIN_RECORDING_BYTES) {
            VoiceState.set(VoiceState.Kind.IDLE, null);
            return;
        }
        VoiceState.set(VoiceState.Kind.TRANSCRIBING, null);
        String language = FriendaiClient.clientConfig().voice.language;
        stt.get().transcribe(PcmConverter.toFloat(pcm), language).whenComplete((text, error) -> mc.execute(() -> {
            if (error != null) {
                FriendaiMod.LOGGER.warn("Speech recognition failed", error);
                VoiceState.set(VoiceState.Kind.ERROR, UserMessage.of("friendai.voice.error.failed"));
            } else {
                VoiceState.set(VoiceState.Kind.IDLE, null);
                if (text != null && !text.isBlank()) {
                    onTranscript.accept(text.strip());
                }
            }
        }));
    }
}
