package dev.friendai.client.tts;

import dev.friendai.client.FriendaiClient;
import dev.friendai.client.audio.LocalTtsEngine;
import dev.friendai.client.audio.VoiceModelManager;
import dev.friendai.client.ui.ClientToasts;
import dev.friendai.client.voice.VoiceRuntime;
import dev.friendai.core.Components;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.speech.ResolvedTts;
import dev.friendai.features.speech.SpatialAudio;
import dev.friendai.features.speech.VoiceReadiness;
import dev.friendai.features.speech.tts.SpeechEngine;
import dev.friendai.features.speech.tts.SpeechGate;
import dev.friendai.features.speech.tts.SpeechQueue;
import dev.friendai.features.speech.tts.SpeechText;
import dev.friendai.features.speech.tts.TtsClip;
import dev.friendai.features.speech.tts.VoiceModel;
import dev.friendai.features.speech.tts.VoiceModels;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.VoiceConfig;
import dev.friendai.infrastructure.speech.CloudTtsEngine;
import dev.friendai.infrastructure.speech.TtsClient;
import dev.friendai.infrastructure.speech.VoiceHttp;
import dev.friendai.presentation.companion.ClientSessionId;
import dev.friendai.protocol.speech.CompanionSpeechView;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's speech output: one worker synthesizes and plays replies in order. The render thread only
 * resolves the settings, enqueues text, and shows toasts; everything else happens on the worker.
 * Spatial replies sample the world on the client tick and the worker reads the volatile mix snapshot.
 */
public final class SpeechRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/tts");
    private static final long ERROR_TOAST_INTERVAL_MS = 10_000;

    private static final Object LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "friendai-tts");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
    private static final TtsClient TTS = TtsClient.withBuiltins(new VoiceHttp(HTTP, Duration.ofSeconds(20)));
    private static final SpeechPlayback PLAYBACK = new SpeechPlayback();
    private static final VoiceModelManager MODELS = new VoiceModelManager(
            FabricLoader.getInstance()
                    .getGameDir()
                    .resolve("friendai")
                    .resolve("models")
                    .resolve("tts"),
            HTTP);

    private static final SpeechQueue<Chunk> QUEUE = new SpeechQueue<>();
    private static boolean draining;
    private static int generation;
    private static SpeechEngine cachedEngine;
    private static String cachedKey;
    private static long lastToastAt;
    private static String lastToastKey;
    private static volatile Plan playingPlan;
    private static volatile SpatialAudio.Mix mix = SpatialAudio.CENTER;
    private static ClientSessionId sessionId;
    private static boolean closed;

    private SpeechRuntime() {}

    /** One resolved reply's settings, including which companion speaks and how far its voice carries. */
    private record Plan(
            ResolvedTts settings, double volume, String device, boolean spatial, UUID companionId, int range) {}

    /** One queued utterance; the plan travels with it so a newer reply cannot repoint older chunks. */
    private record Chunk(String text, Plan plan, ClientSessionId sessionId) {}

    /** Where local voices are downloaded to and loaded from. */
    public static VoiceModelManager models() {
        return MODELS;
    }

    /** Queues a reply for speech. Called on the client thread. */
    public static void speak(String text) {
        ClientConfig.TtsSection cfg = section();
        if (cfg == null || !cfg.enabled) {
            return;
        }
        List<String> chunks = SpeechText.cleanAndSplit(text, cfg.maxChars);
        if (chunks.isEmpty()) {
            return;
        }
        enqueue(chunks, resolve(cfg, false, null, 0), currentSession());
    }

    /** Speaks the localized sample sentence, whether or not speech is enabled, and reports the outcome. */
    public static void test() {
        ClientConfig.TtsSection cfg = section();
        if (cfg == null) {
            return;
        }
        stop();
        Plan plan = resolve(cfg, false, null, 0);
        String sample = Component.translatable("friendai.voice.tts.sample").getString();
        int gen;
        ClientSessionId expectedSession;
        synchronized (LOCK) {
            gen = generation;
            expectedSession = sessionId;
        }
        WORKER.execute(() -> {
            try {
                playOne(sample, plan, gen, expectedSession);
                if (isCurrent(gen, expectedSession)) {
                    Minecraft.getInstance().execute(() -> {
                        if (isCurrent(gen, expectedSession)) {
                            ClientToasts.show(Component.translatable("friendai.config.voice.tts.test_result"));
                        }
                    });
                }
            } catch (UserFacingException e) {
                UserMessage message = e.userMessage();
                if (isCurrent(gen, expectedSession)) {
                    Minecraft.getInstance().execute(() -> {
                        if (isCurrent(gen, expectedSession)) ClientToasts.show(Components.of(message));
                    });
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Speech test failed: {}", e.toString());
                if (isCurrent(gen, expectedSession)) {
                    Minecraft.getInstance().execute(() -> {
                        if (isCurrent(gen, expectedSession)) {
                            ClientToasts.show(Components.of(UserMessage.of("friendai.voice.tts.error.failed")));
                        }
                    });
                }
            }
        });
    }

    /** Drops what is queued and cuts off what is playing. */
    public static void stop() {
        synchronized (LOCK) {
            generation++;
            QUEUE.clear();
        }
        playingPlan = null;
        mix = SpatialAudio.CENTER;
        PLAYBACK.stop();
    }

    /** Called when the settings change: releases the engine so the next reply uses the new one. */
    public static void invalidate() {
        stop();
        SpeechEngine closing;
        synchronized (LOCK) {
            closing = cachedEngine;
            cachedEngine = null;
            cachedKey = null;
        }
        if (closing != null) {
            // Closing a local engine waits for a synthesis holding it, so it must not run on the render thread.
            WORKER.execute(closing::close);
        }
    }

    public static void onConnect(ClientSessionId connectedSession) {
        synchronized (LOCK) {
            if (closed) return;
            sessionId = connectedSession;
            generation++;
            QUEUE.clear();
        }
        PLAYBACK.stop();
    }

    public static void onDisconnect(ClientSessionId disconnectedSession) {
        synchronized (LOCK) {
            if (!disconnectedSession.equals(sessionId)) return;
            sessionId = null;
        }
        invalidate();
    }

    /** Releases application-wide playback and worker resources at client shutdown. */
    public static void close() {
        synchronized (LOCK) {
            if (closed) return;
            closed = true;
            sessionId = null;
        }
        invalidate();
        PLAYBACK.close();
        WORKER.shutdown();
        try {
            WORKER.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A speech packet from the server, on the client thread, tied to its originating connection. */
    public static void onCompanionSpeech(ClientSessionId expectedSession, CompanionSpeechView payload) {
        if (!isCurrentSession(expectedSession)) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        ClientConfig.TtsSection cfg = section();
        if (cfg == null || !cfg.enabled) {
            return;
        }
        boolean speak = SpeechGate.shouldSpeak(
                payload.addressee(),
                minecraft.player.getUUID(),
                cfg.speakGuests,
                VoiceRuntime.mic().isRecording());
        if (!speak) {
            return;
        }
        Entity source = null;
        if (cfg.spatial) {
            source = minecraft.level.getEntity(payload.companionId());
            if (!(source instanceof CompanionEntity)) {
                // The companion is not in this level: the reply must not be heard here.
                return;
            }
        }
        List<String> chunks = SpeechText.cleanAndSplit(payload.text(), cfg.maxChars);
        if (chunks.isEmpty()) {
            return;
        }
        int range = Math.max(1, Math.min(128, payload.audibleRange()));
        if (source != null) {
            mix = mixFor(minecraft.player, source, range);
        } else {
            mix = SpatialAudio.CENTER;
        }
        enqueue(chunks, resolve(cfg, source != null, payload.companionId(), range), expectedSession);
    }

    /**
     * Keeps a playing spatial reply aligned with the player and the companion. Runs on the client thread;
     * the playback worker only reads the volatile {@link #mix} snapshot this writes.
     */
    public static void tick() {
        Plan plan = playingPlan;
        if (plan == null || plan.companionId() == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (!(minecraft.level.getEntity(plan.companionId()) instanceof CompanionEntity source)
                || source.isDeadOrDying()
                || source.isRemoved()) {
            stop();
            return;
        }
        if (plan.spatial()) {
            mix = mixFor(minecraft.player, source, plan.range());
        }
    }

    private static void enqueue(List<String> chunks, Plan plan, ClientSessionId expectedSession) {
        synchronized (LOCK) {
            if (closed || expectedSession == null || !expectedSession.equals(sessionId)) return;
            for (String chunk : chunks) {
                QUEUE.add(new Chunk(chunk, plan, expectedSession));
            }
            if (draining) {
                return;
            }
            draining = true;
        }
        WORKER.execute(SpeechRuntime::drain);
    }

    private static void drain() {
        try {
            while (true) {
                Chunk chunk;
                int gen;
                synchronized (LOCK) {
                    chunk = QUEUE.poll();
                    if (chunk == null) {
                        return;
                    }
                    if (closed || !chunk.sessionId().equals(sessionId)) continue;
                    gen = generation;
                }
                try {
                    playOne(chunk.text(), chunk.plan(), gen, chunk.sessionId());
                } catch (UserFacingException e) {
                    if (!isCurrent(gen, chunk.sessionId())) continue;
                    synchronized (LOCK) {
                        QUEUE.clear();
                    }
                    toastOnce(e.userMessage());
                } catch (RuntimeException e) {
                    if (!isCurrent(gen, chunk.sessionId())) continue;
                    LOGGER.warn("Speech failed: {}", e.toString());
                    synchronized (LOCK) {
                        QUEUE.clear();
                    }
                    toastOnce(UserMessage.of("friendai.voice.tts.error.failed"));
                } finally {
                    playingPlan = null;
                }
            }
        } finally {
            boolean resubmit;
            synchronized (LOCK) {
                resubmit = QUEUE.size() > 0;
                if (!resubmit) {
                    draining = false;
                }
            }
            if (resubmit) {
                WORKER.execute(SpeechRuntime::drain);
            }
        }
    }

    private static void playOne(String text, Plan plan, int gen, ClientSessionId expectedSession) {
        if (plan == null) {
            throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.failed"));
        }
        playingPlan = plan;
        ResolvedTts settings = plan.settings();
        boolean installed = true;
        if (settings.local()) {
            VoiceModel model = VoiceModels.byId(settings.voice())
                    .orElseThrow(() -> new UserFacingException(UserMessage.of("friendai.voice.tts.error.no_voice")));
            installed = MODELS.isInstalled(model);
        }
        UserMessage readiness = VoiceReadiness.tts(settings, installed);
        if (readiness != null) {
            throw new UserFacingException(readiness);
        }
        SpeechEngine engine = engine(settings);
        TtsClip clip;
        try {
            clip = engine.synthesize(text).join();
        } catch (CompletionException | CancellationException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UserFacingException uf) {
                throw uf;
            }
            LOGGER.warn("Speech synthesis failed: {}", cause == null ? e.toString() : cause.toString());
            throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.failed"));
        }
        synchronized (LOCK) {
            if (!isCurrentLocked(gen, expectedSession)) {
                return;
            }
        }
        playingPlan = plan;
        try {
            PLAYBACK.play(clip, plan.volume(), plan.device(), plan.spatial() ? SpeechRuntime::currentMix : null);
        } finally {
            playingPlan = null;
        }
    }

    private static SpatialAudio.Mix currentMix() {
        SpatialAudio.Mix current = mix;
        return current == null ? SpatialAudio.CENTER : current;
    }

    private static SpatialAudio.Mix mixFor(Player player, Entity source, int range) {
        Vec3 ears = player.getEyePosition();
        Vec3 mouth = source.getEyePosition();
        return SpatialAudio.mix(ears.x, ears.y, ears.z, player.getYRot(), mouth.x, mouth.y, mouth.z, range);
    }

    private static SpeechEngine engine(ResolvedTts settings) {
        String key = VoiceConfig.ttsCacheKey(settings);
        synchronized (LOCK) {
            if (cachedEngine != null && key.equals(cachedKey)) {
                return cachedEngine;
            }
            closeCachedLocked();
            SpeechEngine built;
            if (settings.local()) {
                VoiceModel model = VoiceModels.byId(settings.voice())
                        .orElseThrow(
                                () -> new UserFacingException(UserMessage.of("friendai.voice.tts.error.no_voice")));
                if (!MODELS.isInstalled(model)) {
                    throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.no_voice"));
                }
                // Runs inline on the speech worker, which is already off the client thread.
                built = new LocalTtsEngine(model, MODELS.dir(model), settings.speed(), Runnable::run);
            } else {
                built = new CloudTtsEngine(settings, TTS);
            }
            cachedEngine = built;
            cachedKey = key;
            return built;
        }
    }

    private static void closeCachedLocked() {
        if (cachedEngine != null) {
            cachedEngine.close();
            cachedEngine = null;
            cachedKey = null;
        }
    }

    private static void toastOnce(UserMessage message) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (message.key().equals(lastToastKey) && now - lastToastAt < ERROR_TOAST_INTERVAL_MS) {
                return;
            }
            lastToastKey = message.key();
            lastToastAt = now;
        }
        Minecraft.getInstance().execute(() -> ClientToasts.show(Components.of(message)));
    }

    private static ClientSessionId currentSession() {
        synchronized (LOCK) {
            return sessionId;
        }
    }

    private static boolean isCurrentSession(ClientSessionId expectedSession) {
        synchronized (LOCK) {
            return expectedSession != null && expectedSession.equals(sessionId) && !closed;
        }
    }

    private static boolean isCurrent(int expectedGeneration, ClientSessionId expectedSession) {
        synchronized (LOCK) {
            return isCurrentLocked(expectedGeneration, expectedSession);
        }
    }

    private static boolean isCurrentLocked(int expectedGeneration, ClientSessionId expectedSession) {
        return expectedGeneration == generation && !closed && java.util.Objects.equals(expectedSession, sessionId);
    }

    private static ClientConfig.TtsSection section() {
        ClientConfig config = FriendaiClient.clientConfig();
        return config == null || config.voice == null ? null : config.voice.tts;
    }

    private static Plan resolve(ClientConfig.TtsSection cfg, boolean spatial, UUID companionId, int range) {
        return new Plan(
                VoiceConfig.resolveTts(
                        cfg, Minecraft.getInstance().getLanguageManager().getSelected(), System::getenv),
                cfg.volume,
                cfg.outputDevice,
                spatial,
                companionId,
                range);
    }
}
