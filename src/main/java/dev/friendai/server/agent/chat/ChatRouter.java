package dev.friendai.server.agent.chat;

import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.features.conversation.*;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Sends chat messages addressed to a companion to the companion that should hear them. */
public final class ChatRouter {
    private ChatRouter() {}

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            var server = sender.level().getServer();
            if (server != null) {
                ServerRuntimeRegistry.find(server)
                        .ifPresent(runtime -> onChat(runtime, sender, message.signedContent()));
            }
        });
    }

    public static void onChat(ServerRuntime runtime, ServerPlayer player, String text) {
        boolean ownNear = CompanionRouting.ownWithinHearing(runtime, player);
        // A yes/no answer to a pending trust request is handled before any companion sees the line,
        // but only when the companion is close enough to have heard it.
        if (ownNear && runtime.trust().answer(player, text)) {
            return;
        }
        ServerConfig cfg = runtime.config();
        // A renamed companion answers to its current name; the configured name stays the default for a summon.
        String name = runtime.companions()
                .find(player.getUUID())
                .map(companion -> companion.getName().getString())
                .filter(current -> !current.isBlank())
                .orElse(cfg.companion.name);
        AddressDetector detector = new AddressDetector(name, cfg.chat.prefix, cfg.chat.respondToName, false);
        Optional<String> addressed = detector.match(text);
        if (addressed.isPresent()) {
            Optional<UUID> owner = CompanionRouting.route(runtime, player);
            if (owner.isPresent()) {
                CompanionRouting.deliver(runtime, player, owner.get(), addressed.get(), BrainEvent.Source.CHAT);
            } else if (runtime.companions().find(player.getUUID()).isPresent()) {
                player.sendSystemMessage(Component.translatable("friendai.companion.too_far"));
            }
            return;
        }
        // Unaddressed lines only reach the speaker's own companion, never someone else's,
        // and only when that companion is close enough to hear them.
        if (cfg.chat.listenAll && ownNear) {
            CompanionRouting.deliver(runtime, player, player.getUUID(), text, BrainEvent.Source.CHAT);
        }
    }
}
