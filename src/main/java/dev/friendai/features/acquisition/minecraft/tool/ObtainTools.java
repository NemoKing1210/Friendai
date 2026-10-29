package dev.friendai.features.acquisition.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.acquisition.ObtainPlan;
import dev.friendai.features.acquisition.ObtainPlanner;
import dev.friendai.features.acquisition.ObtainText;
import dev.friendai.features.acquisition.TradingContext;
import dev.friendai.features.acquisition.WorldFacts;
import dev.friendai.features.acquisition.minecraft.ObtainSubtaskFactory;
import dev.friendai.features.acquisition.minecraft.search.DropIndex;
import dev.friendai.features.acquisition.minecraft.search.WorldScan;
import dev.friendai.features.acquisition.minecraft.task.ObtainTask;
import dev.friendai.features.crafting.minecraft.RecipeIndex;
import dev.friendai.features.trading.TradeAuthority;
import dev.friendai.features.trading.TraderRecord;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.TaskContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Getting an item by any means, and explaining the plan before committing to it. */
public final class ObtainTools implements FeatureToolContributor {
    private final ObtainSubtaskFactory subtaskFactory;

    public ObtainTools(ObtainSubtaskFactory subtaskFactory) {
        this.subtaskFactory = subtaskFactory;
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        JsonObject schema = SchemaBuilder.object()
                .string("item", "Item id like minecraft:iron_pickaxe; tags are not accepted")
                .integer("count", "How many to get", 1, 64)
                .bool("use_containers", "Allow taking items from nearby chests and barrels")
                .bool("explore", "Allow searching further away and digging down when a resource is not nearby")
                .enumeration("scope", "Search budget", "local", "local", "expedition")
                .required("item", "count")
                .build();
        TaskToolDefinition obtain = new TaskToolDefinition(
                "obtain_item",
                "Get an item by any means: take it from nearby chests, mine, hunt, farm, smelt and craft, making the tools needed on the way, and explore if a resource is not nearby. Uses any nearby log for planks and sticks; a specific wood is explored only when the request names it. Re-plans after every step.",
                schema,
                (c, a) -> new ObtainTask(
                        itemId(a.get("item").getAsString()),
                        a.get("count").getAsInt(),
                        flag(a, "use_containers", true),
                        flag(a, "explore", true),
                        subtaskFactory),
                (c, caller, a) -> callerObtain(c, caller, a, subtaskFactory),
                true,
                AccessLevel.TRUSTED,
                TaskToolDefinition.QueueTraits.DEFAULT,
                true,
                true,
                metadata("obtain_item"));
        registry.registerTask(obtain);
        registry.registerTool(
                new Tool<>() {
                    @Override
                    public String name() {
                        return "plan_obtain";
                    }

                    @Override
                    public String description() {
                        return "Show how the companion would get an item from here, step by step, without doing it. Uses any nearby log for planks and sticks; a specific wood is explored only when the request names it.";
                    }

                    @Override
                    public JsonObject schema() {
                        return SchemaBuilder.object()
                                .string("item", "Item id like minecraft:iron_pickaxe; tags are not accepted")
                                .integer("count", "How many to get", 1, 64)
                                .bool("use_containers", "Allow taking items from nearby chests and barrels")
                                .bool(
                                        "explore",
                                        "Allow searching further away and digging down when a resource is not nearby")
                                .enumeration("scope", "Search budget", "local", "local", "expedition")
                                .required("item")
                                .build();
                    }

                    @Override
                    public AccessLevel minAccess() {
                        return AccessLevel.TRUSTED;
                    }

                    @Override
                    public ToolResult execute(CompanionEntity c, JsonObject a) {
                        String item = itemId(a.get("item").getAsString());
                        int count = a.has("count") && !a.get("count").isJsonNull()
                                ? a.get("count").getAsInt()
                                : 1;
                        String result =
                                planText(c, item, count, flag(a, "use_containers", true), flag(a, "explore", true));
                        if (a.has("scope") && "expedition".equals(a.get("scope").getAsString())) {
                            var policy = taskContext(c).resourceSearchSettings();
                            result += "; expedition search policy: " + policy.expeditionRadius() + " blocks, "
                                    + policy.expeditionTicks()
                                    + " ticks, owner only and disabled unless enabled by the server";
                        }
                        return ToolResult.ok(result);
                    }
                },
                metadata("plan_obtain"));
    }

    private static ObtainTask callerObtain(
            CompanionEntity companion, Caller caller, JsonObject args, ObtainSubtaskFactory subtaskFactory) {
        String scope = args.has("scope") && !args.get("scope").isJsonNull()
                ? args.get("scope").getAsString()
                : "local";
        if (!scope.equals("local") && !scope.equals("expedition"))
            throw new IllegalArgumentException("scope must be local or expedition");
        TaskContext context = taskContext(companion);
        var config = context.resourceSearchSettings();
        if (scope.equals("expedition")
                && (caller.access() != AccessLevel.OWNER
                        || !config.allowExpeditions()
                        || !config.chunkLoadingEnabled()))
            throw new IllegalArgumentException("expeditions are disabled or owner-only");
        int radius = scope.equals("expedition") ? config.expeditionRadius() : config.localRadius();
        int ticks = scope.equals("expedition") ? config.expeditionTicks() : config.localTicks();
        TradingContext trading = null;
        if (caller.access() == AccessLevel.OWNER && caller.uuid() != null && caller.tradingScope() != null) {
            TradeAuthority authority = context.tradeAuthorityFor(caller.uuid());
            var budget = authority.budgetFor(caller.tradingScope()).orElse(null);
            if (budget != null) {
                List<TraderRecord> eligible = context.tradeBook(caller.uuid()).snapshot().traders().stream()
                        .map(trader -> {
                            var offers = trader.offers().stream()
                                    .filter(offer ->
                                            authority.permitsPayment(caller.tradingScope(), offer.fingerprint()))
                                    .toList();
                            return new TraderRecord(
                                    trader.id(),
                                    trader.entityType(),
                                    trader.dimension(),
                                    trader.x(),
                                    trader.y(),
                                    trader.z(),
                                    trader.label(),
                                    trader.profession(),
                                    trader.level(),
                                    trader.firstSeen(),
                                    trader.lastSeen(),
                                    trader.offersSeen(),
                                    trader.revision(),
                                    trader.status(),
                                    offers);
                        })
                        .filter(trader -> !trader.offers().isEmpty())
                        .toList();
                if (!eligible.isEmpty())
                    trading = new TradingContext(
                            caller.tradingScope(),
                            eligible,
                            budget,
                            companion.hands().player().getUUID(),
                            ((ServerLevel) companion.level())
                                    .getServer()
                                    .overworld()
                                    .getGameTime());
            }
        }
        return new ObtainTask(
                itemId(args.get("item").getAsString()),
                args.get("count").getAsInt(),
                flag(args, "use_containers", true),
                flag(args, "explore", true),
                ObtainTask.DEFAULT_MAX_STEPS,
                Math.max(ObtainTask.DEFAULT_MAX_TICKS, ticks),
                radius,
                ObtainTask.DEFAULT_EXPLORE_BLOCKS,
                ticks,
                scope,
                caller,
                trading,
                subtaskFactory);
    }

    private static String planText(
            CompanionEntity companion, String item, int count, boolean useContainers, boolean explore) {
        ServerLevel level = (ServerLevel) companion.level();
        Map<String, Integer> inventory = inventoryMap(companion);
        WorldFacts world = WorldScan.scan(taskContext(companion), WorldScan.BLOCK_RADIUS, useContainers, explore);
        ObtainPlan plan = ObtainPlanner.plan(item, count, inventory, RecipeIndex.of(level), DropIndex.of(level), world);
        if (!plan.actionable()) {
            return "can't get " + count + " " + item + " here: " + ObtainText.missingText(plan.missing(), !explore);
        }
        return ObtainText.planText(plan, item, count, inventory);
    }

    private static boolean flag(JsonObject args, String name, boolean fallback) {
        return args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsBoolean() : fallback;
    }

    private static TaskContext taskContext(CompanionEntity companion) {
        return new TaskContext(companion, (ServerLevel) companion.level());
    }

    private static ToolMeta metadata(String name) {
        String hint = name.equals("obtain_item")
                ? "- To get an item you don't have, prefer obtain_item over long hand-written plans; use plan_obtain to explain what it will take.\n"
                        + "- In creative mode, obtain_item can supply a registered item directly for an authorized owner."
                : "";
        return new ToolMeta(name, ToolGroup.OBTAIN, false, hint, List.of(), Cost.HIGH);
    }

    private static String itemId(String text) {
        Identifier id = Identifier.tryParse(text.strip());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("unknown item: " + text);
        }
        return id.toString();
    }

    private static Map<String, Integer> inventoryMap(CompanionEntity companion) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            account(items, companion.inventory().getItem(slot));
        }
        account(items, companion.getMainHandItem());
        return items;
    }

    private static void account(Map<String, Integer> items, ItemStack stack) {
        if (!stack.isEmpty()) {
            items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
    }
}
