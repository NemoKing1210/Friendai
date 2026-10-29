package dev.friendai.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.trading.TradeQuery;
import dev.friendai.features.trading.minecraft.task.InspectTraderTask;
import dev.friendai.features.trading.minecraft.task.RefreshTradesTask;
import dev.friendai.features.trading.minecraft.tool.TradingTools;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Authenticated owner-only endpoint for the Traders menu. */
public final class TradeRequestHandler {
    private static final java.util.Map<UUID, UUID> ACTIVE_QUERY = new java.util.HashMap<>();
    private static final java.util.Map<UUID, Long> LAST_ACTION = new java.util.HashMap<>();

    private TradeRequestHandler() {}

    public static void handle(ServerPlayer player, TradeRequestC2SPayload request) {
        var state = dev.friendai.core.FriendaiServerState.get(player.level().getServer());
        if (!state.config().tasks.trading.enabled) return;
        UUID queryId;
        try {
            queryId = UUID.fromString(request.queryId());
        } catch (RuntimeException malformed) {
            return;
        }
        long now = player.level().getServer().overworld().getGameTime();
        synchronized (TradeRequestHandler.class) {
            Long previous = LAST_ACTION.get(player.getUUID());
            if (previous != null && now - previous < 20) return;
            LAST_ACTION.put(player.getUUID(), now);
            if (LAST_ACTION.size() > 4096) LAST_ACTION.entrySet().removeIf(entry -> now - entry.getValue() > 24_000);
            if (request.action() == TradeRequestC2SPayload.Action.SUBSCRIBE)
                ACTIVE_QUERY.put(player.getUUID(), queryId);
            else if (request.action() == TradeRequestC2SPayload.Action.CLOSE) ACTIVE_QUERY.remove(player.getUUID());
            else if (!queryId.equals(ACTIVE_QUERY.get(player.getUUID()))) return;
        }
        var book = state.trading().book(player.getUUID());
        switch (request.action()) {
            case SUBSCRIBE -> TradeViewStream.send(player, queryId, 0, null);
            case CLOSE -> {}
            case QUERY -> {
                if (request.itemId().isBlank()) TradeViewStream.send(player, queryId, request.page(), null);
                else {
                    TradeQuery.Direction[] directions = TradeQuery.Direction.values();
                    if (request.direction() < 0 || request.direction() >= directions.length) return;
                    try {
                        TradeQuery query = new TradeQuery(
                                directions[request.direction()],
                                request.itemId(),
                                request.components(),
                                TradeQuery.MAX_RESULTS);
                        TradeViewStream.send(player, queryId, request.page(), query);
                    } catch (IllegalArgumentException invalid) {
                    }
                }
            }
            case INSPECT ->
                withCompanion(
                        player,
                        raw -> {
                            UUID trader = UUID.fromString(raw);
                            CompanionManager.get(player.level().getServer())
                                    .find(player.getUUID())
                                    .ifPresent(companion -> companion
                                            .executionCoordinator()
                                            .start(new InspectTraderTask(trader), "traders_menu"));
                        },
                        request.traderId());
            case REFRESH ->
                withCompanion(
                        player,
                        raw -> {
                            var traders = Arrays.stream(raw.split(","))
                                    .map(UUID::fromString)
                                    .distinct()
                                    .limit(20)
                                    .toList();
                            if (!traders.isEmpty() && traders.size() <= 20)
                                CompanionManager.get(player.level().getServer())
                                        .find(player.getUUID())
                                        .ifPresent(companion -> companion
                                                .executionCoordinator()
                                                .start(new RefreshTradesTask(traders), "traders_menu"));
                        },
                        request.traderId());
            case FORGET -> {
                try {
                    if (!book.forget(UUID.fromString(request.traderId())))
                        player.sendSystemMessage(Component.translatable("friendai.menu.traders.forget_denied"));
                    else TradeViewStream.send(player, queryId, request.page(), null);
                } catch (RuntimeException malformed) {
                }
            }
            case PREVIEW, SUBMIT, WAIT, BARTER -> orderAction(player, request);
        }
    }

    public static synchronized void disconnect(UUID owner) {
        ACTIVE_QUERY.remove(owner);
        LAST_ACTION.remove(owner);
    }

    private static void orderAction(ServerPlayer player, TradeRequestC2SPayload request) {
        CompanionManager.get(player.level().getServer()).find(player.getUUID()).ifPresent(companion -> {
            JsonObject args;
            try {
                var parsed = JsonParser.parseString(request.components());
                if (!parsed.isJsonObject()) return;
                args = parsed.getAsJsonObject();
            } catch (RuntimeException malformed) {
                return;
            }
            Caller caller = new Caller(player.getUUID(), player.getName().getString(), AccessLevel.OWNER);
            var state = dev.friendai.core.FriendaiServerState.get(player.level().getServer());
            try {
                if (request.action() == TradeRequestC2SPayload.Action.PREVIEW) {
                    if (args.has("kind") && "barter".equals(args.get("kind").getAsString())) {
                        int max = args.has("max_ingots")
                                ? args.get("max_ingots").getAsInt()
                                : state.config().tasks.trading.barterDefaultIngots;
                        player.sendSystemMessage(Component.literal(
                                "Piglin barter gives random vanilla results; this order may spend up to " + max
                                        + " gold ingots and guarantees no particular item."));
                        return;
                    }
                    var result = TradingTools.previewFromMenu(state, companion, caller, args);
                    player.sendSystemMessage(Component.literal(result.content()));
                } else if (request.action() == TradeRequestC2SPayload.Action.SUBMIT) {
                    caller = state.tradeAuthorityFor(player.getUUID()).begin(caller, true);
                    companion
                            .executionCoordinator()
                            .start(TradingTools.submitFromMenu(state, companion, caller, args), "traders_menu");
                } else if (request.action() == TradeRequestC2SPayload.Action.BARTER) {
                    args.remove("kind");
                    caller = state.tradeAuthorityFor(player.getUUID()).begin(caller, true);
                    companion
                            .executionCoordinator()
                            .start(TradingTools.barterFromMenu(state, companion, caller, args), "traders_menu");
                } else {
                    companion
                            .executionCoordinator()
                            .start(TradingTools.waitFromMenu(state, companion, caller, args), "traders_menu");
                }
            } catch (RuntimeException invalid) {
                player.sendSystemMessage(Component.literal("Trading action failed: " + invalid.getMessage()));
            }
        });
    }

    private static void withCompanion(ServerPlayer player, java.util.function.Consumer<String> action, String value) {
        try {
            if (CompanionManager.get(player.level().getServer())
                    .find(player.getUUID())
                    .isEmpty()) return;
            action.accept(value);
        } catch (RuntimeException malformed) {
        }
    }
}
