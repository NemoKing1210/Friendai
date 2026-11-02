package dev.friendai.features.coordination.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.coordination.HelpRequest;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Owner-only help requests between companions. A request names one bounded piece of help and expires; acceptance
 * authorizes only that help, never the requester's queue, access level or resources. Nothing here transfers items.
 */
public final class CoordinationTools implements FeatureToolContributor {
    private static final int MIN_SECONDS = 10;
    private static final int MAX_SECONDS = 600;
    private static final int DEFAULT_SECONDS = 120;

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTool(
                tool(
                        "request_help",
                        "Ask another owner's companion for one bounded piece of help. The request expires and an "
                                + "acceptance authorizes only that help; it never grants access to your other work.",
                        SchemaBuilder.object()
                                .string("owner", "The other owner's player name")
                                .string("help", "One short bounded request, such as 'bring 8 oak logs'")
                                .integer("expires_in", "Seconds until the request expires", MIN_SECONDS, MAX_SECONDS)
                                .required("owner", "help")
                                .build(),
                        CoordinationTools::request),
                metadata("request_help"));
        registry.registerTool(
                tool(
                        "accept_help",
                        "Accept the open help request from another owner. It authorizes only the help that request "
                                + "names.",
                        SchemaBuilder.object()
                                .string("owner", "The requesting owner's player name")
                                .required("owner")
                                .build(),
                        CoordinationTools::accept),
                metadata("accept_help"));
        registry.registerTool(
                tool(
                        "decline_help",
                        "Decline the open help request from another owner. No work or transfer happens.",
                        SchemaBuilder.object()
                                .string("owner", "The requesting owner's player name")
                                .required("owner")
                                .build(),
                        CoordinationTools::decline),
                metadata("decline_help"));
    }

    private static Tool<CompanionEntity> tool(String name, String description, JsonObject schema, Action action) {
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
                return schema;
            }

            @Override
            public AccessLevel minAccess() {
                return AccessLevel.OWNER;
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return action.run(companion, args);
            }
        };
    }

    private static ToolResult request(CompanionEntity companion, JsonObject args) {
        Context context = context(companion);
        if (context.error != null) return ToolResult.error(context.error);
        String help = args.get("help").getAsString().strip();
        if (help.isEmpty() || help.length() > 200) {
            return ToolResult.error("help must be one short sentence");
        }
        Optional<ServerPlayer> target = player(context.server, args.get("owner").getAsString());
        if (target.isEmpty()) return ToolResult.error("that owner is not online");
        if (target.get().getUUID().equals(context.owner)) return ToolResult.error("that is your own owner");
        int seconds = args.has("expires_in") && !args.get("expires_in").isJsonNull()
                ? args.get("expires_in").getAsInt()
                : DEFAULT_SECONDS;
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            return ToolResult.error("expires_in must be between " + MIN_SECONDS + " and " + MAX_SECONDS + " seconds");
        }
        HelpRequest request = new HelpRequest(context.owner, target.get().getUUID(), help, context.now + seconds * 20L);
        return context.runtime.coordination().requestHelp(request)
                ? ToolResult.ok("asked " + target.get().getName().getString() + " for help: " + help)
                : ToolResult.error("there is already an open request with that owner");
    }

    private static ToolResult accept(CompanionEntity companion, JsonObject args) {
        Context context = context(companion);
        if (context.error != null) return ToolResult.error(context.error);
        Optional<ServerPlayer> from = player(context.server, args.get("owner").getAsString());
        if (from.isEmpty()) return ToolResult.error("that owner is not online");
        Optional<HelpRequest> accepted =
                context.runtime.coordination().acceptHelp(from.get().getUUID(), context.owner, context.now);
        return accepted.map(request -> ToolResult.ok("accepted help: " + request.help()))
                .orElseGet(() -> ToolResult.error("there is no open request from that owner"));
    }

    private static ToolResult decline(CompanionEntity companion, JsonObject args) {
        Context context = context(companion);
        if (context.error != null) return ToolResult.error(context.error);
        Optional<ServerPlayer> from = player(context.server, args.get("owner").getAsString());
        if (from.isEmpty()) return ToolResult.error("that owner is not online");
        return context.runtime.coordination().declineHelp(from.get().getUUID(), context.owner)
                ? ToolResult.ok("declined the request")
                : ToolResult.error("there is no open request from that owner");
    }

    private static Context context(CompanionEntity companion) {
        if (companion.ownerUuid() == null) return Context.error("I don't have an owner.");
        MinecraftServer server = companion.level().getServer();
        if (server == null) return Context.error("the world is not available");
        Optional<ServerRuntime> runtime = ServerRuntimeRegistry.find(server);
        if (runtime.isEmpty()) return Context.error("the server runtime is not available");
        return new Context(runtime.get(), server, companion.ownerUuid(), server.getTickCount());
    }

    private static Optional<ServerPlayer> player(MinecraftServer server, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(server.getPlayerList().getPlayerByName(name.strip()));
    }

    private static ToolMeta metadata(String name) {
        return switch (name) {
            case "request_help" ->
                new ToolMeta(
                        name,
                        ToolGroup.TRUST,
                        false,
                        "- Use request_help only when the owner asks another owner's companion for one bounded piece of help. A request expires and acceptance authorizes only that help.",
                        java.util.List.of(),
                        Cost.LOW);
            case "accept_help" ->
                new ToolMeta(
                        name,
                        ToolGroup.TRUST,
                        false,
                        "- Use accept_help when the owner accepts the named request. It authorizes only the help that request names.",
                        java.util.List.of(),
                        Cost.LOW);
            case "decline_help" ->
                new ToolMeta(
                        name,
                        ToolGroup.TRUST,
                        false,
                        "- Use decline_help when the owner declines a request. No work or transfer happens.",
                        java.util.List.of(),
                        Cost.LOW);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private record Context(ServerRuntime runtime, MinecraftServer server, UUID owner, long now, String error) {
        Context(ServerRuntime runtime, MinecraftServer server, UUID owner, long now) {
            this(runtime, server, owner, now, null);
        }

        static Context error(String message) {
            return new Context(null, null, null, 0L, message);
        }
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }
}
