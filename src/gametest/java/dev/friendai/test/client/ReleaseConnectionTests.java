package dev.friendai.test.client;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.client.FriendaiClient;
import dev.friendai.client.skin.SkinCache;
import dev.friendai.client.ui.CompanionControlScreen;
import dev.friendai.client.ui.CompanionRequests;
import dev.friendai.client.voice.SpeechToText;
import dev.friendai.client.voice.VoiceRuntime;
import dev.friendai.client.voice.VoiceState;
import dev.friendai.client.voice.VoiceSubmission;
import dev.friendai.client.voice.WakeWordHandler;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.appearance.SkinHash;
import dev.friendai.features.appearance.SkinImage;
import dev.friendai.features.speech.PcmConverter;
import dev.friendai.features.speech.VoiceTranscript;
import dev.friendai.features.speech.wake.WakeWordPolicy;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.session.ClientSessionId;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.presentation.companion.CompanionViewState;
import dev.friendai.server.net.Operators;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.MinecraftServer;

/** Real negotiated packets on a loopback dedicated server, with fixture PCM and no physical devices. */
public final class ReleaseConnectionTests implements FabricClientGameTest {
    private static final int WAIT_TICKS = 500;
    private static final String DEBUG_LINE = "dedicated operator log fixture";
    private static final String OLD_TRANSCRIPT = "hello from the closed voice connection";
    private static final String NEW_TRANSCRIPT = "hello from the reconnected voice connection";
    private static final String OVERSIZED_TRANSCRIPT = "a".repeat(VoiceTranscript.MAX_WIRE_BYTES + 100);
    private static final String RATE_LIMITED_TRANSCRIPT = "hello while the voice gate is closed";
    private static final String NO_COMPANION_TRANSCRIPT = "hello after the companion went away";

    @Override
    public void runTest(ClientGameTestContext context) {
        Properties properties = new Properties();
        properties.setProperty("server-ip", "127.0.0.1");
        properties.setProperty("online-mode", "false");
        properties.setProperty("view-distance", "4");
        properties.setProperty("simulation-distance", "4");

        try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties)) {
            byte[] png = fixturePng();
            var valid = (SkinImage.Valid) SkinImage.validate(png);
            String hash = SkinHash.of(valid.png());
            ConcurrentLinkedQueue<String> turns = new ConcurrentLinkedQueue<>();
            LlmGateway gateway = (system, messages, tools) -> {
                messages.stream().map(Message::text).forEach(turns::add);
                return CompletableFuture.completedFuture(
                        new LlmResponse("", List.of(), StopReason.END, new Usage(0, 0)));
            };

            ClientSessionId before;
            VoiceTurn pending = new VoiceTurn();
            try {
                try (TestDedicatedServerConnection first = server.connect()) {
                    before = prepare(context, server, first, gateway);
                    verifyRoles(context, server, first);
                    context.runOnClient(mc -> require(
                            CompanionRequests.uploadSkin(before, png, true), "negotiated skin upload was rejected"));
                    server.waitFor(
                            s -> ServerRuntimeRegistry.getOrCreate(s)
                                    .companions()
                                    .find(first.getServerPlayer().getUUID())
                                    .filter(companion -> hash.equals(companion.getSkinHash()) && companion.isSlimSkin())
                                    .isPresent(),
                            WAIT_TICKS);
                    context.runOnClient(mc -> pending.start(context));
                    require(!pending.result.isDone(), "old recognition must be pending at disconnect");
                }

                await(
                        context,
                        "client lifecycle clears the first connection",
                        mc -> FriendaiClient.runtime().sessionId() == null);
                try (TestDedicatedServerConnection second = server.connect()) {
                    ClientSessionId after = prepare(context, server, second, gateway);
                    require(!before.equals(after), "reconnect did not replace the client session");

                    pending.result.complete(OLD_TRANSCRIPT);
                    await(
                            context,
                            "old voice callback has run on the reconnected client",
                            mc -> pending.completionHandled.isDone());
                    require(!pending.forwarded.get(), "the pending old voice turn sent a transcript after reconnect");

                    await(
                            context,
                            "client receives restored skin metadata and fetches the real texture",
                            mc -> wearingSkin(mc, hash) && SkinCache.textureFor(hash) != null);

                    VoiceTurn fresh = new VoiceTurn();
                    try {
                        context.runOnClient(mc -> fresh.start(context));
                        fresh.result.complete(NEW_TRANSCRIPT);
                        await(
                                context,
                                "new voice turn reaches the dedicated server's gateway",
                                mc -> turns.stream().anyMatch(text -> text.contains("by voice]: " + NEW_TRANSCRIPT)));
                        require(fresh.forwarded.get(), "fresh voice turn did not send its negotiated transcript");
                        require(
                                fresh.submission.get() == VoiceSubmission.Outcome.SENT,
                                "the current connection reported the fresh transcript as " + fresh.submission.get());
                        require(
                                turns.stream().noneMatch(text -> text.contains(OLD_TRANSCRIPT)),
                                "the dedicated server received stale speech");
                    } finally {
                        context.runOnClient(mc -> fresh.close());
                    }

                    // The client refuses a line that cannot fit the wire limit, so nothing is sent and the HUD says
                    // why.
                    expectRefusedTurn(
                            context,
                            turns,
                            OVERSIZED_TRANSCRIPT,
                            "friendai.voice.error.too_long",
                            VoiceSubmission.Outcome.TOO_LONG);
                    // The server admits one line per voice gate window and reports the refusal for the next one.
                    server.runOnServer(s -> runtime(s)
                            .protocolConnections()
                            .voiceLimiter()
                            .tryAcquire(second.getServerPlayer().getUUID()));
                    expectRefusedTurn(
                            context,
                            turns,
                            RATE_LIMITED_TRANSCRIPT,
                            "friendai.voice.error.rate_limited",
                            VoiceSubmission.Outcome.SENT);
                    // A speaker whose companion is gone learns it from the server's answer, not from silence. The gate
                    // is cleared first so this turn reaches the routing decision inside its window.
                    server.runOnServer(s -> {
                        var player = second.getServerPlayer();
                        runtime(s).protocolConnections().voiceLimiter().forget(player.getUUID());
                        require(
                                runtime(s).companions().dismiss(player),
                                "the fixture companion could not be dismissed");
                    });
                    expectRefusedTurn(
                            context,
                            turns,
                            NO_COMPANION_TRANSCRIPT,
                            "friendai.voice.need_companion",
                            VoiceSubmission.Outcome.SENT);
                }
            } finally {
                context.runOnClient(mc -> pending.close());
            }
        }
        System.out.println("FRIENDAI_CLIENT PASS: dedicated permissions, skin transfer and voice reconnect");
    }

    private static ClientSessionId prepare(
            ClientGameTestContext context,
            TestDedicatedServerContext server,
            TestDedicatedServerConnection connection,
            LlmGateway gateway) {
        await(
                context,
                "control and appearance capability negotiation",
                mc -> CompanionRequests.serverSupported()
                        && CompanionRequests.skinsSupported(
                                FriendaiClient.runtime().sessionId()));
        server.runOnServer(s -> {
            var player = connection.getServerPlayer();
            var runtime = ServerRuntimeRegistry.getOrCreate(s);
            runtime.sessions().setGatewayOverride(player.getUUID(), gateway);
            runtime.companions().summon(player);
            runtime.sessions().debug().logOf(player.getUUID()).add(LogKind.MANUAL, DEBUG_LINE);
        });
        return context.computeOnClient(mc -> FriendaiClient.runtime().sessionId());
    }

    private static void verifyRoles(
            ClientGameTestContext context,
            TestDedicatedServerContext server,
            TestDedicatedServerConnection connection) {
        server.runOnServer(s -> require(
                !Operators.isOperator(connection.getServerPlayer()),
                "the first dedicated connection is an ordinary player"));
        context.runOnClient(mc ->
                mc.setScreenAndShow(new CompanionControlScreen(new TitleScreen(), FriendaiClient.companionSession())));
        await(
                context,
                "ordinary dedicated menu snapshot",
                mc -> view().snapshot()
                        .filter(snapshot -> snapshot.present()
                                && !snapshot.operator()
                                && snapshot.newLog().isEmpty())
                        .isPresent());
        require(
                context.computeOnClient(
                        mc -> view().log().view(Set.of(LogKind.MANUAL)).isEmpty()),
                "ordinary player received operator log text");

        server.runOnServer(s -> {
            var player = connection.getServerPlayer();
            s.getPlayerList().op(player.nameAndId());
            require(Operators.isOperator(player), "could not grant operator permissions on the dedicated server");
        });
        await(
                context,
                "operator dedicated menu snapshot with diagnostic log",
                mc -> view().snapshot()
                        .filter(snapshot -> snapshot.operator()
                                && view().log().view(Set.of(LogKind.MANUAL)).stream()
                                        .anyMatch(entry -> entry.text().equals(DEBUG_LINE)))
                        .isPresent());
        context.runOnClient(mc -> mc.setScreenAndShow(null));
    }

    private static CompanionViewState view() {
        return FriendaiClient.companionSession().viewState();
    }

    private static ServerRuntime runtime(MinecraftServer server) {
        return ServerRuntimeRegistry.getOrCreate(server);
    }

    /**
     * Sends one fixture utterance and requires the HUD to name the refusal, with no turn reaching the brain. The local
     * outcome differs by scenario: the client refuses an oversized line itself, while a closed gate or a missing
     * companion can only be decided by the server.
     */
    private static void expectRefusedTurn(
            ClientGameTestContext context,
            ConcurrentLinkedQueue<String> turns,
            String transcript,
            String errorKey,
            VoiceSubmission.Outcome expectedSubmission) {
        VoiceTurn turn = new VoiceTurn();
        try {
            context.runOnClient(mc -> turn.start(context));
            turn.result.complete(transcript);
            try {
                await(context, "voice outcome " + errorKey, mc -> {
                    VoiceState.Snapshot state = VoiceState.current();
                    return state.kind() == VoiceState.Kind.ERROR
                            && state.message() != null
                            && errorKey.equals(state.message().key());
                });
            } catch (AssertionError timeout) {
                VoiceState.Snapshot last = VoiceState.current();
                throw new AssertionError(
                        timeout.getMessage() + " (voice state " + last.kind() + " " + last.message() + ", submission "
                                + turn.submission.get() + ")",
                        timeout);
            }
            require(
                    turn.submission.get() == expectedSubmission,
                    "the refused transcript was reported as " + turn.submission.get() + " instead of "
                            + expectedSubmission);
            require(
                    turns.stream().noneMatch(text -> text.contains(transcript)),
                    "a refused transcript reached the brain: " + transcript);
        } finally {
            context.runOnClient(mc -> turn.close());
        }
    }

    private static boolean wearingSkin(Minecraft minecraft, String hash) {
        if (minecraft.level == null) return false;
        for (var entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof CompanionEntity companion
                    && hash.equals(companion.getSkinHash())
                    && companion.isSlimSkin()) {
                return true;
            }
        }
        return false;
    }

    private static byte[] fixturePng() {
        BufferedImage image = new BufferedImage(SkinImage.SIZE, SkinImage.SIZE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(7, 13, 0xFF7BC9E8);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            require(ImageIO.write(image, "png", output), "PNG writer is unavailable");
            return output.toByteArray();
        } catch (IOException failure) {
            throw new AssertionError("could not encode the fixture skin", failure);
        }
    }

    private static void await(ClientGameTestContext context, String operation, Predicate<Minecraft> condition) {
        try {
            context.waitFor(condition, WAIT_TICKS);
        } catch (AssertionError timeout) {
            throw new AssertionError("Timed out waiting for " + operation, timeout);
        }
    }

    private static void require(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }

    /** Drives the real voice delivery code with fake microphone, keyword detector and recognition result. */
    private static final class VoiceTurn implements WakeWordHandler.Capture, SpeechToText {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final CompletableFuture<Void> completionHandled = new CompletableFuture<>();
        private final AtomicBoolean forwarded = new AtomicBoolean();
        private final AtomicReference<VoiceSubmission.Outcome> submission = new AtomicReference<>();
        private final AtomicBoolean keyword = new AtomicBoolean(true);
        private WakeWordHandler.ChunkSink sink;
        private WakeWordHandler handler;
        private boolean transcribing;

        void start(ClientGameTestContext context) {
            ClientConfig config = ClientConfig.defaults();
            config.voice.wake.enabled = true;
            handler = new WakeWordHandler(
                    this,
                    new ClientVoiceHost(context, completionHandled),
                    () -> config,
                    () -> this,
                    FriendaiClient.runtime()::sessionId,
                    (session, transcript, origin) -> {
                        VoiceSubmission.Outcome outcome = VoiceSubmission.submit(session, transcript, origin);
                        submission.set(outcome);
                        forwarded.set(outcome == VoiceSubmission.Outcome.SENT);
                        return outcome;
                    },
                    () -> samples -> keyword.getAndSet(false));
            handler.tick();
            require(sink != null, "wake capture did not arm on the real client session");
            float[] voiced = new float[2_400];
            Arrays.fill(voiced, 0.3f);
            byte[] chunk = PcmConverter.fromFloat(voiced);
            sink.onChunk(chunk, chunk.length);
            sink.onChunk(chunk, chunk.length);
            byte[] silence = new byte[chunk.length];
            sink.onChunk(silence, silence.length);
            for (int tick = 0; tick < WakeWordPolicy.Config.DEFAULTS.silenceTicks(); tick++) handler.tick();
            require(transcribing, "fixture microphone did not produce an utterance");
        }

        @Override
        public CompletableFuture<Void> startMonitoring(int preRollSeconds, WakeWordHandler.ChunkSink chunks) {
            sink = chunks;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void stop() {
            sink = null;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public CompletableFuture<String> transcribe(float[] samples16k, String language) {
            transcribing = samples16k.length >= 4_800;
            return result;
        }

        @Override
        public void close() {
            if (handler != null) handler.close();
            VoiceState.set(VoiceState.Kind.IDLE, null);
        }
    }

    private record ClientVoiceHost(ClientGameTestContext context, CompletableFuture<Void> completionHandled)
            implements WakeWordHandler.Host {
        @Override
        public boolean canListen() {
            return FriendaiClient.runtime().sessionId() != null && Minecraft.getInstance().player != null;
        }

        @Override
        public boolean isCurrent(ClientSessionId session, int generation) {
            return VoiceRuntime.isCurrentOperation(session, generation);
        }

        @Override
        public int nextGeneration() {
            return VoiceRuntime.nextCaptureGeneration();
        }

        @Override
        public void execute(Runnable task) {
            context.runOnClient(client -> {
                task.run();
                completionHandled.complete(null);
            });
        }
    }
}
