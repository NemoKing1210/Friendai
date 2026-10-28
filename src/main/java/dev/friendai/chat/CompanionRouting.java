package dev.friendai.chat;

import dev.friendai.brain.BrainEvent;
import dev.friendai.config.ServerConfig;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Minecraft glue for {@link CompanionPicker}: collects candidates and delivers the message. */
public final class CompanionRouting {
    private CompanionRouting() {}

    /** The owner of the companion that should hear the sender, if any. */
    public static Optional<UUID> route(ServerPlayer sender) {
        var server = sender.level().getServer();
        ServerConfig cfg = FriendaiServerState.get(server).config();
        List<CompanionPicker.Candidate> candidates = new ArrayList<>();
        for (CompanionEntity companion : CompanionManager.get(server).all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null) {
                continue;
            }
            boolean sameDimension = companion.level() == sender.level();
            double distance = companion.position().distanceTo(sender.position());
            boolean ownerOnline = server.getPlayerList().getPlayer(owner) != null;
            candidates.add(new CompanionPicker.Candidate(owner, sameDimension, distance, ownerOnline));
        }
        return CompanionPicker.pick(sender.getUUID(), candidates, cfg.chat.hearingRadius, cfg.chat.guestsEnabled);
    }

    /** True when the sender's own companion is loaded, in the same dimension and within hearing. */
    public static boolean ownWithinHearing(ServerPlayer sender) {
        var server = sender.level().getServer();
        ServerConfig cfg = FriendaiServerState.get(server).config();
        return CompanionManager.get(server)
                .find(sender.getUUID())
                .filter(companion -> companion.level() == sender.level())
                .map(companion -> companion.position().distanceTo(sender.position()) <= cfg.chat.hearingRadius)
                .orElse(false);
    }

    /** Remembers that the sender spoke and posts the line to the owner's brain. */
    public static void deliver(ServerPlayer sender, UUID owner, String text, BrainEvent.Source source) {
        var server = sender.level().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        String name = sender.getName().getString();
        state.memory().book(owner).touch(sender.getUUID(), name, System.currentTimeMillis());
        state.brains().post(owner, new BrainEvent.PlayerSaid(sender.getUUID(), name, text, source));
    }
}
