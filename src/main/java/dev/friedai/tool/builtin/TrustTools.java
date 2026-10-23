package dev.friedai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.memory.PlayerRecord;
import dev.friedai.memory.TrustRequest;
import dev.friedai.tool.AccessLevel;
import dev.friedai.tool.SchemaBuilder;
import dev.friedai.tool.ToolRegistry;
import dev.friedai.tool.ToolResult;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Trust changes the owner asks for in their own words. The tools only open a request: the owner confirms
 * it themselves with a button, a command, or a yes/no answer, never through the model.
 */
final class TrustTools {
    private TrustTools() {}

    static void register(ToolRegistry<CompanionEntity> registry) {
        registry.register(ToolKit.instantTool(
                "trust_player",
                "Ask the owner to confirm that a player becomes trusted (may make the companion act). "
                        + "Use only when the owner clearly asks for it.",
                schema(),
                AccessLevel.OWNER,
                (c, a) -> ask(c, a.get("name").getAsString(), true)));
        registry.register(ToolKit.instantTool(
                "untrust_player",
                "Ask the owner to confirm that a player is no longer trusted.",
                schema(),
                AccessLevel.OWNER,
                (c, a) -> ask(c, a.get("name").getAsString(), false)));
    }

    private static JsonObject schema() {
        return SchemaBuilder.object()
                .string("name", "The player's name")
                .required("name")
                .build();
    }

    private static ToolResult ask(CompanionEntity companion, String name, boolean trust) {
        String wanted = name == null ? "" : name.strip();
        UUID owner = companion.ownerUuid();
        if (owner == null) {
            return ToolResult.error("I don't have an owner.");
        }
        ServerPlayer ownerPlayer = companion.owner().orElse(null);
        if (ownerPlayer == null) {
            return ToolResult.error("the owner is offline");
        }
        var server = companion.level().getServer();
        FriedaiServerState state = FriedaiServerState.get(server);

        Optional<ServerPlayer> online = server.getPlayerList().getPlayers().stream()
                .filter(p -> p.getName().getString().equalsIgnoreCase(wanted))
                .findFirst();
        UUID target;
        String targetName;
        if (online.isPresent()) {
            target = online.get().getUUID();
            targetName = online.get().getName().getString();
        } else {
            Optional<PlayerRecord> known = state.memory().book(owner).findByName(wanted);
            if (known.isEmpty()) {
                return ToolResult.error("I don't know a player called " + wanted);
            }
            target = known.get().uuid();
            targetName = known.get().lastName();
        }
        if (target.equals(owner)) {
            return ToolResult.error("you are the owner already");
        }
        boolean trusted = state.memory().book(owner).isTrusted(target);
        if (trusted == trust) {
            return ToolResult.ok(trust ? targetName + " is already trusted" : targetName + " is not trusted");
        }
        TrustRequest request = state.trust().request(ownerPlayer, target, targetName, trust);
        return ToolResult.ok("asked the owner to confirm (request #" + request.id() + ", expires in 60 s)");
    }
}
