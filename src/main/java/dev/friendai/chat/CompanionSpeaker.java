package dev.friendai.chat;

import dev.friendai.brain.Speaker;
import dev.friendai.core.Components;
import dev.friendai.text.UserMessage;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
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

    public CompanionSpeaker(
            MinecraftServer server,
            UUID owner,
            Supplier<String> name,
            IntSupplier maxReplyChars,
            Consumer<Boolean> thinkingSink) {
        this.server = server;
        this.owner = owner;
        this.name = name;
        this.maxReplyChars = maxReplyChars;
        this.thinkingSink = thinkingSink;
    }

    @Override
    public void say(String text) {
        for (String chunk : ReplySplitter.split(text, maxReplyChars.getAsInt())) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("<" + name.get() + "> " + chunk), false);
        }
    }

    @Override
    public void thinking(boolean on) {
        thinkingSink.accept(on);
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
