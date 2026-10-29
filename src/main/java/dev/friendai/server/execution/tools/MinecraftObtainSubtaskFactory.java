package dev.friendai.server.execution.tools;

import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.ObtainStep;
import dev.friendai.features.acquisition.TradingContext;
import dev.friendai.features.acquisition.minecraft.ObtainSubtaskFactory;
import dev.friendai.features.acquisition.minecraft.ResourceSearchTaskFactory;
import dev.friendai.features.acquisition.minecraft.task.ExploreTaskFactory;
import dev.friendai.features.acquisition.minecraft.task.ObtainTask;
import dev.friendai.features.acquisition.minecraft.task.SearchResourceTask;
import dev.friendai.features.acquisition.minecraft.task.VisitResourceTask;
import dev.friendai.features.animals.minecraft.task.MilkTask;
import dev.friendai.features.animals.minecraft.task.ShearTask;
import dev.friendai.features.combat.minecraft.task.HuntTask;
import dev.friendai.features.cooking.minecraft.task.CraftingTaskFactory;
import dev.friendai.features.cooking.minecraft.task.SmeltTask;
import dev.friendai.features.crafting.StationPolicy;
import dev.friendai.features.crafting.minecraft.task.CraftTask;
import dev.friendai.features.exploration.minecraft.task.ExploreTask;
import dev.friendai.features.farming.minecraft.task.FarmTask;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.mining.minecraft.task.MineBlocksTask;
import dev.friendai.features.trading.TradeOrder;
import dev.friendai.features.trading.minecraft.task.InspectTraderTask;
import dev.friendai.features.trading.minecraft.task.TradeTask;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/** App-level constructor for acquisition substeps owned by several world features. */
public final class MinecraftObtainSubtaskFactory
        implements ObtainSubtaskFactory, ExploreTaskFactory, CraftingTaskFactory, ResourceSearchTaskFactory {
    private static final int MINING_RADIUS = 32;
    private static final int HUNT_RADIUS = 24;
    private static final int FARM_RADIUS = 8;
    private static final int SMELT_WAIT_TICKS = 1_200;
    private static final int RESOURCE_RADIUS = 16;

    @Override
    public Created create(TaskContext context, ObtainStep step, Options options) {
        Task task = switch (step) {
            case ObtainStep.Take ignored -> throw new IllegalArgumentException("container steps are acquisition-owned");
            case ObtainStep.Craft craft -> new CraftTask(craft.item(), craft.count(), StationPolicy.AUTO);
            case ObtainStep.Smelt smelt ->
                new SmeltTask(
                        itemMatcher(smelt.input()),
                        smelt.count(),
                        null,
                        null,
                        true,
                        StationPolicy.AUTO,
                        SMELT_WAIT_TICKS,
                        this);
            case ObtainStep.Mine mine -> new MineBlocksTask(blockMatcher(mine.block()), mine.count(), MINING_RADIUS);
            case ObtainStep.VisitResource visit ->
                new VisitResourceTask(visit.siteId(), visit.dimension(), options.exploreTicks());
            case ObtainStep.Hunt hunt ->
                new HuntTask(type -> entityId(type).equals(hunt.mob()), hunt.mob(), hunt.count(), HUNT_RADIUS);
            case ObtainStep.Harvest harvest -> {
                BlockMatcher crop = blockMatcher(harvest.crop());
                var center = context.resources().knownOrVisible(context.companion(), crop, RESOURCE_RADIUS, 1).stream()
                        .findFirst()
                        .orElse(context.companion().blockPosition());
                yield new FarmTask(center, FARM_RADIUS, null, false);
            }
            case ObtainStep.Shear shear -> new ShearTask(entityType(shear.mob()), shear.count());
            case ObtainStep.Milk milk -> new MilkTask(milk.count(), null);
            case ObtainStep.Explore explore ->
                options.caller() == null
                        ? create(
                                explore.block(),
                                explore.down(),
                                explore.depthY(),
                                options.exploreRadius(),
                                options.exploreBlocks(),
                                options.exploreTicks())
                        : new SearchResourceTask(
                                explore.block(), "auto", options.searchScope(), null, options.caller(), this);
            case ObtainStep.Trade trade -> tradeTask(context, trade, options.caller(), options.tradingContext());
            case ObtainStep.InspectTrader inspect -> new InspectTraderTask(inspect.trader());
        };
        if (step instanceof ObtainStep.VisitResource visit && task instanceof VisitResourceTask visitTask) {
            return Created.visitResource(task, visitTask::result);
        }
        return Created.task(task);
    }

    @Override
    public Task create(String item, int count, StationPolicy policy) {
        return new CraftTask(item, count, policy);
    }

    public Task createForBuild(String item, int count) {
        return new ObtainTask(item, count, true, true, this);
    }

    @Override
    public Task create(
            dev.friendai.features.knowledge.search.TargetKind fixedTarget,
            String resource,
            String requestedStrategy,
            String scope,
            String heading,
            Caller caller) {
        return fixedTarget == null
                ? new SearchResourceTask(resource, requestedStrategy, scope, heading, caller, this)
                : new SearchResourceTask(fixedTarget, requestedStrategy, scope, heading, caller, this);
    }

    @Override
    public MiningTask create(String block, boolean down, int depthY, int radius, int maxBlocks, int maxTicks) {
        ExploreTask task = new ExploreTask(blockMatcher(block), down, depthY, radius, maxBlocks, maxTicks);
        return new MiningTask() {
            @Override
            public int excavationBlocks() {
                return task.excavationBlocks();
            }

            @Override
            public String type() {
                return task.type();
            }

            @Override
            public boolean hasTimeout() {
                return task.hasTimeout();
            }

            @Override
            public void start(TaskContext context) {
                task.start(context);
            }

            @Override
            public TaskStatus tick(TaskContext context) {
                return task.tick(context);
            }

            @Override
            public void stop(TaskContext context) {
                task.stop(context);
            }

            @Override
            public String describe() {
                return task.describe();
            }

            @Override
            public String resultSummary() {
                return task.resultSummary();
            }
        };
    }

    private static Task tradeTask(
            TaskContext context, ObtainStep.Trade step, Caller caller, TradingContext tradingContext) {
        if (caller == null
                || tradingContext == null
                || !tradingContext.scopeId().equals(step.scopeId())) {
            throw new IllegalStateException("obtain trade has no captured owner scope");
        }
        var trader = tradingContext.traders().stream()
                .filter(value -> value.id().equals(step.trader()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("planned trader disappeared"));
        var offer = trader.offers().stream()
                .filter(value -> value.ref().equals(step.offerRef()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("planned offer disappeared"));
        var authority = context.sessions().tradeAuthorityFor(caller.uuid());
        if (trader.revision() != step.revision() || !authority.permitsPayment(step.scopeId(), offer.fingerprint())) {
            throw new IllegalStateException("planned offer or payment authority is stale");
        }
        long deadline = authority.deadlineFor(step.scopeId());
        long now = context.level().getServer().overworld().getGameTime();
        if (deadline <= now) throw new IllegalStateException("owner trading scope expired");
        UUID id = UUID.randomUUID();
        int desired = Math.multiplyExact(step.count(), offer.output().count());
        TradeOrder order = new TradeOrder(
                id,
                caller.uuid(),
                step.scopeId(),
                step.trader(),
                step.offerRef(),
                step.revision(),
                desired,
                tradingContext.budget(),
                Set.of(offer.fingerprint()),
                true,
                true,
                deadline);
        return new TradeTask(authority.authorize(caller, order));
    }

    private static dev.friendai.task.inventory.ItemMatcher itemMatcher(String id) {
        return dev.friendai.task.inventory.ItemMatcher.parse(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown item: " + id));
    }

    private static BlockMatcher blockMatcher(String id) {
        return BlockMatcher.parse(id).orElseThrow(() -> new IllegalArgumentException("unknown block: " + id));
    }

    private static EntityType<?> entityType(String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(parsed)) {
            throw new IllegalArgumentException("unknown mob: " + id);
        }
        return BuiltInRegistries.ENTITY_TYPE.getValue(parsed);
    }

    private static String entityId(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }
}
