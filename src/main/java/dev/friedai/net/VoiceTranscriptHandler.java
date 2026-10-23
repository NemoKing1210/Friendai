package dev.friedai.net;

import dev.friedai.brain.BrainEvent;
import dev.friedai.chat.CompanionRouting;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Server side of voice input: validates the transcript and hands it to the companion that should hear it. */
public final class VoiceTranscriptHandler {
    private static final RateLimiter LIMITER = new RateLimiter(1_000, System::currentTimeMillis);

    private VoiceTranscriptHandler() {}

    public static void handle(ServerPlayer player, String text) {
        if (text == null || text.isBlank() || text.length() > VoiceTranscriptC2SPayload.MAX_CHARS) {
            return;
        }
        if (!LIMITER.tryAcquire(player.getUUID())) {
            return;
        }
        var server = player.level().getServer();
        Optional<UUID> owner = CompanionRouting.route(player);
        if (owner.isEmpty()) {
            player.sendSystemMessage(Component.translatable("friedai.voice.need_companion"));
            return;
        }
        String name = player.getName().getString();
        String clean = text.strip();
        server.getPlayerList()
                .broadcastSystemMessage(Component.translatable("friedai.voice.chat_line", name, clean), false);
        CompanionRouting.deliver(player, owner.get(), clean, BrainEvent.Source.VOICE);
    }
}
