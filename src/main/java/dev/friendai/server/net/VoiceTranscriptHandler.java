package dev.friendai.server.net;

import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.server.agent.chat.CompanionRouting;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Server side of voice input: validates the transcript and hands it to the companion that should hear it. */
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
        if (text == null || text.isBlank() || text.length() > 500) {
            return;
        }
        String clean = text.strip();
        boolean ownNear = CompanionRouting.ownWithinHearing(runtime, player);
        // A yes/no answer to a pending trust request is handled before the line reaches any companion,
        // but only when the companion is close enough to have heard it.
        if (ownNear && runtime.trust().answer(player, clean)) {
            return;
        }
        if (!runtime.protocolConnections().voiceLimiter().tryAcquire(player.getUUID())) {
            return;
        }
        Optional<UUID> owner = CompanionRouting.route(runtime, player);
        if (owner.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    runtime.companions().find(player.getUUID()).isPresent()
                            ? "friendai.companion.too_far"
                            : "friendai.voice.need_companion"));
            return;
        }
        String name = player.getName().getString();
        server.getPlayerList()
                .broadcastSystemMessage(Component.translatable("friendai.voice.chat_line", name, clean), false);
        CompanionRouting.deliver(runtime, player, owner.get(), clean, BrainEvent.Source.VOICE);
    }
}
