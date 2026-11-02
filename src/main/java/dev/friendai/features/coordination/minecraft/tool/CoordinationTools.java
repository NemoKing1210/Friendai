package dev.friendai.features.coordination.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.coordination.HandoffRequest;
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
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

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
        registry.registerTool(
                tool(
                        "offer_handoff",
                        "Offer another owner's companion an exact item quantity at a named meeting point. Both owners "
                                + "must accept; acceptance authorizes only that transfer.",
                        SchemaBuilder.object()
                                .string("owner", "The recipient owner's player name")
                                .string("item", "Item id or #tag")
                                .integer("count", "Exact quantity", 1, HandoffRequest.MAX_COUNT)
                                .integer(
                                        "x", "Meeting x", -HandoffRequest.COORDINATE_MAX, HandoffRequest.COORDINATE_MAX)
                                .integer("y", "Meeting y", -64, 320)
                                .integer(
                                        "z", "Meeting z", -HandoffRequest.COORDINATE_MAX, HandoffRequest.COORDINATE_MAX)
                                .integer("expires_in", "Seconds until the offer expires", MIN_SECONDS, MAX_SECONDS)
                                .required("owner", "item", "count", "x", "y", "z")
                                .build(),
                        CoordinationTools::offerHandoff),
                metadata("offer_handoff"));
        registry.registerTool(
                tool(
                        "accept_handoff",
                        "Accept another owner's handoff offer. Both companions must be at the named meeting point "
                                + "and the source inventory is re-read before the exact quantity moves once.",
                        SchemaBuilder.object()
                                .string("owner", "The offering owner's player name")
                                .required("owner")
                                .build(),
                        CoordinationTools::acceptHandoff),
                metadata("accept_handoff"));
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

    private static ToolResult offerHandoff(CompanionEntity companion, JsonObject args) {
        Context context = context(companion);
        if (context.error != null) return ToolResult.error(context.error);
        Optional<ServerPlayer> target = player(context.server, args.get("owner").getAsString());
        if (target.isEmpty()) return ToolResult.error("that owner is not online");
        if (target.get().getUUID().equals(context.owner)) return ToolResult.error("that is your own owner");
        int seconds = args.has("expires_in") && !args.get("expires_in").isJsonNull()
                ? args.get("expires_in").getAsInt()
                : DEFAULT_SECONDS;
        String dimension = companion.level().dimension().identifier().toString();
        HandoffRequest request = new HandoffRequest(
                context.owner,
                target.get().getUUID(),
                args.get("item").getAsString(),
                args.get("count").getAsInt(),
                dimension,
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt(),
                context.now + seconds * 20L);
        return context.runtime.coordination().offerHandoff(request)
                ? ToolResult.ok("offered " + request.count() + " " + request.item() + " at the meeting point")
                : ToolResult.error("there is already an open handoff with that owner");
    }

    private static ToolResult acceptHandoff(CompanionEntity companion, JsonObject args) {
        Context context = context(companion);
        if (context.error != null) return ToolResult.error(context.error);
        Optional<ServerPlayer> from = player(context.server, args.get("owner").getAsString());
        if (from.isEmpty()) return ToolResult.error("that owner is not online");
        var request = context.runtime.coordination().takeHandoff(from.get().getUUID(), context.owner, context.now);
        if (request.isEmpty()) return ToolResult.error("there is no open handoff from that owner");
        HandoffRequest handoff = request.get();
        var source = context.runtime.companions().find(handoff.fromOwner()).orElse(null);
        var target = context.runtime.companions().find(handoff.toOwner()).orElse(null);
        if (source == null || target == null) return ToolResult.error("both companions must exist");
        if (!source.level().dimension().identifier().toString().equals(handoff.dimension())
                || !target.level().dimension().identifier().toString().equals(handoff.dimension())) {
            return ToolResult.error("both companions must be in the offered dimension");
        }
        if (!atMeeting(source, handoff) || !atMeeting(target, handoff)) {
            return ToolResult.error("both companions must be at the meeting point");
        }
        ItemMatcher matcher = ItemMatcher.parse(handoff.item()).orElse(null);
        if (matcher == null) return ToolResult.error("unknown item or tag: " + handoff.item());
        int have = InventoryOps.count(source.inventory(), matcher);
        if (have < handoff.count()) {
            return ToolResult.error("the source companion has only " + have + " " + handoff.item());
        }
        int moved = 0;
        for (ItemStack stack : InventoryOps.remove(source.inventory(), matcher, handoff.count())) {
            ItemStack leftover = target.inventory().addItem(stack);
            if (!leftover.isEmpty()) source.inventory().addItem(leftover);
            moved += stack.getCount() - leftover.getCount();
        }
        return ToolResult.ok("moved " + moved + " " + handoff.item() + " to the other companion");
    }

    private static boolean atMeeting(CompanionEntity companion, HandoffRequest request) {
        var at = companion.blockPosition();
        long dx = (long) at.getX() - request.x();
        long dy = (long) at.getY() - request.y();
        long dz = (long) at.getZ() - request.z();
        return dx * dx + dy * dy + dz * dz <= 25;
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
            case "offer_handoff" ->
                new ToolMeta(
                        name,
                        ToolGroup.TRUST,
                        false,
                        "- Use offer_handoff only when the owner asks to give an exact item quantity to another owner at a named meeting point. Both owners must accept.",
                        java.util.List.of(),
                        Cost.LOW);
            case "accept_handoff" ->
                new ToolMeta(
                        name,
                        ToolGroup.TRUST,
                        false,
                        "- Use accept_handoff when both companions are at the meeting point and both owners accepted. The exact quantity moves once.",
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
