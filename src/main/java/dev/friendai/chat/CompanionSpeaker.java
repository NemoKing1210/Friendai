package dev.friendai.chat;

import dev.friendai.brain.Speaker;
import dev.friendai.core.Components;
import dev.friendai.net.CompanionSpeechS2CPayload;
import dev.friendai.text.UserMessage;
import java.util.UUID;
import java.util.function.Consumer;
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
    /** Passes the thinking state to the entity, whose client draws a thought bubble: no screen text is shown. */
    private final Consumer<Boolean> thinkingSink;
    /** The speaking entity and how far its voice carries, so clients can place it in the world. */
    private final Supplier<UUID> companionId;

    private final IntSupplier audibleRange;

    public CompanionSpeaker(
            MinecraftServer server,
            UUID owner,
            Supplier<String> name,
            IntSupplier maxReplyChars,
            Consumer<Boolean> thinkingSink,
            Supplier<UUID> companionId,
            IntSupplier audibleRange) {
        this.server = server;
        this.owner = owner;
        this.name = name;
        this.maxReplyChars = maxReplyChars;
        this.thinkingSink = thinkingSink;
        this.companionId = companionId;
        this.audibleRange = audibleRange;
    }

    @Override
    public void say(String text) {
        for (String chunk : ReplySplitter.split(text, maxReplyChars.getAsInt())) {
            server.getPlayerList().broadcastSystemMessage(chatLine(name.get(), chunk), false);
        }
    }

    /** The public chat line: only the name carries the derived colour, the reply keeps the default chat colour. */
    public static Component chatLine(String name, String text) {
        return Component.literal("<").append(CompanionNames.styled(name)).append(Component.literal("> " + text));
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
                addressee,
                companionId.get(),
                audibleRange.getAsInt());
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
        thinkingSink.accept(on);
    }

    @Override
    public void error(UserMessage message) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(Component.literal("[" + name.get() + "] ")
                    .append(Components.of(message))
                    .withStyle(ChatFormatting.RED));
        }
    }
}
