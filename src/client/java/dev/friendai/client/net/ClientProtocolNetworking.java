package dev.friendai.client.net;

import dev.friendai.FriendaiMod;
import dev.friendai.client.companion.session.CompanionClientSession;
import dev.friendai.client.debug.MovementDebugState;
import dev.friendai.client.runtime.ClientRuntime;
import dev.friendai.client.skin.SkinCache;
import dev.friendai.client.tts.SpeechRuntime;
import dev.friendai.presentation.companion.ClientSessionId;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolAccept;
import dev.friendai.protocol.ProtocolBootstrapCodec;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolFrame;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewCodec;
import dev.friendai.protocol.control.CompanionSnapshotProtocolCodec;
import dev.friendai.protocol.debug.MovementDebugProtocolViewCodec;
import dev.friendai.protocol.debug.PromptProtocolViewCodec;
import dev.friendai.protocol.instructions.SkillProtocolViewCodec;
import dev.friendai.protocol.memory.MemoryProtocolViewCodec;
import dev.friendai.protocol.places.PlaceProtocolViewCodec;
import dev.friendai.protocol.speech.CompanionSpeechProtocolViewCodec;
import dev.friendai.protocol.trading.TradeProtocolViewCodec;
import dev.friendai.transport.payload.ProtocolAcceptC2SPayload;
import dev.friendai.transport.payload.ProtocolHelloS2CPayload;
import dev.friendai.transport.payload.ProtocolViewS2CPayload;
import dev.friendai.transport.payload.SkinS2CPayload;
import dev.friendai.transport.protocol.CompanionSnapshotProtocolAdapter;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Registers client-side protocol receivers and applies negotiated, correlated views. */
public final class ClientProtocolNetworking {
    private ClientProtocolNetworking() {}

    public static void register(ClientRuntime runtime) {
        ClientPlayNetworking.registerGlobalReceiver(ProtocolHelloS2CPayload.TYPE, (payload, context) -> {
            ClientSessionId sessionId = runtime.sessionId();
            if (sessionId == null) return;
            context.client().execute(() -> receiveHello(runtime, sessionId, payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(ProtocolViewS2CPayload.TYPE, (payload, context) -> {
            ClientSessionId sessionId = runtime.sessionId();
            if (sessionId == null) return;
            context.client().execute(() -> receiveView(runtime, sessionId, payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(SkinS2CPayload.TYPE, (payload, context) -> {
            ClientSessionId sessionId = runtime.sessionId();
            if (sessionId == null) return;
            context.client().execute(() -> {
                if (runtime.allowsAppearance(sessionId)) SkinCache.onSkin(payload);
            });
        });
    }

    private static void receiveHello(
            ClientRuntime runtime, ClientSessionId sessionId, ProtocolHelloS2CPayload payload) {
        var result = runtime.acceptProtocolHello(sessionId, payload.data()).orElse(null);
        if (result == null) return;
        if (result instanceof dev.friendai.protocol.ProtocolClientRequestGate.Ready ready) {
            if (ClientPlayNetworking.canSend(ProtocolAcceptC2SPayload.TYPE)) {
                ProtocolAccept accept = ready.accept();
                ClientPlayNetworking.send(new ProtocolAcceptC2SPayload(ProtocolBootstrapCodec.encodeAccept(accept)));
                return;
            }
        }
        Minecraft.getInstance().disconnectFromWorld(Component.translatable("friendai.protocol.version_mismatch"));
    }

    private static void receiveView(ClientRuntime runtime, ClientSessionId sessionId, ProtocolViewS2CPayload payload) {
        DecodeResult<ProtocolFrame> decodedFrame = ProtocolViewCodec.decode(payload.data());
        if (!(decodedFrame instanceof DecodeResult.Valid<ProtocolFrame> validFrame)) {
            FriendaiMod.LOGGER.warn(
                    "Dropping a malformed or unsupported protocol view: {}", decodeReason(decodedFrame));
            return;
        }
        ProtocolFrame frame = validFrame.value();
        ProtocolFamily family = ProtocolFamily.fromId(frame.familyId()).orElse(null);
        if (family == null || !runtime.acceptsProtocolView(sessionId, family, frame.requestId())) return;
        CompanionClientSession session = runtime.activeCompanionSession();
        if (session == null || !session.id().equals(sessionId)) return;

        byte[] bytes = payload.data();
        switch (family) {
            case CONTROL -> {
                var decoded = CompanionSnapshotProtocolCodec.decode(bytes);
                var view = decodedView(decoded);
                if (view != null) {
                    session.viewState()
                            .onSnapshot(
                                    sessionId,
                                    CompanionSnapshotProtocolAdapter.fromProtocol(view.value()),
                                    System.currentTimeMillis());
                } else warnView("control", decoded);
            }
            case MEMORY -> {
                var decoded = MemoryProtocolViewCodec.decode(bytes);
                var view = decodedView(decoded);
                if (view != null) session.viewState().onMemoryView(sessionId, view.value());
                else warnView("memory", decoded);
            }
            case PLACES -> {
                var decoded = PlaceProtocolViewCodec.decode(bytes);
                var view = decodedView(decoded);
                if (view != null) session.viewState().onPlaceView(sessionId, view.value());
                else warnView("places", decoded);
            }
            case INSTRUCTIONS -> {
                var decoded = SkillProtocolViewCodec.decode(bytes);
                var view = decodedView(decoded);
                if (view != null) {
                    session.viewState().onSkillsView(sessionId, view.value().withResponseId(view.requestId()));
                } else warnView("instructions", decoded);
            }
            case TRADING -> {
                var decoded = TradeProtocolViewCodec.decode(bytes);
                var view = decodedView(decoded);
                if (view != null) session.viewState().onTradeView(sessionId, view.value());
                else warnView("trading", decoded);
            }
            case DEBUG -> receiveDebug(sessionId, frame, bytes, session);
            case SPEECH -> {
                var decoded = CompanionSpeechProtocolViewCodec.decode(bytes);
                var view = decodedView(decoded);
                if (view != null) SpeechRuntime.onCompanionSpeech(sessionId, view.value());
                else warnView("speech", decoded);
            }
        }
    }

    private static void receiveDebug(
            ClientSessionId sessionId, ProtocolFrame frame, byte[] bytes, CompanionClientSession session) {
        if (frame.operationId() == 128) {
            var decoded = PromptProtocolViewCodec.decode(bytes);
            var view = decodedView(decoded);
            if (view != null) session.viewState().onPrompt(sessionId, view.value());
            else warnView("prompt", decoded);
        } else if (frame.operationId() == 129) {
            var decoded = MovementDebugProtocolViewCodec.decode(bytes);
            var view = decodedView(decoded);
            if (view != null) MovementDebugState.onView(view.value());
            else warnView("movement debug", decoded);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ProtocolView<T> decodedView(DecodeResult<ProtocolView<T>> decoded) {
        return decoded instanceof DecodeResult.Valid<?> valid ? (ProtocolView<T>) valid.value() : null;
    }

    private static void warnView(String family, DecodeResult<?> decoded) {
        FriendaiMod.LOGGER.warn(
                "Dropping a malformed or unsupported {} protocol view: {}", family, decodeReason(decoded));
    }

    private static String decodeReason(DecodeResult<?> decoded) {
        if (decoded instanceof DecodeResult.Malformed<?> malformed)
            return malformed.reason().name();
        if (decoded instanceof DecodeResult.Unsupported<?> unsupported)
            return unsupported.reason().name();
        return "unknown";
    }
}
