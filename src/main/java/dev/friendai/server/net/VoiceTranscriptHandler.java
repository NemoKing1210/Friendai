package dev.friendai.server.net;

import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.features.conversation.AudienceOrigin;
import dev.friendai.features.speech.VoiceTranscript;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.speech.SpeechAcknowledgement;
import dev.friendai.protocol.speech.SpeechAcknowledgementProtocolViewCodec;
import dev.friendai.server.agent.chat.CompanionRouting;
import dev.friendai.server.agent.chat.LocalOrderExecutor;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server side of voice input: validates the transcript, hands it to the companion that should hear it and tells the
 * speaker what happened to it. Every admitted transcript gets exactly one acknowledgement carrying its request id, so
 * a silent HUD can never stand for an ignored command.
 */
public final class VoiceTranscriptHandler {

    private VoiceTranscriptHandler() {}

    public static void handle(AuthenticatedRequestContext context, String text) {
        ServerPlayer player = context.player();
        ServerRuntime runtime = context.runtime();
        var server = player.level().getServer();
        if (server != null && !server.isSameThread()) {
            // Network threads call this directly; all world and memory access belongs to the server thread.
            server.execute(() -> handle(context, text));
            return;
        }
        if (text == null || text.isBlank()) {
            // A blank line is never sent by the client; there is nothing to acknowledge.
            return;
        }
        String clean = VoiceTranscript.normalize(text);
        if (VoiceTranscript.wireBytes(clean) > VoiceTranscript.MAX_WIRE_BYTES) {
            acknowledge(context, SpeechAcknowledgement.Outcome.TOO_LONG);
            return;
        }
        boolean ownNear = CompanionRouting.ownWithinHearing(runtime, player);
        // A yes/no answer to a pending trust request is handled before the line reaches any companion,
        // but only when the companion is close enough to have heard it.
        if (ownNear && runtime.trust().answer(player, clean)) {
            acknowledge(context, SpeechAcknowledgement.Outcome.ACCEPTED);
            return;
        }
        if (!runtime.protocolConnections().voiceLimiter().tryAcquire(player.getUUID())) {
            acknowledge(context, SpeechAcknowledgement.Outcome.RATE_LIMITED);
            return;
        }
        Optional<UUID> owner = CompanionRouting.route(runtime, player);
        if (owner.isEmpty()) {
            boolean hasCompanion = runtime.companions().find(player.getUUID()).isPresent();
            acknowledge(
                    context,
                    hasCompanion ? SpeechAcknowledgement.Outcome.TOO_FAR : SpeechAcknowledgement.Outcome.NO_COMPANION);
            player.sendSystemMessage(Component.translatable(
                    hasCompanion ? "friendai.companion.too_far" : "friendai.voice.need_companion"));
            return;
        }
        // Accepted before the turn is delivered: the confirmation must not wait for a model reply.
        acknowledge(context, SpeechAcknowledgement.Outcome.ACCEPTED);
        String name = player.getName().getString();
        // Push-to-talk is private: the transcript line and the reply stay with the player who spoke.
        player.sendSystemMessage(Component.translatable("friendai.voice.chat_line", name, clean), false);
        // A spoken line gets the same exact-order shortcut as an addressed chat line, so a stop or follow phrase works
        // at once, without a model and with the provider offline. Anything the grammar does not claim still reaches
        // the brain exactly as before.
        if (LocalOrderExecutor.tryRun(runtime, player, owner.get(), clean)) {
            return;
        }
        CompanionRouting.deliver(runtime, player, owner.get(), clean, BrainEvent.Source.VOICE, AudienceOrigin.of(true));
    }

    private static void acknowledge(AuthenticatedRequestContext context, SpeechAcknowledgement.Outcome outcome) {
        byte[] frame =
                SpeechAcknowledgementProtocolViewCodec.encode(new SpeechAcknowledgement(outcome), context.requestId());
        context.runtime().protocolConnections().sendView(context.player(), ProtocolFamily.SPEECH, frame);
    }
}
