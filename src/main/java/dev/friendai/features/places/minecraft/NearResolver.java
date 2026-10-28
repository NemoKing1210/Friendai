package dev.friendai.features.places.minecraft;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.NearTarget;
import dev.friendai.features.places.Place;
import dev.friendai.features.places.PlaceNames;
import dev.friendai.place.PlaceService;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Turns the {@code near} and {@code x}/{@code y}/{@code z} arguments of a tool into the block to search around. */
public final class NearResolver {
    /** The speaker must be this close to the companion for "near me" to be a place it can work around. */
    private static final double MAX_SPEAKER_DISTANCE = 64.0;

    private NearResolver() {}

    /**
     * Explicit coordinates win; otherwise {@code near} names a remembered place ({@code place:<name>}), the
     * speaker, or the companion. A place in another dimension is refused by
     * {@link PlaceService#requireReachable}.
     */
    public static BlockPos resolve(CompanionEntity c, Caller caller, JsonObject args) {
        Optional<BlockPos> coordinates = coordinates(args);
        if (coordinates.isPresent()) {
            return coordinates.get();
        }
        String raw = args.has("near") && !args.get("near").isJsonNull()
                ? args.get("near").getAsString()
                : null;
        Optional<String> placeName = PlaceNames.fromNear(raw);
        if (placeName.isPresent()) {
            Place place = PlaceService.requireReachable(c, placeName.get(), "a search");
            return new BlockPos(
                    place.anchor().x(), place.anchor().y(), place.anchor().z());
        }
        if (NearTarget.parse(raw) == NearTarget.SELF) {
            return c.blockPosition();
        }
        ServerPlayer speaker = speaker(c, caller);
        if (speaker == null) {
            throw new IllegalArgumentException("the player is not online; use near \"self\" or give x, y and z");
        }
        if (speaker.level() != c.level()) {
            throw new IllegalArgumentException(
                    "the player is in another dimension; use near \"self\" or give x, y and z");
        }
        if (speaker.distanceTo(c) > MAX_SPEAKER_DISTANCE) {
            throw new IllegalArgumentException(
                    "the player is more than 64 blocks away; use near \"self\" or give x, y and z");
        }
        return speaker.blockPosition();
    }

    /** Reads an explicit coordinate triple, rejecting partial triples. */
    public static Optional<BlockPos> coordinates(JsonObject args) {
        int present = 0;
        for (String name : new String[] {"x", "y", "z"}) {
            if (args.has(name) && !args.get(name).isJsonNull()) present++;
        }
        if (present == 0) return Optional.empty();
        if (present != 3) throw new IllegalArgumentException("give all of x, y, z or none");
        return Optional.of(new BlockPos(
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt()));
    }

    /** The calling player, or the owner when the caller carries no identity (a plan step has none). */
    private static ServerPlayer speaker(CompanionEntity c, Caller caller) {
        if (caller.uuid() != null && c.level() instanceof ServerLevel level) {
            return level.getServer().getPlayerList().getPlayer(caller.uuid());
        }
        return c.owner().orElse(null);
    }
}
