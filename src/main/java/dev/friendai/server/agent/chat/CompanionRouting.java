package dev.friendai.server.agent.chat;

import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.conversation.*;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Minecraft glue for {@link CompanionPicker}: collects candidates and delivers the message. */
public final class CompanionRouting {
    private CompanionRouting() {}

    /** The owner of the companion that should hear the sender, if any. */
    public static Optional<UUID> route(ServerRuntime runtime, ServerPlayer sender) {
        ServerConfig cfg = runtime.config();
        List<CompanionPicker.Candidate> candidates = new ArrayList<>();
        for (CompanionEntity companion : runtime.companions().all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null) {
                continue;
            }
            boolean sameDimension = companion.level() == sender.level();
            double distance = companion.position().distanceTo(sender.position());
            boolean ownerOnline = runtime.server().getPlayerList().getPlayer(owner) != null;
            candidates.add(new CompanionPicker.Candidate(owner, sameDimension, distance, ownerOnline));
        }
        return CompanionPicker.pick(sender.getUUID(), candidates, cfg.chat.hearingRadius, cfg.chat.guestsEnabled);
    }

    /** True when the sender's own companion is loaded, in the same dimension and within hearing. */
    public static boolean ownWithinHearing(ServerRuntime runtime, ServerPlayer sender) {
        ServerConfig cfg = runtime.config();
        return runtime.companions()
                .find(sender.getUUID())
                .filter(companion -> companion.level() == sender.level())
                .map(companion -> companion.position().distanceTo(sender.position()) <= cfg.chat.hearingRadius)
                .orElse(false);
    }

    /** Remembers that the sender spoke and posts the line to the owner's brain. */
    public static void deliver(
            ServerRuntime runtime, ServerPlayer sender, UUID owner, String text, BrainEvent.Source source) {
        String name = sender.getName().getString();
        runtime.memory().book(owner).touch(sender.getUUID(), name, System.currentTimeMillis());
        runtime.socialDirector().ownerSpoke(owner, runtime.server().overworld().getGameTime());
        if (sender.getUUID().equals(owner)) {
            // The owner's own words may move the face; a guest line never does.
            runtime.companions().find(owner).ifPresent(companion -> companion.moodCue(text));
        }
        runtime.sessions().post(owner, new BrainEvent.PlayerSaid(sender.getUUID(), name, text, source));
    }
}
