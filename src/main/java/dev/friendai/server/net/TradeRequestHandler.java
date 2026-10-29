package dev.friendai.server.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.trading.TradeQuery;
import dev.friendai.features.trading.minecraft.task.InspectTraderTask;
import dev.friendai.features.trading.minecraft.task.RefreshTradesTask;
import dev.friendai.features.trading.minecraft.tool.TradingTools;
import dev.friendai.protocol.trading.TradeRequest;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Authenticated owner-only endpoint for the typed Traders menu requests. */
public final class TradeRequestHandler {
    private TradeRequestHandler() {}

    public static void handle(AuthenticatedRequestContext context, TradeRequest request) {
        ServerPlayer player = context.player();
        ServerRuntime runtime = context.runtime();
        long requestId = context.requestId();
        if (!runtime.config().tasks.trading.enabled) return;
        UUID queryId = queryId(request);
        if (!runtime.protocolConnections()
                .allowTradeRequest(
                        player.getUUID(),
                        queryId,
                        request instanceof TradeRequest.Subscribe,
                        request instanceof TradeRequest.Close)) return;

        var book = runtime.trading().book(player.getUUID());
        if (request instanceof TradeRequest.Subscribe) {
            TradeViewStream.send(runtime, player, queryId, 0, null, requestId);
        } else if (request instanceof TradeRequest.Close) {
            // Closing removes the sender's active query in the connection-scoped gate above.
        } else if (request instanceof TradeRequest.Query query) {
            if (query.itemId().isBlank()) {
                TradeViewStream.send(runtime, player, queryId, query.page(), null, requestId);
            } else {
                try {
                    TradeQuery tradeQuery = new TradeQuery(
                            query.direction() == TradeRequest.Direction.BUY
                                    ? TradeQuery.Direction.BUY
                                    : TradeQuery.Direction.SELL,
                            query.itemId(),
                            query.componentsJson(),
                            TradeQuery.MAX_RESULTS);
                    TradeViewStream.send(runtime, player, queryId, query.page(), tradeQuery, requestId);
                } catch (IllegalArgumentException invalid) {
                    // Invalid domain query: do not mutate or publish an unfiltered result.
                }
            }
        } else if (request instanceof TradeRequest.Inspect inspect) {
            runtime.companions()
                    .find(player.getUUID())
                    .ifPresent(companion -> companion
                            .executionCoordinator()
                            .start(new InspectTraderTask(inspect.traderId()), "traders_menu"));
        } else if (request instanceof TradeRequest.Refresh refresh) {
            if (!refresh.traderIds().isEmpty()) {
                runtime.companions()
                        .find(player.getUUID())
                        .ifPresent(companion -> companion
                                .executionCoordinator()
                                .start(new RefreshTradesTask(refresh.traderIds()), "traders_menu"));
            }
        } else if (request instanceof TradeRequest.Forget forget) {
            if (!book.forget(forget.traderId())) {
                player.sendSystemMessage(Component.translatable("friendai.menu.traders.forget_denied"));
            } else {
                TradeViewStream.send(runtime, player, queryId, forget.page(), null, requestId);
            }
        } else if (request instanceof TradeRequest.Preview
                || request instanceof TradeRequest.Submit
                || request instanceof TradeRequest.Wait
                || request instanceof TradeRequest.Barter) {
            orderAction(player, runtime, request);
        }
    }

    private static UUID queryId(TradeRequest request) {
        if (request instanceof TradeRequest.Subscribe value) return value.queryId();
        if (request instanceof TradeRequest.Query value) return value.queryId();
        if (request instanceof TradeRequest.Close value) return value.queryId();
        if (request instanceof TradeRequest.Inspect value) return value.queryId();
        if (request instanceof TradeRequest.Refresh value) return value.queryId();
        if (request instanceof TradeRequest.Preview value) return value.queryId();
        if (request instanceof TradeRequest.Submit value) return value.queryId();
        if (request instanceof TradeRequest.Wait value) return value.queryId();
        if (request instanceof TradeRequest.Forget value) return value.queryId();
        if (request instanceof TradeRequest.Barter value) return value.queryId();
        throw new IllegalArgumentException("unknown trade request");
    }

    private static void orderAction(ServerPlayer player, ServerRuntime runtime, TradeRequest request) {
        runtime.companions().find(player.getUUID()).ifPresent(companion -> {
            String json = request instanceof TradeRequest.Preview value
                    ? value.argumentsJson()
                    : request instanceof TradeRequest.Submit value
                            ? value.argumentsJson()
                            : request instanceof TradeRequest.Wait value
                                    ? value.argumentsJson()
                                    : ((TradeRequest.Barter) request).argumentsJson();
            JsonObject args;
            try {
                var parsed = JsonParser.parseString(json);
                if (!parsed.isJsonObject()) return;
                args = parsed.getAsJsonObject();
            } catch (RuntimeException malformed) {
                return;
            }
            Caller caller = new Caller(player.getUUID(), player.getName().getString(), AccessLevel.OWNER);
            try {
                if (request instanceof TradeRequest.Preview) {
                    if (args.has("kind") && "barter".equals(args.get("kind").getAsString())) {
                        int max = args.has("max_ingots")
                                ? args.get("max_ingots").getAsInt()
                                : runtime.config().tasks.trading.barterDefaultIngots;
                        player.sendSystemMessage(Component.literal(
                                "Piglin barter gives random vanilla results; this order may spend up to " + max
                                        + " gold ingots and guarantees no particular item."));
                        return;
                    }
                    var result = TradingTools.previewFromMenu(runtime.tradingAccess(), companion, caller, args);
                    player.sendSystemMessage(Component.literal(result.content()));
                } else if (request instanceof TradeRequest.Submit) {
                    caller = runtime.tradingAccess()
                            .tradeAuthorityFor(player.getUUID())
                            .begin(caller, true);
                    companion
                            .executionCoordinator()
                            .start(
                                    TradingTools.submitFromMenu(runtime.tradingAccess(), companion, caller, args),
                                    "traders_menu");
                } else if (request instanceof TradeRequest.Barter) {
                    args.remove("kind");
                    caller = runtime.tradingAccess()
                            .tradeAuthorityFor(player.getUUID())
                            .begin(caller, true);
                    companion
                            .executionCoordinator()
                            .start(
                                    TradingTools.barterFromMenu(runtime.tradingAccess(), companion, caller, args),
                                    "traders_menu");
                } else {
                    companion
                            .executionCoordinator()
                            .start(
                                    TradingTools.waitFromMenu(runtime.tradingAccess(), companion, caller, args),
                                    "traders_menu");
                }
            } catch (RuntimeException invalid) {
                player.sendSystemMessage(Component.literal("Trading action failed: " + invalid.getMessage()));
            }
        });
    }
}
