package dev.friedai.chat;

import dev.friedai.brain.BrainEvent;
import dev.friedai.config.ServerConfig;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.entity.CompanionManager;
import java.util.Optional;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.level.ServerPlayer;

/** Sends chat messages addressed to a companion to the companion that should hear them. */
public final class ChatRouter {
    private ChatRouter() {}

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> onChat(sender, message.signedContent()));
    }

    public static void onChat(ServerPlayer player, String text) {
        var server = player.level().getServer();
        FriedaiServerState state = FriedaiServerState.get(server);
        ServerConfig cfg = state.config();
        AddressDetector detector =
                new AddressDetector(cfg.companion.name, cfg.chat.prefix, cfg.chat.respondToName, false);
        Optional<String> addressed = detector.match(text);
        if (addressed.isPresent()) {
            CompanionRouting.route(player)
                    .ifPresent(owner ->
                            CompanionRouting.deliver(player, owner, addressed.get(), BrainEvent.Source.CHAT));
            return;
        }
        // Unaddressed lines only reach the speaker's own companion, never someone else's.
        if (cfg.chat.listenAll && CompanionManager.get(server).find(player.getUUID()).isPresent()) {
            CompanionRouting.deliver(player, player.getUUID(), text, BrainEvent.Source.CHAT);
        }
    }
}
