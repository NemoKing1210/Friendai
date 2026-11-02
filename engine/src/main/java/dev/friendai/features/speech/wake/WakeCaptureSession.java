package dev.friendai.features.speech.wake;

import dev.friendai.features.speech.PcmRingBuffer;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

/**
 * Ties a local detector to the pure wake state machine and a bounded pre-roll. It owns no microphone and no thread:
 * the client feeds the same PCM chunks it already captures, and receives one bounded utterance when capture ends.
 * Pre-wake audio is overwritten in the ring; disarm and finish clear it.
 */
public final class WakeCaptureSession {
    /** A local keyword detector; it must not upload audio. */
    @FunctionalInterface
    public interface Detector {
        boolean hit(byte[] pcm, int length);
    }

    private final WakeWordPolicy policy;
    private final PcmRingBuffer preRoll;
    private final Detector detector;
    private final ByteArrayOutputStream utterance = new ByteArrayOutputStream();
    private boolean capturing;

    public WakeCaptureSession(WakeWordPolicy policy, Detector detector, int preRollBytes) {
        this.policy = java.util.Objects.requireNonNull(policy);
        this.detector = java.util.Objects.requireNonNull(detector);
        this.preRoll = new PcmRingBuffer(preRollBytes);
    }

    public void arm() {
        policy.arm();
    }

    public void disarm() {
        policy.disarm();
        capturing = false;
        utterance.reset();
        preRoll.clear();
    }

    /** Feeds one captured chunk; returns a finished utterance on the call that ended capture. */
    public Optional<byte[]> feed(byte[] pcm, int length, boolean loud) {
        if (pcm == null || length <= 0) return Optional.empty();
        if (capturing) {
            utterance.write(pcm, 0, Math.min(length, pcm.length));
            if (loud) policy.levelAboveThreshold();
            else policy.levelBelowThreshold();
            if (!policy.capturing()) return finish();
            return Optional.empty();
        }
        preRoll.write(pcm, length);
        if (policy.state() == WakeWordPolicy.State.ARMED && detector.hit(pcm, Math.min(length, pcm.length))) {
            policy.detectorHit();
            if (policy.capturing()) {
                capturing = true;
                utterance.reset();
                byte[] pre = preRoll.snapshot();
                utterance.write(pre, 0, pre.length);
                utterance.write(pcm, 0, Math.min(length, pcm.length));
            }
        }
        return Optional.empty();
    }

    /** Advances the bounded timers; returns a finished utterance when silence or the cap ended capture. */
    public Optional<byte[]> tick() {
        boolean was = policy.capturing();
        policy.tick();
        if (was && !policy.capturing()) return finish();
        return Optional.empty();
    }

    public WakeWordPolicy.State state() {
        return policy.state();
    }

    public boolean capturing() {
        return capturing;
    }

    public byte[] preRoll() {
        return preRoll.snapshot();
    }

    private Optional<byte[]> finish() {
        capturing = false;
        byte[] out = utterance.toByteArray();
        utterance.reset();
        preRoll.clear();
        return out.length == 0 ? Optional.empty() : Optional.of(out);
    }
}
