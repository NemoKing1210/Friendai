package dev.friendai.features.trading.minecraft.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.QueueTradeScope;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.trading.BarterOrder;
import dev.friendai.features.trading.TradeBudget;
import dev.friendai.features.trading.TradeItem;
import dev.friendai.features.trading.TradeOffer;
import dev.friendai.features.trading.TradeOrder;
import dev.friendai.features.trading.TradePlan;
import dev.friendai.features.trading.TradePlanner;
import dev.friendai.features.trading.TradeQuery;
import dev.friendai.features.trading.TradeText;
import dev.friendai.features.trading.TraderRecord;
import dev.friendai.features.trading.minecraft.task.BarterTask;
import dev.friendai.features.trading.minecraft.task.InspectTraderTask;
import dev.friendai.features.trading.minecraft.task.LevelTraderTask;
import dev.friendai.features.trading.minecraft.task.RefreshTradesTask;
import dev.friendai.features.trading.minecraft.task.TradeTask;
import dev.friendai.features.trading.minecraft.task.WaitForRestockTask;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Owner-only discovery, inspection, quote queries and explicit budgeted merchant orders. */
public final class TradingTools implements FeatureToolContributor {
    private static final int MAX_TRADE_INPUTS = 2;
    private static final int MAX_PLAN_INPUTS = TradeBudget.MAX_INPUT_KINDS;
    private static final int MAX_COUNT = TradeItem.MAX_COUNT;
    private static final int MAX_DEADLINE_TICKS = 1_200;

    private final TradingToolAccess access;

    public TradingTools() {
        this(TradingToolAccess.UNAVAILABLE);
    }

    public TradingTools(TradingToolAccess access) {
        this.access = java.util.Objects.requireNonNull(access);
    }

    /** Dedicated UI actions reuse the same validation and authority path as the owner tools. */
    public static ToolResult previewFromMenu(
            TradingToolAccess access, CompanionEntity companion, Caller caller, JsonObject args) {
        return new TradingTools(access).planTrade(companion, caller, args);
    }

    public static dev.friendai.task.Task submitFromMenu(
            TradingToolAccess access, CompanionEntity companion, Caller caller, JsonObject args) {
        return new TradingTools(access).createTradeTask(companion, caller, args);
    }

    public static dev.friendai.task.Task waitFromMenu(
            TradingToolAccess access, CompanionEntity companion, Caller caller, JsonObject args) {
        return new TradingTools(access).createRestockTask(companion, caller, args);
    }

    public static dev.friendai.task.Task barterFromMenu(
            TradingToolAccess access, CompanionEntity companion, Caller caller, JsonObject args) {
        return new TradingTools(access).createBarterTask(companion, caller, args);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTool(
                callerTool(
                        "find_traders",
                        "Find loaded merchants and adult piglins within the observation radius. This records visible identities only; it does not inspect offers or promise barter rewards.",
                        strict(SchemaBuilder.object().build()),
                        AccessLevel.OWNER,
                        () -> true,
                        this::findTraders),
                metadata("find_traders"));
        registry.registerTask(task(
                "inspect_trader",
                "Approach the exact remembered merchant UUID and inspect its live offers. Never use a different nearest merchant as a substitute.",
                strict(SchemaBuilder.object()
                        .string("trader", "Exact trader UUID from find_traders or known_trades")
                        .required("trader")
                        .build()),
                TaskToolDefinition.QueueTraits.DEFAULT,
                (c, caller, args) -> {
                    rejectExtra(args, Set.of("trader"), false);
                    requireOwner(c, caller);
                    return new InspectTraderTask(parseUuid(requiredString(args, "trader"), "trader"));
                }));
        JsonObject uuidSchema = new JsonObject();
        uuidSchema.addProperty("type", "string");
        uuidSchema.addProperty("format", "uuid");
        registry.registerTask(task(
                "refresh_trades",
                "Inspect up to 20 exact selected merchants; piglin barter has its own tool, and only a complete vanilla merchant inspection updates offers.",
                strict(SchemaBuilder.object()
                        .array("traders", "Exact trader UUIDs to inspect", uuidSchema, 1, RefreshTradesTask.MAX_TRADERS)
                        .required("traders")
                        .build()),
                TaskToolDefinition.QueueTraits.DEFAULT,
                (c, caller, args) -> {
                    rejectExtra(args, Set.of("traders"), false);
                    UUID owner = requireOwner(c, caller);
                    var state = state(c);
                    List<UUID> selected = parseUuidArray(args.getAsJsonArray("traders"));
                    for (UUID id : selected) {
                        if (state.tradeBook(owner)
                                .trader(id)
                                .map(record -> "minecraft:piglin".equals(record.entityType()))
                                .orElse(false)) {
                            throw new IllegalArgumentException("piglin barter has no merchant offers to refresh");
                        }
                    }
                    long deadline = Math.addExact(
                            currentTick(c), state.tradingSettings().maxWaitTicks());
                    return new RefreshTradesTask(selected, deadline);
                }));
        registry.registerTask(task(
                "wait_for_restock",
                "Wait for one selected villager offer to restock naturally. Wandering traders and villagers without a workstation cannot restock.",
                strict(SchemaBuilder.object()
                        .string("trader", "Exact trader UUID")
                        .string("offer_ref", "Exact known offer reference")
                        .integer(
                                "wait_ticks",
                                "Optional bounded wait, defaulting to server trading settings",
                                20,
                                12_000)
                        .required("trader", "offer_ref")
                        .build()),
                TaskToolDefinition.QueueTraits.DEFAULT,
                (c, caller, args) -> createRestockTask(c, caller, args)));
        registry.registerTask(task(
                "level_trader",
                "Raise one selected villager's level using only the explicitly allowed offers and one shared cumulative spending cap; repeat a ref in the list to explicitly permit another fill. Never buy filler trades.",
                strict(SchemaBuilder.object()
                        .string("trader", "Exact villager UUID")
                        .integer("target_level", "Target profession level", 2, 5)
                        .array(
                                "allowed_offer_refs",
                                "Exact offer references the owner permits for leveling",
                                offerRefSchema(),
                                1,
                                64)
                        .array(
                                "input_caps",
                                "Shared cumulative spending ceiling and reserves",
                                inputRowsSchema(),
                                1,
                                MAX_TRADE_INPUTS)
                        .array(
                                "allowed_payment_fingerprints",
                                "Optional exact modified payment stacks",
                                fingerprintSchema(),
                                0,
                                256)
                        .integer("deadline_ticks", "Shared execution deadline from now", 1, MAX_DEADLINE_TICKS)
                        .required("trader", "target_level", "allowed_offer_refs", "input_caps", "deadline_ticks")
                        .build()),
                new TaskToolDefinition.QueueTraits(false, null, true),
                this::createLevelTraderTask));
        registry.registerTask(task(
                "barter",
                "Exchange up to the explicit gold budget with one selected adult piglin. Results are random; only piglin-owned output entities are collected, and accepted gold is never retried.",
                strict(SchemaBuilder.object()
                        .string("piglin", "Exact adult piglin UUID from find_traders")
                        .integer("max_ingots", "Maximum plain gold ingots to spend", 1, 64)
                        .string("stop_item_id", "Optional exact collected item id for early stop")
                        .string("stop_components_json", "Optional exact component requirements for the early-stop item")
                        .integer("stop_count", "Optional collected target quantity", 1, MAX_COUNT)
                        .integer("deadline_ticks", "Bounded wait deadline from now", 20, 12_000)
                        .required("piglin")
                        .build()),
                new TaskToolDefinition.QueueTraits(false, null, true),
                this::createBarterTask));
        registry.registerTool(
                callerTool(
                        "known_trades",
                        "Query remembered buy or sell offers. Results show freshness and stock; a quote is historical and is never permission to pay.",
                        strict(SchemaBuilder.object()
                                .enumeration("direction", "Query offers by output (buy) or input (sell)", "buy", "sell")
                                .string("item_id", "Exact namespaced item id, such as minecraft:emerald")
                                .string(
                                        "components_json",
                                        "Optional exact component requirements as a JSON object; use Minecraft component ids and exact levels for enchantments")
                                .integer("limit", "Maximum results", 1, TradeQuery.MAX_RESULTS)
                                .required("direction", "item_id")
                                .build()),
                        AccessLevel.OWNER,
                        () -> true,
                        this::knownTrades),
                metadata("known_trades"));
        JsonObject inputRows = inputRowsSchema();
        registry.registerTool(
                callerTool(
                        "plan_trade",
                        "Explain which remembered offers can satisfy a buy or sell request using the companion's current inventory and explicit per-input caps. Planning never spends.",
                        strict(SchemaBuilder.object()
                                .enumeration("direction", "Query offers by output (buy) or input (sell)", "buy", "sell")
                                .string("item_id", "Exact namespaced item id")
                                .string(
                                        "components_json",
                                        "Optional exact component requirements as a JSON object; use Minecraft component ids and exact levels for enchantments")
                                .integer("desired_count", "Positive target quantity", 1, MAX_COUNT)
                                .array(
                                        "input_caps",
                                        "Maximum item totals to spend and amounts to preserve",
                                        inputRows,
                                        1,
                                        MAX_PLAN_INPUTS)
                                .bool(
                                        "allow_overshoot",
                                        "Allow complete vanilla output batches to exceed the requested count")
                                .required("direction", "item_id", "desired_count", "input_caps")
                                .build()),
                        AccessLevel.OWNER,
                        () -> true,
                        this::planTrade),
                metadata("plan_trade"));
        JsonObject tradeSchema = strict(SchemaBuilder.object()
                .string("trader", "Exact trader UUID from a remembered offer")
                .string("offer_ref", "Stable offer reference returned by known_trades")
                .integer("revision", "Trader observation revision returned by known_trades", 0, Integer.MAX_VALUE)
                .integer("desired_output_count", "Positive exact component output quantity to receive", 1, MAX_COUNT)
                .array(
                        "input_caps",
                        "Explicit maximum spend and reserve for every offer input",
                        inputRows,
                        1,
                        MAX_TRADE_INPUTS)
                .array(
                        "allowed_payment_fingerprints",
                        "Optional SHA-256 stack fingerprints authorizing modified payment stacks",
                        fingerprintSchema(),
                        0,
                        256)
                .bool("allow_overshoot", "Allow full output batches to exceed desired_output_count")
                .bool("allow_partial", "Accept a real partial receipt if execution stops early")
                .integer("deadline_ticks", "Execution deadline from now, in ticks", 1, MAX_DEADLINE_TICKS)
                .required(
                        "trader",
                        "offer_ref",
                        "revision",
                        "desired_output_count",
                        "input_caps",
                        "allow_overshoot",
                        "allow_partial",
                        "deadline_ticks")
                .build());
        registry.registerTask(task(
                "trade_item",
                "Execute one remembered merchant offer for an explicit output count and per-input spending caps. The live merchant and offer are reread before every fill; stale quotes must be inspected again.",
                tradeSchema,
                new TaskToolDefinition.QueueTraits(false, null, true),
                this::createTradeTask));
    }

    private static TaskToolDefinition task(
            String name,
            String description,
            JsonObject schema,
            TaskToolDefinition.QueueTraits queueTraits,
            TaskToolDefinition.CallerFactory callerFactory) {
        return new TaskToolDefinition(
                name,
                description,
                schema,
                null,
                callerFactory,
                true,
                AccessLevel.OWNER,
                queueTraits,
                true,
                true,
                metadata(name));
    }

    private static Tool<CompanionEntity> callerTool(
            String name,
            String description,
            JsonObject schema,
            AccessLevel minAccess,
            java.util.function.BooleanSupplier enabled,
            CallerAction action) {
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
                return minAccess;
            }

            @Override
            public boolean enabled() {
                return enabled.getAsBoolean();
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return action.run(companion, new Caller(null, "", minAccess), args);
            }

            @Override
            public ToolResult execute(CompanionEntity companion, Caller caller, JsonObject args) {
                return action.run(companion, caller, args);
            }
        };
    }

    private static ToolMeta metadata(String name) {
        String hint = switch (name) {
            case "find_traders" ->
                "- Trading knowledge belongs to the owner. Find only loaded nearby traders; remembered locations and offers can be stale.";
            case "refresh_trades" ->
                "- Refresh at most 20 explicitly selected traders; only complete live inspection replaces remembered offers.";
            case "wait_for_restock" ->
                "- Wait between checks with merchant sessions closed; only real vanilla restocks count, and no stock or workstation is forced.";
            case "known_trades" ->
                "- Known prices are historical quotes. Check freshness and inspect a trader before any purchase; never promise a piglin barter reward.";
            case "plan_trade" ->
                "- plan_trade is read-only: check exact stock, output batches and every required currency before placing an order.";
            case "trade_item" ->
                "- Spend only for an explicit owner order with a cap and reserve for every input. Respect missing currency and explicit offer refs; never buy filler to level a villager.";
            case "level_trader" ->
                "- Level only the exact selected villager, using only the owner's allowed offer refs under one shared cumulative cap. Stop at the requested level; never buy filler.";
            case "barter" ->
                "- Piglin barter is random. Pay only one plain gold ingot at a time under the owner's explicit cap, attribute only drops owned by the selected piglin, and never retry accepted gold with no output.";
            case "inspect_trader" -> "";
            default -> throw new IllegalArgumentException("unknown trading tool: " + name);
        };
        return new ToolMeta(name, ToolGroup.TRADING, false, hint, List.of(), Cost.HIGH);
    }

    @FunctionalInterface
    private interface CallerAction {
        ToolResult run(CompanionEntity companion, Caller caller, JsonObject args);
    }

    private ToolResult findTraders(CompanionEntity companion, Caller caller, JsonObject args) {
        rejectExtra(args, Set.of(), false);
        requireOwner(companion, caller);
        var state = state(companion);
        List<TraderRecord> records = state.observeTraders(companion);
        return ToolResult.ok(
                records.isEmpty()
                        ? "No loaded merchants or adult piglins were observed nearby."
                        : "Observed " + records.size() + " loaded trader(s):\n"
                                + String.join(
                                        "\n",
                                        records.stream()
                                                .limit(40)
                                                .map(TradeText::describe)
                                                .toList()));
    }

    private ToolResult knownTrades(CompanionEntity companion, Caller caller, JsonObject args) {
        rejectExtra(args, Set.of("direction", "item_id", "components_json", "limit"), false);
        UUID owner = requireOwner(companion, caller);
        TradeQuery query = parseQuery(args);
        var state = state(companion);
        var actor = companion.hands().player().getUUID();
        var book = state.tradeBook(owner);
        book.activatePricingActor(actor);
        long now = currentTick(companion);
        List<TradeQuery.Match> matches = book.query(
                query,
                now,
                state.tradingSettings().quoteFreshTicks(),
                state.tradingSettings().locationFreshTicks());
        return ToolResult.ok(
                matches.isEmpty()
                        ? "No remembered offers match this exact item and component query."
                        : "Remembered offers (historical quotes; inspect before spending):\n"
                                + String.join(
                                        "\n",
                                        matches.stream()
                                                .map(TradeText::describe)
                                                .toList()));
    }

    private ToolResult planTrade(CompanionEntity companion, Caller caller, JsonObject args) {
        rejectExtra(
                args,
                Set.of("direction", "item_id", "components_json", "desired_count", "input_caps", "allow_overshoot"),
                false);
        UUID owner = requireOwner(companion, caller);
        TradeQuery query = parseQuery(args);
        int desired = args.get("desired_count").getAsInt();
        if (desired < 1 || desired > MAX_COUNT) throw new IllegalArgumentException("desired_count is out of range");
        TradeBudget budget = parseBudget(args.getAsJsonArray("input_caps"), MAX_PLAN_INPUTS);
        var state = state(companion);
        UUID actor = companion.hands().player().getUUID();
        var book = state.tradeBook(owner);
        book.activatePricingActor(actor);
        List<TraderRecord> records = book.snapshot().traders();
        Map<String, Integer> inventory = inventoryByItem(companion);
        TradePlan plan = TradePlanner.plan(
                query,
                desired,
                records,
                inventory,
                budget,
                currentTick(companion),
                args.has("allow_overshoot") && args.get("allow_overshoot").getAsBoolean(),
                actor,
                new TradePlanner.Location(
                        companion.level().dimension().identifier().toString(),
                        companion.blockPosition().getX(),
                        companion.blockPosition().getY(),
                        companion.blockPosition().getZ()),
                state.tradingSettings().quoteFreshTicks(),
                state.tradingSettings().locationFreshTicks());
        return ToolResult.ok(describePlan(plan, records));
    }

    private dev.friendai.task.Task createTradeTask(CompanionEntity companion, Caller caller, JsonObject args) {
        // QueueTradeScope is injected only after the first caller-aware preview. It must agree with the caller.
        rejectExtra(
                args,
                Set.of(
                        "trader",
                        "offer_ref",
                        "revision",
                        "desired_output_count",
                        "input_caps",
                        "allowed_payment_fingerprints",
                        "allow_overshoot",
                        "allow_partial",
                        "deadline_ticks",
                        QueueTradeScope.ARG),
                true);
        UUID owner = requireOwner(companion, caller);
        if (caller.tradingScope() == null) {
            throw new IllegalArgumentException("spending requires an owner-initiated player turn");
        }
        UUID savedScope = QueueTradeScope.read(args);
        if (savedScope != null && !savedScope.equals(caller.tradingScope())) {
            throw new IllegalArgumentException("queued trading scope does not match this owner turn");
        }
        UUID traderId = parseUuid(requiredString(args, "trader"), "trader");
        String offerRef = args.get("offer_ref").getAsString();
        if (offerRef.isBlank() || offerRef.length() > 160) throw new IllegalArgumentException("invalid offer_ref");
        long revision = args.get("revision").getAsLong();
        int desired = args.get("desired_output_count").getAsInt();
        if (desired < 1 || desired > MAX_COUNT)
            throw new IllegalArgumentException("desired_output_count is out of range");
        TradeBudget budget = parseBudget(args.getAsJsonArray("input_caps"), MAX_TRADE_INPUTS);
        Set<String> fingerprints = parseFingerprints(args.getAsJsonArray("allowed_payment_fingerprints"));
        boolean overshoot = args.get("allow_overshoot").getAsBoolean();
        boolean partial = args.get("allow_partial").getAsBoolean();
        int deadlineTicks = args.get("deadline_ticks").getAsInt();
        if (deadlineTicks < 1 || deadlineTicks > MAX_DEADLINE_TICKS) {
            throw new IllegalArgumentException("deadline_ticks is out of range");
        }

        var state = state(companion);
        var book = state.tradeBook(owner);
        UUID actor = companion.hands().player().getUUID();
        book.activatePricingActor(actor);
        TraderRecord trader =
                book.trader(traderId).orElseThrow(() -> new IllegalArgumentException("unknown trader UUID"));
        if (trader.revision() != revision)
            throw new IllegalArgumentException("trader revision changed; query offers again");
        TradeOffer offer = book.resolveOffer(traderId, offerRef)
                .orElseThrow(() -> new IllegalArgumentException("unknown or ambiguous offer reference"));
        long now = currentTick(companion);
        if (trader.status() != TraderRecord.Status.VERIFIED
                || !actor.equals(offer.pricingActor())
                || offer.observedTick() > now
                || now - offer.observedTick() >= state.tradingSettings().quoteFreshTicks()) {
            throw new IllegalArgumentException("offer quote is stale; inspect this trader again before spending");
        }
        if (offer.exhausted()) throw new IllegalArgumentException("offer is exhausted; inspect again for restock");
        validateBudgetMatchesOffer(budget, offer);
        long deadline = Math.addExact(now, deadlineTicks);
        UUID orderId = orderId(
                caller.tradingScope(),
                traderId,
                offerRef,
                revision,
                desired,
                budget,
                fingerprints,
                overshoot,
                partial,
                deadlineTicks);
        TradeOrder requested = new TradeOrder(
                orderId,
                owner,
                caller.tradingScope(),
                traderId,
                offerRef,
                revision,
                desired,
                budget,
                fingerprints,
                overshoot,
                partial,
                deadline);
        TradeOrder authorized = state.tradeAuthorityFor(owner).authorize(caller, requested);
        return new TradeTask(authorized);
    }

    private dev.friendai.task.Task createRestockTask(CompanionEntity companion, Caller caller, JsonObject args) {
        rejectExtra(args, Set.of("trader", "offer_ref", "wait_ticks"), false);
        requireOwner(companion, caller);
        var state = state(companion);
        UUID traderId = parseUuid(requiredString(args, "trader"), "trader");
        String offerRef = requiredString(args, "offer_ref");
        int maximum = state.tradingSettings().maxWaitTicks();
        int waitTicks = args.has("wait_ticks")
                ? args.get("wait_ticks").getAsInt()
                : state.tradingSettings().waitTicks();
        if (waitTicks < 20 || waitTicks > maximum) {
            throw new IllegalArgumentException("wait_ticks must be between 20 and the configured maximum " + maximum);
        }
        long deadline = Math.addExact(currentTick(companion), waitTicks);
        return new WaitForRestockTask(traderId, offerRef, deadline);
    }

    private dev.friendai.task.Task createLevelTraderTask(CompanionEntity companion, Caller caller, JsonObject args) {
        rejectExtra(
                args,
                Set.of(
                        "trader",
                        "target_level",
                        "allowed_offer_refs",
                        "input_caps",
                        "allowed_payment_fingerprints",
                        "deadline_ticks",
                        QueueTradeScope.ARG),
                true);
        UUID owner = requireOwner(companion, caller);
        if (caller.tradingScope() == null)
            throw new IllegalArgumentException("leveling requires an owner-initiated player turn");
        UUID savedScope = QueueTradeScope.read(args);
        if (savedScope != null && !savedScope.equals(caller.tradingScope())) {
            throw new IllegalArgumentException("queued trading scope does not match this owner turn");
        }
        int targetLevel = args.get("target_level").getAsInt();
        if (targetLevel < 2 || targetLevel > 5)
            throw new IllegalArgumentException("target_level must be between 2 and 5");
        JsonArray refs = args.getAsJsonArray("allowed_offer_refs");
        if (refs == null || refs.isEmpty() || refs.size() > 64)
            throw new IllegalArgumentException("allowed_offer_refs must contain 1 to 64 refs");
        var state = state(companion);
        UUID traderId = parseUuid(requiredString(args, "trader"), "trader");
        TradeBudget budget = parseBudget(args.getAsJsonArray("input_caps"), MAX_TRADE_INPUTS);
        Set<String> fingerprints = parseFingerprints(args.getAsJsonArray("allowed_payment_fingerprints"));
        int deadlineTicks = args.get("deadline_ticks").getAsInt();
        if (deadlineTicks < 1 || deadlineTicks > MAX_DEADLINE_TICKS)
            throw new IllegalArgumentException("deadline_ticks is out of range");
        long now = currentTick(companion);
        long deadline = Math.addExact(now, deadlineTicks);
        var book = state.tradeBook(owner);
        TraderRecord trader =
                book.trader(traderId).orElseThrow(() -> new IllegalArgumentException("unknown trader UUID"));
        if (!"minecraft:villager".equals(trader.entityType()))
            throw new IllegalArgumentException("only a selected villager can be leveled");
        var orders = new java.util.ArrayList<TradeOrder>();
        int fillIndex = 0;
        for (JsonElement element : refs) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("allowed_offer_refs must be strings");
            }
            String ref = element.getAsString();
            if (ref.isBlank() || ref.length() > 160) {
                throw new IllegalArgumentException("allowed offer refs must be non-empty and bounded");
            }
            TradeOffer offer = book.resolveOffer(traderId, ref)
                    .orElseThrow(() -> new IllegalArgumentException("unknown or ambiguous allowed offer ref: " + ref));
            if (offer.exhausted())
                throw new IllegalArgumentException("allowed offer is exhausted; inspect or wait for restock first");
            if (!offer.pricingActor().equals(companion.hands().player().getUUID())
                    || offer.observedTick() > now
                    || now - offer.observedTick() >= state.tradingSettings().quoteFreshTicks()) {
                throw new IllegalArgumentException("allowed offer quote is stale; inspect the villager again");
            }
            validateBudgetCoversOffer(budget, offer);
            UUID id = orderId(
                    caller.tradingScope(),
                    traderId,
                    ref,
                    trader.revision(),
                    1,
                    budget,
                    fingerprints,
                    true,
                    false,
                    deadlineTicks);
            id = UUID.nameUUIDFromBytes((id + "\nlevel-fill-" + fillIndex++).getBytes(StandardCharsets.UTF_8));
            TradeOrder requested = new TradeOrder(
                    id,
                    owner,
                    caller.tradingScope(),
                    traderId,
                    ref,
                    trader.revision(),
                    1,
                    budget,
                    fingerprints,
                    true,
                    false,
                    deadline);
            orders.add(state.tradeAuthorityFor(owner).authorize(caller, requested));
        }
        return new LevelTraderTask(traderId, targetLevel, orders);
    }

    private dev.friendai.task.Task createBarterTask(CompanionEntity companion, Caller caller, JsonObject args) {
        rejectExtra(
                args,
                Set.of(
                        "piglin",
                        "max_ingots",
                        "stop_item_id",
                        "stop_components_json",
                        "stop_count",
                        "deadline_ticks",
                        QueueTradeScope.ARG),
                true);
        UUID owner = requireOwner(companion, caller);
        if (caller.tradingScope() == null)
            throw new IllegalArgumentException("barter requires an owner-initiated player turn");
        UUID savedScope = QueueTradeScope.read(args);
        if (savedScope != null && !savedScope.equals(caller.tradingScope())) {
            throw new IllegalArgumentException("queued trading scope does not match this owner turn");
        }
        var state = state(companion);
        var config = state.tradingSettings();
        UUID piglinId = parseUuid(requiredString(args, "piglin"), "piglin");
        int maxIngots = args.has("max_ingots") ? args.get("max_ingots").getAsInt() : config.barterDefaultIngots();
        if (maxIngots < 1 || maxIngots > config.maxBarterIngots()) {
            throw new IllegalArgumentException(
                    "max_ingots must be between 1 and the configured maximum " + config.maxBarterIngots());
        }
        int deadlineTicks =
                args.has("deadline_ticks") ? args.get("deadline_ticks").getAsInt() : config.waitTicks();
        if (deadlineTicks < 20 || deadlineTicks > config.maxWaitTicks()) {
            throw new IllegalArgumentException("deadline_ticks is outside the configured wait range");
        }
        boolean hasItem = args.has("stop_item_id");
        boolean hasCount = args.has("stop_count");
        if (hasItem != hasCount || (!hasItem && args.has("stop_components_json"))) {
            throw new IllegalArgumentException("early stop requires both stop_item_id and stop_count");
        }
        java.util.Optional<TradeQuery> target = java.util.Optional.empty();
        int targetCount = 0;
        if (hasItem) {
            String components = args.has("stop_components_json") ? requiredString(args, "stop_components_json") : "{}";
            target = java.util.Optional.of(new TradeQuery(
                    TradeQuery.Direction.BUY,
                    requiredString(args, "stop_item_id"),
                    components,
                    TradeQuery.MAX_RESULTS));
            targetCount = args.get("stop_count").getAsInt();
            if (targetCount < 1 || targetCount > MAX_COUNT)
                throw new IllegalArgumentException("stop_count is out of range");
        }
        state.observeTraders(companion);
        TraderRecord known = state.tradeBook(owner)
                .trader(piglinId)
                .orElseThrow(() -> new IllegalArgumentException("piglin must be discovered nearby first"));
        if (!"minecraft:piglin".equals(known.entityType()))
            throw new IllegalArgumentException("selected UUID is not an adult piglin");
        if (!companion.level().dimension().identifier().toString().equals(known.dimension())) {
            throw new IllegalArgumentException("selected piglin is in another dimension");
        }
        if (companion.distanceToSqr(known.x() + 0.5, known.y() + 0.5, known.z() + 0.5)
                > config.observationRadius() * (double) config.observationRadius()) {
            throw new IllegalArgumentException("selected piglin is outside the observation radius");
        }
        long deadline = Math.addExact(currentTick(companion), deadlineTicks);
        UUID id = barterOrderId(
                caller.tradingScope(), piglinId, maxIngots, target.orElse(null), targetCount, deadlineTicks);
        BarterOrder requested =
                new BarterOrder(id, owner, caller.tradingScope(), piglinId, maxIngots, target, targetCount, deadline);
        return new BarterTask(state.tradeAuthorityFor(owner).authorizeBarter(caller, requested));
    }

    private static UUID barterOrderId(
            UUID scope, UUID piglin, int maxIngots, TradeQuery target, int targetCount, int deadlineTicks) {
        String intent = piglin + "\n" + maxIngots + "\n" + (target == null ? "" : target.toString()) + "\n"
                + targetCount + "\n" + deadlineTicks;
        return UUID.nameUUIDFromBytes((scope + "\nbarter\n" + intent).getBytes(StandardCharsets.UTF_8));
    }

    private static TradeQuery parseQuery(JsonObject args) {
        String direction = requiredString(args, "direction");
        TradeQuery.Direction parsedDirection = switch (direction) {
            case "buy" -> TradeQuery.Direction.BUY;
            case "sell" -> TradeQuery.Direction.SELL;
            default -> throw new IllegalArgumentException("direction must be buy or sell");
        };
        String item = requiredString(args, "item_id");
        String components =
                args.has("components_json") ? args.get("components_json").getAsString() : "{}";
        int limit = args.has("limit") ? args.get("limit").getAsInt() : TradeQuery.MAX_RESULTS;
        return new TradeQuery(parsedDirection, item, components, limit);
    }

    private static TradeBudget parseBudget(JsonArray values, int maximumRows) {
        if (values == null || values.size() < 1 || values.size() > maximumRows) {
            throw new IllegalArgumentException("input_caps row count is outside the supported range");
        }
        Map<String, Integer> caps = new HashMap<>();
        Map<String, Integer> reserves = new HashMap<>();
        Map<String, Integer> unitCosts = new HashMap<>();
        for (JsonElement element : values) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("each input cap must be an object");
            JsonObject row = element.getAsJsonObject();
            rejectExtra(row, Set.of("item_id", "max_count", "reserve_count", "max_unit_cost"), false);
            String item = requiredString(row, "item_id");
            int cap = row.get("max_count").getAsInt();
            if (cap < 1 || cap > MAX_COUNT) throw new IllegalArgumentException("max_count is out of range");
            if (caps.putIfAbsent(item, cap) != null)
                throw new IllegalArgumentException("duplicate input cap for " + item);
            if (row.has("reserve_count")) {
                int reserve = row.get("reserve_count").getAsInt();
                if (reserve < 0 || reserve > MAX_COUNT || reserve > cap) {
                    throw new IllegalArgumentException("reserve_count must be between zero and max_count");
                }
                if (reserve > 0) reserves.put(item, reserve);
            }
            if (row.has("max_unit_cost")) {
                int maxUnit = row.get("max_unit_cost").getAsInt();
                if (maxUnit < 1 || maxUnit > MAX_COUNT)
                    throw new IllegalArgumentException("max_unit_cost is out of range");
                unitCosts.put(item, maxUnit);
            }
        }
        return new TradeBudget(caps, reserves, unitCosts);
    }

    private static Set<String> parseFingerprints(JsonArray values) {
        if (values == null) return Set.of();
        if (values.size() > 256) throw new IllegalArgumentException("too many payment fingerprints");
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement element : values) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("payment fingerprints must be strings");
            }
            String value = element.getAsString();
            if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid payment fingerprint");
            if (!result.add(value)) throw new IllegalArgumentException("duplicate payment fingerprint");
        }
        return Set.copyOf(result);
    }

    /** Preview and queued task construction must refer to one runtime authorization for the same intent. */
    private static UUID orderId(
            UUID scope,
            UUID trader,
            String offerRef,
            long revision,
            int desired,
            TradeBudget budget,
            Set<String> fingerprints,
            boolean overshoot,
            boolean partial,
            int deadlineTicks) {
        String intent = trader + "\n" + offerRef + "\n" + revision + "\n" + desired + "\n"
                + new TreeMap<>(budget.inputCaps()) + "\n" + new TreeMap<>(budget.reserves()) + "\n"
                + new TreeMap<>(budget.maxUnitCosts()) + "\n"
                + fingerprints.stream().sorted().toList() + "\n"
                + overshoot + "\n" + partial + "\n" + deadlineTicks;
        return UUID.nameUUIDFromBytes((scope + "\n" + intent).getBytes(StandardCharsets.UTF_8));
    }

    private static void validateBudgetMatchesOffer(TradeBudget budget, TradeOffer offer) {
        Set<String> inputs = new HashSet<>();
        offer.effectiveInputs().forEach(input -> inputs.add(input.itemId()));
        if (!budget.inputCaps().keySet().equals(inputs)) {
            throw new IllegalArgumentException("input_caps must name every exact offer input and no other items");
        }
    }

    private static void validateBudgetCoversOffer(TradeBudget budget, TradeOffer offer) {
        for (var input : offer.effectiveInputs()) {
            if (!budget.inputCaps().containsKey(input.itemId())) {
                throw new IllegalArgumentException("shared input_caps must cover every input of every allowed offer");
            }
        }
    }

    private static Map<String, Integer> inventoryByItem(CompanionEntity companion) {
        Map<String, Integer> result = new HashMap<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            ItemStack stack = companion.inventory().getItem(slot);
            if (stack.isEmpty()) continue;
            String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            result.merge(item, stack.getCount(), Math::addExact);
        }
        return Map.copyOf(result);
    }

    private static String describePlan(TradePlan plan, List<TraderRecord> records) {
        StringBuilder out = new StringBuilder("Trade plan (no items spent):");
        if (plan.fills().isEmpty()) out.append("\nNo affordable fill is currently known.");
        for (TradePlan.Fill fill : plan.fills()) {
            TraderRecord trader = records.stream()
                    .filter(record -> record.id().equals(fill.trader()))
                    .findFirst()
                    .orElse(null);
            out.append("\n")
                    .append(
                            trader == null
                                    ? fill.trader()
                                    : (trader.label().isBlank() ? trader.entityType() : trader.label()))
                    .append(" [")
                    .append(fill.trader())
                    .append("] ")
                    .append(fill.offerRef())
                    .append(" revision=")
                    .append(fill.revision())
                    .append(": ");
            out.append(fill.inputs().stream()
                            .map(input -> (long) input.count() * fill.repeats() + " " + input.itemId())
                            .collect(java.util.stream.Collectors.joining(" + ")))
                    .append(" -> ")
                    .append((long) fill.output().count() * fill.repeats())
                    .append(" ")
                    .append(fill.output().itemId())
                    .append(" in ")
                    .append(fill.repeats())
                    .append(" fill(s)");
        }
        if (!plan.missing().isEmpty()) out.append("\nMissing input resources: ").append(plan.missing());
        if (!plan.needsInspection().isEmpty())
            out.append("\nNeeds live inspection: ").append(plan.needsInspection());
        if (plan.conditional())
            out.append(
                    "\nConditional: current price/stock must be rechecked live, and exact item component predicates must match.");
        out.append("\nA plan is informational; the live quote and owner budget are checked again before each payment.");
        return out.toString();
    }

    private static UUID requireOwner(CompanionEntity companion, Caller caller) {
        if (companion == null
                || caller == null
                || caller.access() != AccessLevel.OWNER
                || companion.ownerUuid() == null
                || !companion.ownerUuid().equals(caller.uuid())) {
            throw new IllegalArgumentException("trading tools are available only to the companion owner");
        }
        return companion.ownerUuid();
    }

    private TradingToolAccess state(CompanionEntity companion) {
        if (companion.level().isClientSide() || companion.level().getServer() == null) {
            throw new IllegalArgumentException("trading tools require a server companion");
        }
        var state = access;
        if (!state.tradingSettings().enabled()) throw new IllegalArgumentException("trading is disabled by the server");
        return state;
    }

    private static long currentTick(CompanionEntity companion) {
        return companion.level().getServer().overworld().getGameTime();
    }

    private static UUID parseUuid(String text, String label) {
        if (text == null || !text.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException(label + " must be a canonical UUID");
        }
        return UUID.fromString(text);
    }

    private static List<UUID> parseUuidArray(JsonArray values) {
        if (values == null || values.size() < 1 || values.size() > RefreshTradesTask.MAX_TRADERS) {
            throw new IllegalArgumentException("traders must contain 1 to 20 UUIDs");
        }
        List<UUID> result = new java.util.ArrayList<>(values.size());
        for (JsonElement element : values) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("each trader id must be a UUID string");
            }
            result.add(parseUuid(element.getAsString(), "trader"));
        }
        return List.copyOf(result);
    }

    private static String requiredString(JsonObject args, String name) {
        if (args == null
                || !args.has(name)
                || !args.get(name).isJsonPrimitive()
                || !args.getAsJsonPrimitive(name).isString()) {
            throw new IllegalArgumentException("missing or invalid " + name);
        }
        return args.get(name).getAsString();
    }

    private static void rejectExtra(JsonObject args, Set<String> allowed, boolean allowScope) {
        if (args == null) throw new IllegalArgumentException("arguments must be an object");
        for (String key : args.keySet()) {
            if (!allowed.contains(key) && !(allowScope && QueueTradeScope.ARG.equals(key))) {
                throw new IllegalArgumentException("unknown argument: " + key);
            }
        }
    }

    private static JsonObject strict(JsonObject schema) {
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject inputRowsSchema() {
        JsonObject item = SchemaBuilder.object()
                .string("item_id", "Exact namespaced item id")
                .integer("max_count", "Maximum total payment", 1, MAX_COUNT)
                .integer("reserve_count", "Amount to leave in inventory", 0, MAX_COUNT)
                .integer("max_unit_cost", "Optional maximum cost per output item", 1, MAX_COUNT)
                .required("item_id", "max_count")
                .build();
        item.addProperty("additionalProperties", false);
        return item;
    }

    private static JsonObject fingerprintSchema() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("pattern", "^[0-9a-f]{64}$");
        return item;
    }

    private static JsonObject offerRefSchema() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("minLength", 1);
        item.addProperty("maxLength", 160);
        return item;
    }
}
