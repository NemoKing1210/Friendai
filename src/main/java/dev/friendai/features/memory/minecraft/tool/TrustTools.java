package dev.friendai.features.memory.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.PlayerRecord;
import dev.friendai.features.memory.PlayerRef;
import dev.friendai.features.memory.TrustRequest;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Opens owner-confirmed trust requests for players named in the owner's words. */
public final class TrustTools implements FeatureToolContributor {
    private final Function<CompanionEntity, TrustAccess> trust;

    public TrustTools(TrustAccess trust) {
        this(ignored -> trust);
    }

    public TrustTools(Function<CompanionEntity, TrustAccess> trust) {
        this.trust = Objects.requireNonNull(trust);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTool(
                tool(
                        "trust_player",
                        "Ask the owner to confirm that a player becomes trusted (may make the companion act). "
                                + "Use only when the owner clearly asks for it.",
                        trust,
                        true),
                metadata("trust_player"));
        registry.registerTool(
                tool("untrust_player", "Ask the owner to confirm that a player is no longer trusted.", trust, false),
                metadata("untrust_player"));
    }

    private static Tool<CompanionEntity> tool(
            String name, String description, Function<CompanionEntity, TrustAccess> trust, boolean trustPlayer) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonObject schema() {
                return SchemaBuilder.object()
                        .string("name", "The player's name")
                        .required("name")
                        .build();
            }

            @Override
            public AccessLevel minAccess() {
                return AccessLevel.OWNER;
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return ask(trust.apply(companion), companion, args.get("name").getAsString(), trustPlayer);
            }
        };
    }

    private static ToolResult ask(TrustAccess trust, CompanionEntity companion, String name, boolean trustPlayer) {
        String wanted = name == null ? "" : name.strip();
        UUID owner = trust.ownerUuid(companion);
        if (owner == null) {
            return ToolResult.error("I don't have an owner.");
        }
        if (!trust.ownerOnline(companion)) {
            return ToolResult.error("the owner is offline");
        }

        Optional<PlayerRef> online = trust.findOnlinePlayer(wanted);
        UUID target;
        String targetName;
        if (online.isPresent()) {
            target = online.get().uuid();
            targetName = online.get().name();
        } else {
            Optional<PlayerRecord> known = trust.findKnownPlayer(owner, wanted);
            if (known.isEmpty()) {
                return ToolResult.error("I don't know a player called " + wanted);
            }
            target = known.get().uuid();
            targetName = known.get().lastName();
        }
        if (target.equals(owner)) {
            return ToolResult.error("you are the owner already");
        }

        boolean alreadyTrusted = trust.isTrusted(owner, target);
        if (alreadyTrusted == trustPlayer) {
            return ToolResult.ok(trustPlayer ? targetName + " is already trusted" : targetName + " is not trusted");
        }
        TrustRequest request = trust.request(companion, target, targetName, trustPlayer);
        return ToolResult.ok("asked the owner to confirm (request #" + request.id() + ", expires in 60 s)");
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(name, ToolGroup.TRUST, false, "", List.of(), Cost.LOW);
    }
}
