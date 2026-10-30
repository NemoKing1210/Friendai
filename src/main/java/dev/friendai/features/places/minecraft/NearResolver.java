package dev.friendai.features.places.minecraft;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.places.NearRequest;
import dev.friendai.features.places.Place;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Turns the {@code near} and {@code x}/{@code y}/{@code z} arguments of a tool into the block to search around.
 * {@link NearRequest} decides which case the call asked for, so this class never re-decides the default: the
 * companion unless the call names a player, a place or a point.
 */
public final class NearResolver {
    /** The player must be this close to the companion for "near me" to be a place it can work around. */
    private static final double MAX_PLAYER_DISTANCE = 64.0;

    /** A resolved search origin, and whether the call named it rather than defaulting to the companion. */
    public record Origin(BlockPos pos, boolean explicit) {}

    private NearResolver() {}

    /**
     * Explicit coordinates win; otherwise {@code near} names a remembered place ({@code place:<name>}), the
     * speaker, or the companion. A place in another dimension is refused by {@link PlaceService#requireReachable}.
     */
    public static Origin resolve(PlaceService places, CompanionEntity c, Caller caller, JsonObject args) {
        return switch (NearRequest.parse(args)) {
            case NearRequest.Coordinates(int x, int y, int z) -> new Origin(new BlockPos(x, y, z), true);
            case NearRequest.Place(String name) -> {
                Place place = places.requireReachable(c, name, "a search");
                yield new Origin(
                        new BlockPos(
                                place.anchor().x(),
                                place.anchor().y(),
                                place.anchor().z()),
                        true);
            }
            case NearRequest.Companion() -> new Origin(c.blockPosition(), false);
            case NearRequest.Speaker() -> new Origin(speaker(c, caller), true);
        };
    }

    /**
     * The player who spoke. Without one there is nobody to search around, so the call is refused instead of falling
     * back to the owner: an away or offline owner must not decide where the companion works.
     */
    private static BlockPos speaker(CompanionEntity c, Caller caller) {
        if (caller.uuid() == null) {
            throw new IllegalArgumentException(
                    "nobody is speaking, so there is no-one to search around; use near \"self\" or give x, y and z");
        }
        ServerLevel level = (ServerLevel) c.level();
        ServerPlayer speaker = level.getServer().getPlayerList().getPlayer(caller.uuid());
        if (speaker == null) {
            throw new IllegalArgumentException("the player is not online; use near \"self\" or give x, y and z");
        }
        if (speaker.level() != c.level()) {
            throw new IllegalArgumentException(
                    "the player is in another dimension; use near \"self\" or give x, y and z");
        }
        if (speaker.distanceTo(c) > MAX_PLAYER_DISTANCE) {
            throw new IllegalArgumentException(
                    "the player is more than 64 blocks away; use near \"self\" or give x, y and z");
        }
        return speaker.blockPosition();
    }
}
