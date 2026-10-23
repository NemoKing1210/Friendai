package dev.friedai.chat;

import dev.friedai.brain.BrainEvent;
import dev.friedai.config.ServerConfig;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.entity.CompanionManager;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.level.ServerPlayer;

/** Sends chat messages addressed to a player's companion to its brain. */
public final class ChatRouter {
    private ChatRouter() {}

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> onChat(sender, message.signedContent()));
    }

    public static void onChat(ServerPlayer player, String text) {
        var server = player.level().getServer();
        if (CompanionManager.get(server).find(player.getUUID()).isEmpty()) {
            return;
        }
        FriedaiServerState state = FriedaiServerState.get(server);
        ServerConfig cfg = state.config();
        AddressDetector detector = new AddressDetector(
                cfg.companion.name, cfg.chat.prefix, cfg.chat.respondToName, cfg.chat.listenAll);
        detector.match(text).ifPresent(content -> state.brains().post(
                player.getUUID(),
                new BrainEvent.PlayerSaid(player.getUUID(), player.getName().getString(), content, BrainEvent.Source.CHAT)));
    }
}
