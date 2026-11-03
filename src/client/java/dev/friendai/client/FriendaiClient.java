package dev.friendai.client;

import dev.friendai.client.companion.session.CompanionClientSession;
import dev.friendai.client.debug.MovementDebugRenderer;
import dev.friendai.client.net.ClientProtocolNetworking;
import dev.friendai.client.render.CompanionRenderer;
import dev.friendai.client.runtime.ClientRuntime;
import dev.friendai.client.tts.SpeechKeybind;
import dev.friendai.client.tts.SpeechRuntime;
import dev.friendai.client.ui.CompanionInventoryScreen;
import dev.friendai.client.ui.CompanionRequests;
import dev.friendai.client.ui.OutlineKeybind;
import dev.friendai.client.ui.PauseMenuButton;
import dev.friendai.client.voice.PushToTalkHandler;
import dev.friendai.client.voice.VoiceHud;
import dev.friendai.client.voice.VoiceRuntime;
import dev.friendai.client.voice.WakeWordHandler;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.menu.FriendaiMenus;
import dev.friendai.protocol.speech.SpeechRequest;
import dev.friendai.transport.payload.ProtocolAcceptC2SPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class FriendaiClient implements ClientModInitializer {
    private static final ClientRuntime RUNTIME = new ClientRuntime();

    public static ClientRuntime runtime() {
        return RUNTIME;
    }

    private static dev.friendai.debug.LogWriterService fileLog;

    private static void startFileLog() {
        try {
            dev.friendai.infrastructure.config.ClientConfig config = clientConfig();
            java.util.List<String> secrets = new java.util.ArrayList<>();
            for (dev.friendai.infrastructure.config.ClientConfig.CloudSettings cloud :
                    config.voice.stt.cloud.values()) {
                if (cloud.apiKey != null && !cloud.apiKey.isBlank()) secrets.add(cloud.apiKey);
            }
            for (dev.friendai.infrastructure.config.ClientConfig.CloudSettings cloud :
                    config.voice.tts.cloud.values()) {
                if (cloud.apiKey != null && !cloud.apiKey.isBlank()) secrets.add(cloud.apiKey);
            }
            fileLog = new dev.friendai.debug.LogWriterService(
                    net.fabricmc.loader.api.FabricLoader.getInstance()
                            .getGameDir()
                            .resolve("logs")
                            .resolve("friendai"),
                    dev.friendai.debug.LogWriterScope.CLIENT,
                    dev.friendai.foundation.diagnostics.LogNames.bootId(),
                    modVersion("friendai"),
                    modVersion("minecraft"),
                    uuid -> "",
                    config.logging.toSettings(),
                    dev.friendai.foundation.diagnostics.SecretRedactor.of(() -> secrets),
                    System::currentTimeMillis);
            dev.friendai.debug.LogSinkHub.add(fileLog);
        } catch (RuntimeException ignored) {
            // The client keeps working without a file log.
        }
    }

    private static void stopFileLog() {
        if (fileLog == null) return;
        dev.friendai.debug.LogSinkHub.remove(fileLog);
        fileLog.close();
        fileLog = null;
    }

    private static String modVersion(String id) {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(id)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public static dev.friendai.infrastructure.config.ClientConfig clientConfig() {
        return RUNTIME.configuration().load();
    }

    /** Returns the active menu session, creating one for an already-open integrated-world UI if necessary. */
    public static CompanionClientSession companionSession() {
        return RUNTIME.companionSession();
    }

    @Override
    public void onInitializeClient() {
        dev.friendai.debug.FriendaiLogAppender.install();
        startFileLog();
        EntityRenderers.register(FriendaiEntities.COMPANION, CompanionRenderer::new);
        MenuScreens.register(FriendaiMenus.TYPE, CompanionInventoryScreen::new);
        clientConfig();
        VoiceHud.register();
        MovementDebugRenderer.register();
        SpeechKeybind.register();
        OutlineKeybind.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> SpeechRuntime.tick());
        PauseMenuButton.register();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                RUNTIME.onConnect(ClientPlayNetworking.canSend(ProtocolAcceptC2SPayload.TYPE)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RUNTIME.onDisconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            stopFileLog();
            RUNTIME.close();
        });
        ClientProtocolNetworking.register(RUNTIME);

        PushToTalkHandler pushToTalk = new PushToTalkHandler(
                VoiceRuntime::stt,
                RUNTIME::sessionId,
                (sessionId, text) -> CompanionRequests.send(sessionId, new SpeechRequest.Transcript(text)));
        RUNTIME.registerPushToTalk(pushToTalk);
        pushToTalk.register();

        WakeWordHandler wake = new WakeWordHandler(
                VoiceRuntime.mic(),
                () -> clientConfig().voice.stt.inputDevice,
                WAKE_HOST,
                FriendaiClient::clientConfig,
                VoiceRuntime::stt,
                RUNTIME::sessionId,
                (sessionId, text) -> CompanionRequests.send(sessionId, new SpeechRequest.Transcript(text)),
                () -> VoiceRuntime.wakeDetector(clientConfig().voice.wake.phrase));
        pushToTalk.onCapture(wake::setPushToTalkCapturing);
        RUNTIME.registerWakeWord(wake);
        ClientTickEvents.END_CLIENT_TICK.register(client -> wake.tick());
    }

    /**
     * The game-side facts hands-free listening needs. It is kept out of {@link WakeWordHandler} so the pipeline can be
     * unit-tested without a Minecraft client: every condition that may stop the microphone lives here, reading the
     * live configuration rather than a copy of it.
     */
    private static final WakeWordHandler.Host WAKE_HOST = new WakeWordHandler.Host() {
        @Override
        public boolean canListen() {
            dev.friendai.infrastructure.config.ClientConfig config = clientConfig();
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            return RUNTIME.sessionId() != null
                    && minecraft.gui.screen() == null
                    && minecraft.player != null
                    && config.voice.stt.enabled
                    && config.voice.wake.enabled
                    && !SpeechRuntime.isSpeaking();
        }

        @Override
        public boolean isCurrent(dev.friendai.presentation.companion.ClientSessionId session, int generation) {
            return VoiceRuntime.isCurrentOperation(session, generation);
        }

        @Override
        public int nextGeneration() {
            return VoiceRuntime.nextCaptureGeneration();
        }

        @Override
        public void execute(Runnable task) {
            net.minecraft.client.Minecraft.getInstance().execute(task);
        }
    };
}
