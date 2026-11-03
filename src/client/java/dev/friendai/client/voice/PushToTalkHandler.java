package dev.friendai.client.voice;

import com.mojang.blaze3d.platform.InputConstants;
import dev.friendai.FriendaiMod;
import dev.friendai.client.FriendaiClient;
import dev.friendai.client.tts.SpeechRuntime;
import dev.friendai.client.ui.ClientKeys;
import dev.friendai.features.speech.PcmConverter;
import dev.friendai.features.speech.VoiceErrors;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.presentation.companion.ClientSessionId;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Hold the key to record, release to transcribe and send the text on. */
public final class PushToTalkHandler {
    private static final int MIN_RECORDING_BYTES = 9_600; // 0.3 s at 16 kHz, 16-bit mono

    private final MicCapture mic = VoiceRuntime.mic();
    private final Supplier<SpeechToText> stt;
    private final Supplier<ClientSessionId> session;
    private final BiConsumer<ClientSessionId, String> onTranscript;
    private KeyMapping key;
    private boolean held;
    private boolean pressIgnored;
    /** The capture generation this handler last started; a completion from an older one is dropped. */
    private int recording;

    /** Told true when push-to-talk takes the microphone and false when it lets go. */
    private Consumer<Boolean> captureHook = capturing -> {};

    private ClientSessionId recordingSession;
    private SpeechToText recordingRecognizer;
    private StreamingSpeechToText.StreamingSession recordingStream;

    public PushToTalkHandler(
            Supplier<SpeechToText> stt,
            Supplier<ClientSessionId> session,
            BiConsumer<ClientSessionId, String> onTranscript) {
        this.stt = stt;
        this.session = session;
        this.onTranscript = onTranscript;
    }

    /**
     * Hands-free listening stands down while the key is held: one microphone cannot serve two captures, and a
     * press is an explicit request that must win over the wake word.
     */
    public void onCapture(Consumer<Boolean> hook) {
        this.captureHook = Objects.requireNonNull(hook, "hook");
    }

    public void register() {
        key = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.friendai.push_to_talk", InputConstants.KEY_V, ClientKeys.category()));
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
        // Never record the companion's own voice: a press cuts any reply off before the mic opens.
        SpeechRuntime.stop();
        captureHook.accept(true);
        ClientConfig.SttSection voice = FriendaiClient.clientConfig().voice.stt;
        pressIgnored = true;
        ClientSessionId sessionId = session.get();
        if (sessionId == null || mc.gui.screen() != null || mc.player == null || !voice.enabled) {
            return;
        }
        VoiceRuntime.SttStatus status = VoiceRuntime.sttStatus();
        if (!status.ready()) {
            VoiceState.set(VoiceState.Kind.ERROR, status.problem());
            return;
        }
        SpeechToText recognizer;
        try {
            recognizer = stt.get();
        } catch (RuntimeException e) {
            FriendaiMod.LOGGER.warn("Could not initialize speech recognition", e);
            VoiceState.set(VoiceState.Kind.ERROR, UserMessage.of("friendai.voice.error.no_model"));
            return;
        }
        if (!recognizer.isReady()) {
            VoiceState.set(VoiceState.Kind.ERROR, UserMessage.of("friendai.voice.error.no_model"));
            return;
        }
        java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
        StreamingSpeechToText.StreamingSession stream = null;
        try {
            if (recognizer instanceof StreamingSpeechToText streaming && streaming.supportsStreaming()) {
                stream = streaming.startStreaming(
                        voice.language,
                        partial -> mc.execute(() -> {
                            int current = generation.get();
                            if (current != 0
                                    && recording == current
                                    && VoiceRuntime.isCurrentSession(sessionId)
                                    && VoiceState.current().kind() == VoiceState.Kind.RECORDING) {
                                VoiceState.setPartial(partial);
                            }
                        }));
            }
            startCapture(voice, stream);
            // Only a real recording supersedes an in-flight transcript; a failed press keeps it.
            recording = VoiceRuntime.nextCaptureGeneration();
            generation.set(recording);
            recordingSession = sessionId;
            recordingRecognizer = recognizer;
            recordingStream = stream;
            pressIgnored = false;
            VoiceState.set(VoiceState.Kind.RECORDING, null);
        } catch (Exception e) {
            FriendaiMod.LOGGER.warn("Could not open the microphone", e);
            if (stream != null) {
                stream.cancel();
            }
            VoiceState.set(VoiceState.Kind.ERROR, UserMessage.of("friendai.voice.error.no_mic"));
        }
    }

    private void startCapture(ClientConfig.SttSection voice, StreamingSpeechToText.StreamingSession stream)
            throws Exception {
        if (stream == null) {
            mic.start(voice.inputDevice, voice.maxRecordSec);
            return;
        }
        mic.start(voice.inputDevice, voice.maxRecordSec, stream::feed);
    }

    private void onReleased(Minecraft mc) {
        captureHook.accept(false);
        ClientSessionId sessionId = recordingSession;
        SpeechToText recognizer = recordingRecognizer;
        StreamingSpeechToText.StreamingSession stream = recordingStream;
        recordingSession = null;
        recordingRecognizer = null;
        recordingStream = null;
        RuntimeException captureFailure = mic.listenerFailure();
        boolean pending = mic.hasPendingRecording();
        byte[] pcm = mic.stop();
        if (pressIgnored || sessionId == null || !VoiceRuntime.isCurrentSession(sessionId) || !pending) {
            if (stream != null) {
                stream.cancel();
            }
            return;
        }
        if (captureFailure != null) {
            FriendaiMod.LOGGER.warn("Speech capture stopped early", captureFailure);
            if (stream != null) {
                stream.cancel();
            }
            VoiceState.set(VoiceState.Kind.ERROR, VoiceErrors.userMessage(captureFailure));
            return;
        }
        if (pcm.length < MIN_RECORDING_BYTES) {
            if (stream != null) {
                stream.cancel();
            }
            VoiceState.set(VoiceState.Kind.IDLE, null);
            return;
        }
        VoiceState.set(VoiceState.Kind.TRANSCRIBING, null);
        final int generation = recording;
        String language = FriendaiClient.clientConfig().voice.stt.language;
        java.util.concurrent.CompletableFuture<String> transcript =
                stream != null ? stream.finish() : recognizer.transcribe(PcmConverter.toFloat(pcm), language);
        transcript.whenComplete((text, error) -> mc.execute(() -> {
            if (!VoiceRuntime.isCurrentSession(sessionId) || generation != VoiceRuntime.captureGeneration()) {
                // A newer press owns the HUD state; if only a config change bumped the generation,
                // nobody else will clear the "transcribing" indicator.
                if (recording == generation) {
                    VoiceState.set(VoiceState.Kind.IDLE, null);
                }
                return;
            }
            if (error != null) {
                FriendaiMod.LOGGER.warn("Speech recognition failed", error);
                VoiceState.set(VoiceState.Kind.ERROR, VoiceErrors.userMessage(error));
            } else {
                VoiceState.set(VoiceState.Kind.IDLE, null);
                if (text != null && !text.isBlank()) {
                    onTranscript.accept(sessionId, text.strip());
                }
            }
        }));
    }

    /** Resets key and recording state when the owning connection closes. */
    public void onDisconnect(ClientSessionId sessionId) {
        captureHook.accept(false);
        if (sessionId.equals(recordingSession) || recordingSession == null) {
            held = false;
            pressIgnored = true;
            recordingSession = null;
            recordingRecognizer = null;
            StreamingSpeechToText.StreamingSession stream = recordingStream;
            recordingStream = null;
            if (stream != null) {
                stream.cancel();
            }
            mic.stop();
            VoiceState.set(VoiceState.Kind.IDLE, null);
        }
    }
}
