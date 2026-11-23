package dev.friendai.server.agent.chat;

import dev.friendai.agent.turn.Speaker;
import dev.friendai.common.Components;
import dev.friendai.features.conversation.Audience;
import dev.friendai.features.conversation.AudienceOrigin;
import dev.friendai.features.conversation.AudiencePolicy;
import dev.friendai.features.conversation.ReplySplitter;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.speech.CompanionSpeechProtocolViewCodec;
import dev.friendai.protocol.speech.CompanionSpeechStreamView;
import dev.friendai.protocol.speech.CompanionSpeechView;
import dev.friendai.server.companion.CompanionNames;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The companion's voice. Typed chat is public, unanswered speech is heard nearby, and push-to-talk stays private;
 * status and errors go only to the owner.
 */
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
        say(text, Audience.NEARBY, owner);
    }

    /**
     * One intent, two renditions: the chat line follows {@code chatFor} and speech follows {@code speechFor}, so a
     * public typed chat is read by everyone but only spoken around the companion, while a private turn keeps both
     * to its speaker.
     */
    private void emit(String text, Audience audience, UUID addressee) {
        emitChat(text, AudienceOrigin.chatFor(audience), addressee);
        if (text.isBlank()) {
            return;
        }
        CompanionSpeechView view = new CompanionSpeechView(
                clampUtf8(name.get(), CompanionSpeechProtocolViewCodec.MAX_COMPANION_BYTES),
                clampUtf8(text, CompanionSpeechProtocolViewCodec.MAX_TEXT_BYTES),
                addressee,
                companionId.get(),
                audibleRange.getAsInt());
        for (ServerPlayer player : recipients(AudienceOrigin.speechFor(audience), addressee)) {
            ServerRuntimeRegistry.getOrCreate(server)
                    .protocolConnections()
                    .sendView(player, ProtocolFamily.SPEECH, CompanionSpeechProtocolViewCodec.encode(view));
        }
    }

    /** The public chat line: only the name carries the derived colour, the reply keeps the default chat colour. */
    public static Component chatLine(String name, String text) {
        return Component.literal("<").append(CompanionNames.styled(name)).append(Component.literal("> " + text));
    }

    /** Offers the whole reply to the clients the audience reaches so they can speak it. */
    @Override
    public void say(String text, UUID addressee) {
        say(text, Audience.NEARBY, addressee);
    }

    @Override
    public void say(String text, Audience audience, UUID addressee) {
        emit(text, audience, addressee);
    }

    @Override
    public void sayChat(String text, Audience audience, UUID addressee) {
        emitChat(text, AudienceOrigin.chatFor(audience), addressee);
    }

    private void emitChat(String text, Audience audience, UUID addressee) {
        List<ServerPlayer> recipients = recipients(audience, addressee);
        if (recipients.isEmpty()) {
            return;
        }
        for (String chunk : ReplySplitter.split(text, maxReplyChars.getAsInt())) {
            Component line = chatLine(name.get(), chunk);
            for (ServerPlayer player : recipients) {
                player.sendSystemMessage(line, false);
            }
        }
    }

    @Override
    public void streamSentence(
            long streamId, long sessionId, long sequence, String text, boolean last, UUID addressee) {
        streamSentence(streamId, sessionId, sequence, text, last, Audience.NEARBY, addressee);
    }

    @Override
    public void streamSentence(
            long streamId,
            long sessionId,
            long sequence,
            String text,
            boolean last,
            Audience audience,
            UUID addressee) {
        if (text.isBlank() && !last) {
            return;
        }
        List<ServerPlayer> recipients = recipients(AudienceOrigin.speechFor(audience), addressee);
        if (recipients.isEmpty()) {
            return;
        }
        CompanionSpeechStreamView view = new CompanionSpeechStreamView(
                clampUtf8(name.get(), CompanionSpeechProtocolViewCodec.MAX_COMPANION_BYTES),
                clampUtf8(text, CompanionSpeechProtocolViewCodec.MAX_TEXT_BYTES),
                addressee,
                companionId.get(),
                audibleRange.getAsInt(),
                streamId,
                sessionId,
                sequence,
                last);
        for (ServerPlayer player : recipients) {
            ServerRuntimeRegistry.getOrCreate(server)
                    .protocolConnections()
                    .sendView(player, ProtocolFamily.SPEECH, CompanionSpeechProtocolViewCodec.encodeStream(view));
        }
    }

    /**
     * The exact clients a line reaches. PUBLIC is everyone, PRIVATE is the addressee alone, and NEARBY is the
     * same dimension within the companion's audible range.
     */
    private List<ServerPlayer> recipients(Audience audience, UUID addressee) {
        Entity source = companion();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        List<AudiencePolicy.Candidate> candidates = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            boolean sameDimension = source != null && player.level() == source.level();
            double distance =
                    source == null ? Double.MAX_VALUE : source.position().distanceTo(player.position());
            candidates.add(new AudiencePolicy.Candidate(player.getUUID(), sameDimension, distance));
        }
        Set<UUID> reached =
                new HashSet<>(AudiencePolicy.recipients(audience, addressee, candidates, audibleRange.getAsInt()));
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer player : players) {
            if (reached.contains(player.getUUID())) {
                result.add(player);
            }
        }
        return result;
    }

    private Entity companion() {
        UUID id = companionId.get();
        if (id == null || server == null) {
            return null;
        }
        for (var level : server.getAllLevels()) {
            var entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static String clampUtf8(String text, int maxBytes) {
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int width = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > maxBytes) break;
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    /** A line the mod composed itself: sent to the owner as a translatable component, so it follows their language. */
    @Override
    public void say(UserMessage message) {
        say(message, Audience.PRIVATE, owner);
    }

    @Override
    public void say(UserMessage message, Audience audience, UUID addressee) {
        for (ServerPlayer player : recipients(AudienceOrigin.chatFor(audience), addressee)) {
            player.sendSystemMessage(chatLine(name.get(), "").copy().append(Components.of(message)));
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
            player.sendSystemMessage(Component.literal("[" + name.get() + "] ")
                    .append(Components.of(message))
                    .withStyle(ChatFormatting.RED));
        }
    }
}
