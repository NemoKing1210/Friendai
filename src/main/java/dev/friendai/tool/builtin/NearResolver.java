package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.tool.Caller;
import dev.friendai.tool.NearTarget;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Turns the {@code near} and {@code x}/{@code y}/{@code z} arguments of a tool into the block to search around. */
final class NearResolver {
    /** The speaker must be this close to the companion for "near me" to be a place it can work around. */
    private static final double MAX_SPEAKER_DISTANCE = 64.0;

    private NearResolver() {}

    /** Explicit coordinates win; otherwise {@code near} picks the speaker or the companion. */
    static BlockPos resolve(CompanionEntity c, Caller caller, JsonObject args) {
        Optional<BlockPos> coordinates = ToolKit.optionalPos(args);
        if (coordinates.isPresent()) {
            return coordinates.get();
        }
        String raw = args.has("near") && !args.get("near").isJsonNull() ? args.get("near").getAsString() : null;
        if (NearTarget.parse(raw) == NearTarget.SELF) {
            return c.blockPosition();
        }
        ServerPlayer speaker = speaker(c, caller);
        if (speaker == null) {
            throw new IllegalArgumentException("the player is not online; use near \"self\" or give x, y and z");
        }
        if (speaker.level() != c.level()) {
            throw new IllegalArgumentException("the player is in another dimension; use near \"self\" or give x, y and z");
        }
        if (speaker.distanceTo(c) > MAX_SPEAKER_DISTANCE) {
            throw new IllegalArgumentException(
                    "the player is more than 64 blocks away; use near \"self\" or give x, y and z");
        }
        return speaker.blockPosition();
    }

    /** The calling player, or the owner when the caller carries no identity (a plan step has none). */
    private static ServerPlayer speaker(CompanionEntity c, Caller caller) {
        if (caller.uuid() != null && c.level() instanceof ServerLevel level) {
            return level.getServer().getPlayerList().getPlayer(caller.uuid());
        }
        return c.owner().orElse(null);
    }
}
