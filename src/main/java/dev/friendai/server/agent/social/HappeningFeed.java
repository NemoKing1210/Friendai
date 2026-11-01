package dev.friendai.server.agent.social;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.social.Happening;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Turns the facts the server already sees into journal entries the companion may mention: a death, a respawn, a
 * change of dimension. Only the owner's own facts land in the owner's journal; nothing here reads chat.
 *
 * <p>Registered once from the mod entrypoint. Every handler is cheap: a lookup, one bounded entry, one dirty flag on
 * the memory book, which is flushed by the tick pipeline like every other owner record.
 */
public final class HappeningFeed {
    /** When each player left, so a return can say how long they were gone; cleared on the next join. */
    private static final java.util.Map<UUID, Long> awaySince = new java.util.concurrent.ConcurrentHashMap<>();
    /** A return shorter than this is not worth a line. */
    private static final long RETURN_AFTER_MILLIS = 30L * 60L * 1_000L;

    private HappeningFeed() {}

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register(HappeningFeed::afterDeath);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> respawn(newPlayer));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> dimension(player, to));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ServerRuntimeRegistry.find(server).ifPresent(runtime -> joined(runtime, handler.getPlayer())));
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> awaySince.put(handler.getPlayer().getUUID(), System.currentTimeMillis()));
    }

    private static void afterDeath(LivingEntity victim, DamageSource source) {
        if (!(victim.level() instanceof ServerLevel level)) {
            return;
        }
        ServerRuntimeRegistry.find(level.getServer()).ifPresent(runtime -> {
            String killer = source.getEntity() == null
                    ? ""
                    : source.getEntity().getName().getString();
            if (victim instanceof ServerPlayer player) {
                record(
                        runtime,
                        player.getUUID(),
                        new Happening(
                                Happening.Kind.DEATH,
                                Happening.Importance.HIGH,
                                (int) level.getGameTime(),
                                "death:" + level.getGameTime(),
                                "you died" + (killer.isBlank() ? "" : " to " + killer)));
            } else if (victim instanceof CompanionEntity companion) {
                UUID owner = companion.ownerUuid();
                if (owner != null) {
                    record(
                            runtime,
                            owner,
                            new Happening(
                                    Happening.Kind.DEATH,
                                    Happening.Importance.NORMAL,
                                    (int) level.getGameTime(),
                                    "mydeath:" + level.getGameTime(),
                                    "I died" + (killer.isBlank() ? "" : " to " + killer)));
                }
            }
        });
    }

    /** Called from the advancement mixin for an award that actually happened. */
    public static void advancement(ServerPlayer player, net.minecraft.advancements.AdvancementHolder holder) {
        var display = holder.value().display();
        if (display.isEmpty() || !display.get().announceToChat()) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        ServerRuntimeRegistry.find(level.getServer())
                .ifPresent(runtime -> record(
                        runtime,
                        player.getUUID(),
                        new Happening(
                                Happening.Kind.ADVANCEMENT,
                                Happening.Importance.HIGH,
                                (int) level.getGameTime(),
                                "advancement:" + holder.id(),
                                "you earned " + display.get().title().getString())));
    }

    private static void respawn(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        ServerRuntimeRegistry.find(level.getServer()).ifPresent(runtime -> {
            var pos = player.blockPosition();
            record(
                    runtime,
                    player.getUUID(),
                    new Happening(
                            Happening.Kind.RESPAWN,
                            Happening.Importance.HIGH,
                            (int) level.getGameTime(),
                            "respawn:" + level.getGameTime(),
                            "you respawned at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
        });
    }

    private static void dimension(ServerPlayer player, net.minecraft.server.level.ServerLevel to) {
        ServerRuntimeRegistry.find(to.getServer())
                .ifPresent(runtime -> record(
                        runtime,
                        player.getUUID(),
                        new Happening(
                                Happening.Kind.DIMENSION,
                                Happening.Importance.NORMAL,
                                (int) to.getGameTime(),
                                "dimension:" + to.getGameTime(),
                                "you went to " + to.dimension().identifier().getPath())));
    }

    private static void joined(ServerRuntime runtime, ServerPlayer player) {
        Long left = awaySince.remove(player.getUUID());
        long away = left == null ? 0L : System.currentTimeMillis() - left;
        if (away < RETURN_AFTER_MILLIS) {
            // A quick reconnect is not news; only a real absence is worth a line.
            return;
        }
        long minutes = away / 60_000L;
        record(
                runtime,
                player.getUUID(),
                new Happening(
                        Happening.Kind.JOIN,
                        Happening.Importance.HIGH,
                        (int) runtime.server().overworld().getGameTime(),
                        "join:" + runtime.server().overworld().getGameTime(),
                        "you came back after " + minutes + " minutes"));
    }

    /**
     * Records one happening with the configured secrets masked. The journal is read back into the prompt, so this is
     * the single place where a player-controlled name or a message reaches it: redact here, not at every call site.
     * It is also the one place that counts the happening in the owner's activity window, so the periodic summary can
     * tell silence from nothing-to-say.
     */
    private static void record(ServerRuntime runtime, UUID owner, Happening happening) {
        book(runtime, owner).ifPresent(book -> {
            // Counted before the write: a repeated key replaces an entry, but the server still saw this happening.
            runtime.socialDirector().activityOf(owner).event();
            String text = runtime.sessions().redactor().apply(happening.text());
            book.recordHappening(
                    owner,
                    text.equals(happening.text())
                            ? happening
                            : new Happening(
                                    happening.kind(), happening.importance(), happening.tick(), happening.key(), text));
        });
    }

    /** The owner's book, only when that owner has a companion: a player without one has nobody to tell. */
    private static java.util.Optional<dev.friendai.features.memory.MemoryBook> book(ServerRuntime runtime, UUID owner) {
        return runtime.companions()
                .find(owner)
                .map(companion -> runtime.memory().book(owner));
    }
}
