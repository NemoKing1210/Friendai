package dev.friedai.net;

import dev.friedai.brain.BrainEvent;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.entity.CompanionManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Server side of voice input: validates the transcript and hands it to the player's companion. */
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
        if (CompanionManager.get(server).find(player.getUUID()).isEmpty()) {
            player.sendSystemMessage(Component.translatable("friedai.voice.need_companion"));
            return;
        }
        String name = player.getName().getString();
        String clean = text.strip();
        server.getPlayerList().broadcastSystemMessage(Component.translatable("friedai.voice.chat_line", name, clean), false);
        FriedaiServerState.get(server).brains().post(
                player.getUUID(), new BrainEvent.PlayerSaid(name, clean, BrainEvent.Source.VOICE));
    }
}
