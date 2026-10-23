package dev.friedai.core;

import dev.friedai.brain.BrainEvent;
import dev.friedai.chat.ConfirmationMatcher;
import dev.friedai.memory.MemoryStore;
import dev.friedai.memory.PendingTrust;
import dev.friedai.memory.TrustRequest;
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
    private final BrainService brains;
    private final Supplier<String> companionName;
    private final PendingTrust pending = new PendingTrust();

    public TrustConfirmations(
            MinecraftServer server, MemoryStore memory, BrainService brains, Supplier<String> companionName) {
        this.server = server;
        this.memory = memory;
        this.brains = brains;
        this.companionName = companionName;
    }

    /** Opens the owner's request (replacing an older one) and sends them the confirmation message. */
    public TrustRequest request(ServerPlayer owner, UUID target, String name, boolean trust) {
        TrustRequest request = pending.open(owner.getUUID(), target, name, trust, System.currentTimeMillis());
        send(owner.getUUID(), askMessage(request));
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
                            request.trust() ? "friedai.command.trust.added" : "friedai.command.untrust.removed",
                            request.targetName()));
            brains.post(
                    owner,
                    new BrainEvent.WorldNotice(request.trust()
                            ? "the owner confirmed: " + request.targetName() + " is now trusted"
                            : "the owner confirmed: " + request.targetName() + " is no longer trusted"));
        } else {
            send(owner, Component.translatable("friedai.trust.cancelled"));
            brains.post(
                    owner,
                    new BrainEvent.WorldNotice(
                            "the owner cancelled the trust change for " + request.targetName()));
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
        ConfirmationMatcher.Answer answer = ConfirmationMatcher.match(text, companionName.get());
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
            brains.post(
                    request.owner(),
                    new BrainEvent.WorldNotice("the trust request for " + request.targetName() + " expired"));
            send(request.owner(), Component.translatable("friedai.trust.expired", request.targetName()));
        }
    }

    private void send(UUID recipient, Component message) {
        ServerPlayer player = server.getPlayerList().getPlayer(recipient);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    private static Component askMessage(TrustRequest request) {
        Component ask = Component.translatable(
                request.trust() ? "friedai.trust.ask.add" : "friedai.trust.ask.remove", request.targetName());
        return Component.literal("[Friendai] ")
                .append(ask)
                .append(Component.literal(" "))
                .append(bracket(button(
                        "friedai.trust.button.confirm",
                        "friedai.trust.button.confirm.hover",
                        "/friedai pending confirm " + request.id())))
                .append(Component.literal(" "))
                .append(bracket(button(
                        "friedai.trust.button.cancel",
                        "friedai.trust.button.cancel.hover",
                        "/friedai pending cancel " + request.id())));
    }

    private static Component button(String key, String hoverKey, String command) {
        return Component.translatable(key)
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.translatable(hoverKey))));
    }

    private static Component bracket(Component inner) {
        return Component.literal("[").append(inner).append(Component.literal("]"));
    }
}
