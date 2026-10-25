package dev.friendai.client.tts;

import dev.friendai.client.FriendaiClient;
import dev.friendai.client.ui.ClientToasts;
import dev.friendai.client.voice.VoiceRuntime;
import dev.friendai.config.ClientConfig;
import dev.friendai.core.Components;
import dev.friendai.net.CompanionSpeechS2CPayload;
import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.ResolvedTts;
import dev.friendai.voice.VoiceConfig;
import dev.friendai.voice.VoiceHttp;
import dev.friendai.voice.VoiceReadiness;
import dev.friendai.voice.tts.CloudTtsEngine;
import dev.friendai.voice.tts.LocalTtsEngine;
import dev.friendai.voice.tts.SpeechEngine;
import dev.friendai.voice.tts.SpeechGate;
import dev.friendai.voice.tts.SpeechQueue;
import dev.friendai.voice.tts.SpeechText;
import dev.friendai.voice.tts.TtsClient;
import dev.friendai.voice.tts.TtsClip;
import dev.friendai.voice.tts.VoiceModel;
import dev.friendai.voice.tts.VoiceModelManager;
import dev.friendai.voice.tts.VoiceModels;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's speech output: one worker synthesizes and plays replies in order. The render thread only
 * resolves the settings, enqueues text, and shows toasts; everything else happens on the worker.
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
            FabricLoader.getInstance().getGameDir().resolve("friendai").resolve("models").resolve("tts"), HTTP);

    private static final SpeechQueue<String> QUEUE = new SpeechQueue<>();
    private static boolean draining;
    private static int generation;
    private static Plan activePlan;
    private static SpeechEngine cachedEngine;
    private static String cachedKey;
    private static long lastToastAt;
    private static String lastToastKey;

    private SpeechRuntime() {}

    /** One resolved reply's settings: the provider configuration, the volume and the output device. */
    private record Plan(ResolvedTts settings, double volume, String device) {}

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
        enqueue(chunks, resolve(cfg));
    }

    /** Speaks the localized sample sentence, whether or not speech is enabled, and reports the outcome. */
    public static void test() {
        ClientConfig.TtsSection cfg = section();
        if (cfg == null) {
            return;
        }
        stop();
        Plan plan = resolve(cfg);
        String sample = Component.translatable("friendai.voice.tts.sample").getString();
        int gen;
        synchronized (LOCK) {
            activePlan = plan;
            gen = generation;
        }
        WORKER.execute(() -> {
            try {
                playOne(sample, plan, gen);
                Minecraft.getInstance().execute(
                        () -> ClientToasts.show(Component.translatable("friendai.config.voice.tts.test_result")));
            } catch (UserFacingException e) {
                UserMessage message = e.userMessage();
                Minecraft.getInstance().execute(() -> ClientToasts.show(Components.of(message)));
            } catch (RuntimeException e) {
                LOGGER.warn("Speech test failed: {}", e.toString());
                Minecraft.getInstance().execute(
                        () -> ClientToasts.show(Components.of(UserMessage.of("friendai.voice.tts.error.failed"))));
            }
        });
    }

    /** Drops what is queued and cuts off what is playing. */
    public static void stop() {
        synchronized (LOCK) {
            generation++;
            QUEUE.clear();
        }
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

    /** Called on disconnect. */
    public static void shutdown() {
        invalidate();
        PLAYBACK.close();
    }

    /** A speech packet from the server, on the client thread. */
    public static void onCompanionSpeech(CompanionSpeechS2CPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        ClientConfig.TtsSection cfg = section();
        if (cfg == null || !cfg.enabled) {
            return;
        }
        boolean speak = SpeechGate.shouldSpeak(
                payload.addressee(), minecraft.player.getUUID(), cfg.speakGuests, VoiceRuntime.mic().isRecording());
        if (speak) {
            speak(payload.text());
        }
    }

    private static void enqueue(List<String> chunks, Plan plan) {
        synchronized (LOCK) {
            activePlan = plan;
            for (String chunk : chunks) {
                QUEUE.add(chunk);
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
                String text;
                Plan plan;
                int gen;
                synchronized (LOCK) {
                    text = QUEUE.poll();
                    if (text == null) {
                        return;
                    }
                    plan = activePlan;
                    gen = generation;
                }
                try {
                    playOne(text, plan, gen);
                } catch (UserFacingException e) {
                    synchronized (LOCK) {
                        QUEUE.clear();
                    }
                    toastOnce(e.userMessage());
                } catch (RuntimeException e) {
                    LOGGER.warn("Speech failed: {}", e.toString());
                    synchronized (LOCK) {
                        QUEUE.clear();
                    }
                    toastOnce(UserMessage.of("friendai.voice.tts.error.failed"));
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

    private static void playOne(String text, Plan plan, int gen) {
        if (plan == null) {
            throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.failed"));
        }
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
            if (gen != generation) {
                return;
            }
        }
        PLAYBACK.play(clip, plan.volume(), plan.device());
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
                        .orElseThrow(() -> new UserFacingException(UserMessage.of("friendai.voice.tts.error.no_voice")));
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

    private static ClientConfig.TtsSection section() {
        ClientConfig config = FriendaiClient.clientConfig();
        return config == null || config.voice == null ? null : config.voice.tts;
    }

    private static Plan resolve(ClientConfig.TtsSection cfg) {
        return new Plan(
                VoiceConfig.resolveTts(
                        cfg, Minecraft.getInstance().getLanguageManager().getSelected(), System::getenv),
                cfg.volume,
                cfg.outputDevice);
    }
}
