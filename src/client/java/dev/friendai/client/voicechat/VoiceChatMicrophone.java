package dev.friendai.client.voicechat;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import dev.friendai.client.voice.WakeWordHandler;
import dev.friendai.features.speech.StreamDecimator;
import dev.friendai.integration.voicechat.FriendaiVoicechatPlugin;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The wake word's microphone when Simple Voice Chat is installed: while the player talks in voice chat, the companion
 * hears the same audio, so there is no second capture of the device and no second key. While voice chat is not
 * connected, muted or switched off, or the owner chose the built-in path, the client's own microphone listens
 * instead, and the switch follows those changes on the next tick. Touches the optional mod's API, so it is loaded
 * only through {@link #around} and only when that mod is present.
 */
public final class VoiceChatMicrophone implements WakeWordHandler.Capture {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/voice");

    /** Voice chat sends nothing while the player is quiet; this long without a frame ends the transmission. */
    private static final long GAP_NANOS = 120_000_000L;
    /** A tenth of a second of 16 kHz 16-bit silence, the quiet chunk that marks the end of a transmission. */
    private static final byte[] SILENCE = new byte[3_200];
    /** How long a built-in microphone that refused to open is left alone before the next attempt. */
    private static final long RETRY_NANOS = 10_000_000_000L;

    private enum Source {
        NONE,
        BUILTIN,
        VOICE_CHAT
    }

    private final WakeWordHandler.Capture builtin;
    private final BooleanSupplier allowed;
    private final StreamDecimator decimator = new StreamDecimator();

    private volatile WakeWordHandler.ChunkSink sink;
    private volatile long lastFrameNanos;
    private volatile boolean gapMarked = true;
    private boolean monitoring;
    private int preRollSeconds;
    private Source source = Source.NONE;
    private long retryAtNanos;

    private VoiceChatMicrophone(WakeWordHandler.Capture builtin, BooleanSupplier allowed) {
        this.builtin = builtin;
        this.allowed = allowed;
    }

    /**
     * The wake word's microphone: voice chat's while {@code allowed} says the owner lets the companion use it and the
     * mod can carry it, {@code builtin} otherwise.
     */
    public static WakeWordHandler.Capture around(WakeWordHandler.Capture builtin, BooleanSupplier allowed) {
        return new VoiceChatMicrophone(builtin, allowed);
    }

    @Override
    public synchronized void startMonitoring(int preRollSeconds, WakeWordHandler.ChunkSink chunks) throws Exception {
        this.preRollSeconds = preRollSeconds;
        this.sink = chunks;
        monitoring = true;
        if (wantsVoiceChat()) {
            useVoiceChat();
        } else {
            // The handler reports a device that will not open, exactly as without voice chat.
            useBuiltin();
        }
    }

    @Override
    public synchronized void stop() {
        monitoring = false;
        sink = null;
        release();
    }

    @Override
    public synchronized void tick() {
        if (!monitoring) {
            return;
        }
        boolean wanted = wantsVoiceChat();
        if (wanted && source != Source.VOICE_CHAT) {
            release();
            useVoiceChat();
        } else if (!wanted && source != Source.BUILTIN && System.nanoTime() >= retryAtNanos) {
            release();
            try {
                useBuiltin();
            } catch (Exception unavailable) {
                LOGGER.warn("Could not open the microphone for the wake word: {}", unavailable.toString());
                retryAtNanos = System.nanoTime() + RETRY_NANOS;
            }
        }
        if (source == Source.VOICE_CHAT && !gapMarked && System.nanoTime() - lastFrameNanos > GAP_NANOS) {
            // The player stopped transmitting: quiet audio lets the utterance end instead of waiting for its cap.
            gapMarked = true;
            WakeWordHandler.ChunkSink chunks = sink;
            if (chunks != null) {
                chunks.onChunk(SILENCE, SILENCE.length);
            }
        }
    }

    /** One frame the player sent to voice chat, on that mod's audio thread. */
    private void onFrame(short[] frame48k) {
        WakeWordHandler.ChunkSink chunks = sink;
        if (chunks == null || frame48k == null) {
            return;
        }
        byte[] pcm;
        synchronized (decimator) {
            pcm = decimator.feed(frame48k);
        }
        lastFrameNanos = System.nanoTime();
        gapMarked = false;
        if (pcm.length > 0) {
            chunks.onChunk(pcm, pcm.length);
        }
    }

    private boolean wantsVoiceChat() {
        if (!allowed.getAsBoolean()) {
            return false;
        }
        VoicechatClientApi api = FriendaiVoicechatPlugin.clientApi();
        return api != null && !api.isDisabled() && !api.isMuted();
    }

    private void useVoiceChat() {
        synchronized (decimator) {
            decimator.reset();
        }
        gapMarked = true;
        FriendaiVoicechatPlugin.microphone(this::onFrame);
        source = Source.VOICE_CHAT;
    }

    private void useBuiltin() throws Exception {
        builtin.startMonitoring(preRollSeconds, (pcm, length) -> {
            WakeWordHandler.ChunkSink chunks = sink;
            if (chunks != null) {
                chunks.onChunk(pcm, length);
            }
        });
        source = Source.BUILTIN;
    }

    private void release() {
        FriendaiVoicechatPlugin.microphone(null);
        if (source == Source.BUILTIN) {
            builtin.stop();
        }
        source = Source.NONE;
    }
}
