package dev.friendai.server.memory;

import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.conversation.ConfirmationMatcher;
import dev.friendai.features.memory.PendingTrust;
import dev.friendai.features.memory.TrustRequest;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.persistence.memory.MemoryStore;
import dev.friendai.server.agent.CompanionSessions;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Trust changes that wait for the owner's own confirmation: the tool opens a request, then a chat button,
 * a command, or a plain yes/no by chat or voice applies it. The model is never part of the decision.
 */
public final class TrustConfirmations {
    private final MinecraftServer server;
    private final MemoryStore memory;
    private final CompanionSessions brains;
    private final Supplier<ServerConfig> config;
    private final PendingTrust pending = new PendingTrust();

    public TrustConfirmations(
            MinecraftServer server, MemoryStore memory, CompanionSessions brains, Supplier<ServerConfig> config) {
        this.server = server;
        this.memory = memory;
        this.brains = brains;
        this.config = config;
    }

    /** Opens the owner's request (replacing an older one) and sends them the confirmation message. */
    public TrustRequest request(ServerPlayer owner, UUID target, String name, boolean trust) {
        TrustRequest request = pending.open(owner.getUUID(), target, name, trust, System.currentTimeMillis());
        send(owner.getUUID(), askMessage(owner, request));
        return request;
    }

    /** Applies ({@code confirm}) or drops the request {@code id}. False when there is nothing to confirm. */
    public boolean apply(UUID owner, int id, boolean confirm) {
        long now = System.currentTimeMillis();
        Optional<TrustRequest> taken = pending.take(owner, id, now);
        if (taken.isEmpty()) {
            return false;
        }
        TrustRequest request = taken.get();
        if (confirm) {
            memory.book(owner).setTrusted(request.target(), request.targetName(), request.trust(), now);
            send(
                    owner,
                    Component.translatable(
                            request.trust() ? "friendai.command.trust.added" : "friendai.command.untrust.removed",
                            request.targetName()));
            brains.post(
                    owner,
                    new BrainEvent.WorldNotice(
                            request.trust()
                                    ? "the owner confirmed: " + request.targetName() + " is now trusted"
                                    : "the owner confirmed: " + request.targetName() + " is no longer trusted"));
        } else {
            send(owner, Component.translatable("friendai.trust.cancelled"));
            brains.post(
                    owner,
                    new BrainEvent.WorldNotice("the owner cancelled the trust change for " + request.targetName()));
        }
        return true;
    }

    /** Handles the owner's own yes/no line; false means the line is not a confirmation and routes as usual. */
    public boolean answer(ServerPlayer player, String text) {
        long now = System.currentTimeMillis();
        Optional<TrustRequest> current = pending.current(player.getUUID(), now);
        if (current.isEmpty()) {
            return false;
        }
        ServerConfig cfg = config.get();
        String name = CompanionManager.get(server)
                .find(player.getUUID())
                .map(companion -> companion.getName().getString())
                .filter(companionName -> !companionName.isBlank())
                .orElse(cfg.companion.name);
        ConfirmationMatcher.Answer answer = ConfirmationMatcher.match(text, name, cfg.chat.prefix);
        if (answer == ConfirmationMatcher.Answer.NONE) {
            return false;
        }
        return apply(player.getUUID(), current.get().id(), answer == ConfirmationMatcher.Answer.YES);
    }

    /** The owner's open request, empty when there is none or it has expired. */
    public Optional<TrustRequest> current(UUID owner) {
        return pending.current(owner, System.currentTimeMillis());
    }

    /** Drops expired requests, telling the owner and their brain. Call once per second. */
    public void tick(long now) {
        for (TrustRequest request : pending.expire(now)) {
            if (server.getPlayerList().getPlayer(request.owner()) == null) {
                // An owner who logged out must not have their companion woken for a dead request.
                continue;
            }
            brains.post(
                    request.owner(),
                    new BrainEvent.WorldNotice("the trust request for " + request.targetName() + " expired"));
            send(request.owner(), Component.translatable("friendai.trust.expired", request.targetName()));
        }
    }

    private void send(UUID recipient, Component message) {
        ServerPlayer player = server.getPlayerList().getPlayer(recipient);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    private Component askMessage(ServerPlayer owner, TrustRequest request) {
        String name = CompanionManager.get(server)
                .find(owner.getUUID())
                .map(companion -> companion.getName().getString())
                .filter(current -> !current.isBlank())
                .orElse(config.get().companion.name);
        Component ask = Component.translatable(
                request.trust() ? "friendai.trust.ask.add" : "friendai.trust.ask.remove", request.targetName());
        return Component.literal("[" + name + "] ")
                .append(ask)
                .append(Component.literal(" "))
                .append(bracket(button(
                        "friendai.trust.button.confirm",
                        "friendai.trust.button.confirm.hover",
                        "/friendai pending confirm " + request.id())))
                .append(Component.literal(" "))
                .append(bracket(button(
                        "friendai.trust.button.cancel",
                        "friendai.trust.button.cancel.hover",
                        "/friendai pending cancel " + request.id())));
    }

    private static Component button(String key, String hoverKey, String command) {
        return Component.translatable(key)
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.translatable(hoverKey))));
    }

    private static Component bracket(Component inner) {
        return Component.literal("[").append(inner).append(Component.literal("]"));
    }
}
