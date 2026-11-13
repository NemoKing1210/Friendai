package dev.friendai.client.voicechat;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.audiochannel.ClientEntityAudioChannel;
import dev.friendai.client.tts.SpeechBackend;
import dev.friendai.features.speech.tts.FramePacer;
import dev.friendai.features.speech.tts.PlaybackGate;
import dev.friendai.features.speech.tts.TtsClip;
import dev.friendai.features.speech.tts.VoiceFrames;
import dev.friendai.integration.voicechat.FriendaiVoicechatPlugin;

/**
 * Speaks a clip through a Simple Voice Chat entity channel bound to the companion. The voice then plays from an
 * OpenAL source at the entity, like a player's, so mods that shape voice-chat audio (Sound Physics Remastered)
 * apply to it. The channel is heard by this client only. Touches the optional mod's API, so it is loaded only
 * through {@link #create} and only when that mod is present.
 */
public final class VoiceChatPlayback implements SpeechBackend {
    /**
     * Frames a late sender may still hand over back to back. The mod queues at most 32 buffers per source and skips
     * audio once that queue is full, so a longer stall restarts the schedule instead.
     */
    private static final int MAX_LAG_FRAMES = 10;
    /** An empty frame is the mod's own end-of-talk mark: it resets the channel's receive state. */
    private static final short[] END_OF_TALK = new short[0];

    private final PlaybackGate gate;

    private VoiceChatPlayback(PlaybackGate gate) {
        this.gate = gate;
    }

    /** The backend whose clips a cancelled {@code gate} stops. */
    public static SpeechBackend create(PlaybackGate gate) {
        return new VoiceChatPlayback(gate);
    }

    /** True while a voice connection exists and the player has not switched voice chat sound off. */
    @Override
    public boolean ready() {
        VoicechatClientApi api = FriendaiVoicechatPlugin.clientApi();
        return api != null && !api.isDisabled();
    }

    @Override
    public boolean play(TtsClip clip, Request request, PlaybackGate.Permit permit) {
        VoicechatClientApi api = FriendaiVoicechatPlugin.clientApi();
        if (api == null || api.isDisabled() || request.companionId() == null) {
            return false;
        }
        VoiceFrames frames;
        try {
            frames = VoiceFrames.of(clip, request.volume());
        } catch (IllegalArgumentException unusableSampleRate) {
            // The built-in path owns the report for a clip no device can play.
            return false;
        }
        // The channel id is the entity's own id: the mod looks the entity up by it and plays from its eyes.
        ClientEntityAudioChannel channel = api.createEntityAudioChannel(request.companionId());
        channel.setDistance(request.range());
        FramePacer pacer = new FramePacer(VoiceFrames.FRAME_NANOS, MAX_LAG_FRAMES);
        boolean finished = true;
        for (int index = 0; index < frames.count(); index++) {
            if (!waitForTurn(pacer) || !gate.stillOpen(permit) || FriendaiVoicechatPlugin.clientApi() != api) {
                finished = false;
                break;
            }
            channel.play(frames.frame(index));
            pacer.sent();
        }
        channel.play(END_OF_TALK);
        if (finished) {
            // Holds the slot of the last frame, so the next clip continues without a gap or an overlap.
            waitForTurn(pacer);
        }
        return true;
    }

    /** Sleeps until the next frame is due. False when the thread was interrupted and should stop sending. */
    private static boolean waitForTurn(FramePacer pacer) {
        long wait = pacer.waitNanos(System.nanoTime());
        if (wait <= 0) {
            return true;
        }
        try {
            Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
