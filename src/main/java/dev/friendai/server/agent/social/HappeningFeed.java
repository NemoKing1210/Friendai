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
    private HappeningFeed() {}

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register(HappeningFeed::afterDeath);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> respawn(newPlayer));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> dimension(player, to));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ServerRuntimeRegistry.find(server).ifPresent(runtime -> joined(runtime, handler.getPlayer())));
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
                book(runtime, player.getUUID())
                        .ifPresent(book -> book.recordHappening(
                                player.getUUID(),
                                new Happening(
                                        Happening.Kind.DEATH,
                                        Happening.Importance.HIGH,
                                        (int) level.getGameTime(),
                                        "death:" + level.getGameTime(),
                                        "you died" + (killer.isBlank() ? "" : " to " + killer))));
            } else if (victim instanceof CompanionEntity companion) {
                UUID owner = companion.ownerUuid();
                if (owner != null) {
                    book(runtime, owner)
                            .ifPresent(book -> book.recordHappening(
                                    owner,
                                    new Happening(
                                            Happening.Kind.DEATH,
                                            Happening.Importance.NORMAL,
                                            (int) level.getGameTime(),
                                            "mydeath:" + level.getGameTime(),
                                            "I died" + (killer.isBlank() ? "" : " to " + killer))));
                }
            }
        });
    }

    private static void respawn(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        ServerRuntimeRegistry.find(level.getServer())
                .ifPresent(runtime -> book(runtime, player.getUUID()).ifPresent(book -> {
                    var pos = player.blockPosition();
                    book.recordHappening(
                            player.getUUID(),
                            new Happening(
                                    Happening.Kind.RESPAWN,
                                    Happening.Importance.HIGH,
                                    (int) level.getGameTime(),
                                    "respawn:" + level.getGameTime(),
                                    "you respawned at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
                }));
    }

    private static void dimension(ServerPlayer player, net.minecraft.server.level.ServerLevel to) {
        ServerRuntimeRegistry.find(to.getServer())
                .ifPresent(runtime -> book(runtime, player.getUUID())
                        .ifPresent(book -> book.recordHappening(
                                player.getUUID(),
                                new Happening(
                                        Happening.Kind.DIMENSION,
                                        Happening.Importance.NORMAL,
                                        (int) to.getGameTime(),
                                        "dimension:" + to.getGameTime(),
                                        "you went to "
                                                + to.dimension().identifier().getPath()))));
    }

    private static void joined(ServerRuntime runtime, ServerPlayer player) {
        book(runtime, player.getUUID())
                .ifPresent(book -> book.recordHappening(
                        player.getUUID(),
                        new Happening(
                                Happening.Kind.JOIN,
                                Happening.Importance.LOW,
                                (int) runtime.server().overworld().getGameTime(),
                                "join:" + runtime.server().overworld().getGameTime(),
                                "you came back")));
    }

    /** The owner's book, only when that owner has a companion: a player without one has nobody to tell. */
    private static java.util.Optional<dev.friendai.features.memory.MemoryBook> book(ServerRuntime runtime, UUID owner) {
        return runtime.companions()
                .find(owner)
                .map(companion -> runtime.memory().book(owner));
    }
}
