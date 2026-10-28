package dev.friendai.net;

import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.chat.CompanionRouting;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionManager;
import dev.friendai.foundation.limits.RateLimiter;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Server side of voice input: validates the transcript and hands it to the companion that should hear it. */
public final class VoiceTranscriptHandler {
    private static final RateLimiter LIMITER = new RateLimiter(1_000, System::currentTimeMillis);

    private VoiceTranscriptHandler() {}

    public static void handle(ServerPlayer player, String text) {
        var server = player.level().getServer();
        if (server != null && !server.isSameThread()) {
            // Network threads call this directly; all world and memory access belongs to the server thread.
            server.execute(() -> handle(player, text));
            return;
        }
        if (text == null || text.isBlank() || text.length() > VoiceTranscriptC2SPayload.MAX_CHARS) {
            return;
        }
        String clean = text.strip();
        boolean ownNear = CompanionRouting.ownWithinHearing(player);
        // A yes/no answer to a pending trust request is handled before the line reaches any companion,
        // but only when the companion is close enough to have heard it.
        if (ownNear && FriendaiServerState.get(server).trust().answer(player, clean)) {
            return;
        }
        if (!LIMITER.tryAcquire(player.getUUID())) {
            return;
        }
        Optional<UUID> owner = CompanionRouting.route(player);
        if (owner.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    CompanionManager.get(server).find(player.getUUID()).isPresent()
                            ? "friendai.companion.too_far"
                            : "friendai.voice.need_companion"));
            return;
        }
        String name = player.getName().getString();
        server.getPlayerList()
                .broadcastSystemMessage(Component.translatable("friendai.voice.chat_line", name, clean), false);
        CompanionRouting.deliver(player, owner.get(), clean, BrainEvent.Source.VOICE);
    }
}
