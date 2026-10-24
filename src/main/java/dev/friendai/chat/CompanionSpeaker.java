package dev.friendai.chat;

import dev.friendai.brain.Speaker;
import dev.friendai.core.Components;
import dev.friendai.net.CompanionSpeechS2CPayload;
import dev.friendai.text.UserMessage;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** The companion's voice: speech goes to everyone in chat, status and errors only to the owner. */
public final class CompanionSpeaker implements Speaker {
    private final MinecraftServer server;
    private final UUID owner;
    private final Supplier<String> name;
    private final IntSupplier maxReplyChars;

    public CompanionSpeaker(MinecraftServer server, UUID owner, Supplier<String> name, IntSupplier maxReplyChars) {
        this.server = server;
        this.owner = owner;
        this.name = name;
        this.maxReplyChars = maxReplyChars;
    }

    @Override
    public void say(String text) {
        for (String chunk : ReplySplitter.split(text, maxReplyChars.getAsInt())) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("<" + name.get() + "> " + chunk), false);
        }
    }

    /**
     * Broadcasts the chat chunks as usual, then offers the whole reply to clients that installed the mod so
     * they can speak it. Vanilla clients never see the packet.
     */
    @Override
    public void say(String text, UUID addressee) {
        say(text);
        if (text.isBlank()) {
            return;
        }
        CompanionSpeechS2CPayload payload = new CompanionSpeechS2CPayload(
                clamp(name.get(), CompanionSpeechS2CPayload.MAX_NAME),
                clamp(text, CompanionSpeechS2CPayload.MAX_TEXT),
                addressee);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, CompanionSpeechS2CPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private static String clamp(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    @Override
    public void thinking(boolean on) {
        if (on) {
            showThinking();
        }
    }

    /** Shows (or refreshes) the "is thinking" action bar for the owner. */
    public void showThinking() {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(Component.translatable("friendai.companion.thinking", name.get()), true);
        }
    }

    @Override
    public void error(UserMessage message) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(
                    Component.literal("[" + name.get() + "] ").append(Components.of(message)).withStyle(ChatFormatting.RED));
        }
    }
}
