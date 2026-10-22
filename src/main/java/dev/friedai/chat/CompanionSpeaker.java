package dev.friedai.chat;

import dev.friedai.brain.Speaker;
import dev.friedai.core.Components;
import dev.friedai.text.UserMessage;
import java.util.UUID;
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
            player.sendSystemMessage(Component.translatable("friedai.companion.thinking", name.get()), true);
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
