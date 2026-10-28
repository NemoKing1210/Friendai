package dev.friendai.tool.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.features.interaction.minecraft.tool.BodyTools;
import dev.friendai.features.interaction.minecraft.tool.InteractionTools;
import dev.friendai.features.inventory.minecraft.tool.InventoryTools;
import dev.friendai.features.inventory.minecraft.tool.StorageTools;
import dev.friendai.features.mining.minecraft.tool.GatheringTools;
import dev.friendai.features.navigation.minecraft.tool.MovementTools;
import dev.friendai.features.navigation.minecraft.tool.RelativeMovementTools;
import dev.friendai.perception.PerceptionContext;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** The companion's skills as tools the LLM can call, grouped by domain. */
public final class FriendaiTools {
    /** A built registry plus task definitions used to restore saved queue jobs. */
    public record Builtins(
            ToolRegistry<CompanionEntity> registry,
            Map<String, TaskToolDef> plannable,
            Map<String, TaskToolDef> taskTools) {}

    private FriendaiTools() {}

    /**
     * Queues a plan the way {@code queue_add} does, for callers outside the model's turn (free time). The plan is
     * not linked to the owner's goal when {@code linkGoal} is false, and any nested action using a {@code denied} tool
     * refuses the whole plan. Never throws for a bad plan.
     */
    public static dev.friendai.execution.tool.ToolResult addPlan(
            CompanionEntity companion,
            dev.friendai.execution.tool.Caller caller,
            com.google.gson.JsonObject args,
            Map<String, TaskToolDef> plannable,
            boolean linkGoal,
            java.util.Set<String> denied) {
        return QueueTools.addPlan(companion, caller, args, plannable, linkGoal, denied);
    }

    public static ToolRegistry<CompanionEntity> create(IntSupplier mineRadius) {
        return create(mineRadius, MemoryAccess.DISABLED);
    }

    /**
     * @param mineRadius search radius for {@code mine_blocks}, read on every call so config changes apply live
     * @param memory long-term memory; {@link MemoryAccess#DISABLED} leaves the memory tools out
     */
    public static ToolRegistry<CompanionEntity> create(IntSupplier mineRadius, MemoryAccess memory) {
        return build(mineRadius, mineRadius, memory).registry();
    }

    /** As {@link #create(IntSupplier, MemoryAccess)}, with its own search radius for the tree tools. */
    public static ToolRegistry<CompanionEntity> create(
            IntSupplier mineRadius, IntSupplier treeRadius, MemoryAccess memory) {
        return build(mineRadius, treeRadius, memory).registry();
    }

    /** Builds the registry and the plan-step lookup together; the tree tools use the mine radius. */
    public static Builtins build(IntSupplier mineRadius, MemoryAccess memory) {
        return build(mineRadius, mineRadius, memory);
    }

    /**
     * @param treeRadius default search radius for {@code find_trees} and {@code fell_tree}, read on every call
     */
    public static Builtins build(IntSupplier mineRadius, IntSupplier treeRadius, MemoryAccess memory) {
        return build(mineRadius, treeRadius, memory, null);
    }

    /** Builds the runtime registry with its server-owned perception dependencies. */
    public static Builtins build(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            MemoryAccess memory,
            Supplier<PerceptionContext> perceptionContext) {
        return build(mineRadius, treeRadius, memory, perceptionContext, () -> true, () -> true);
    }

    /** Builds the runtime registry with live mining and tree settings from the server-owned settings snapshot. */
    public static Builtins build(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            MemoryAccess memory,
            Supplier<PerceptionContext> perceptionContext,
            BooleanSupplier mineCollectDrops,
            BooleanSupplier fellCollectDrops) {
        ToolRegistry<CompanionEntity> registry = new ToolRegistry<>();
        List<TaskToolDef> defs = new ArrayList<>();
        FeatureToolRegistry featureTools = new FeatureToolRegistry(registry, definition -> {
            TaskToolDef task = legacyTaskDefinition(definition);
            defs.add(task);
            return ToolKit.taskTool(task);
        });
        List<FeatureToolContributor> contributors =
                List.of(new MovementTools(), new RelativeMovementTools(), new BodyTools(), new InteractionTools());
        contributors.forEach(contributor -> contributor.register(featureTools));
        new GatheringTools(mineRadius, treeRadius, mineCollectDrops, fellCollectDrops).register(featureTools);
        new InventoryTools().register(featureTools);
        BuildingTools.register(registry, defs);
        new StorageTools().register(featureTools);
        CraftingTools.register(registry, defs);
        CookingTools.register(registry, defs);
        EnchantTools.register(registry, defs);
        CombatTools.register(registry, defs);
        FarmingTools.register(registry, defs);
        AnimalTools.register(registry, defs);
        ObtainTools.register(registry, defs);
        ResourceTools.register(registry, defs);
        PlaceTools.register(registry, defs);
        BuildTools.register(registry, defs);
        StructureTools.register(registry, defs);
        InfoTools.register(registry, defs, mineRadius, treeRadius, perceptionContext);
        MemoryTools.register(registry, memory);
        TrustTools.register(registry);
        ControlTools.register(registry, defs);
        TradingTools.register(registry, defs);
        QueueTools.register(registry, defs);
        FireTools.register(registry, defs);
        CreativeTools.register(registry, defs);
        GoalTools.register(registry);
        SkillTools.register(registry);
        RoutingTools.register(registry);
        FreeTimeTools.register(registry);
        Map<String, TaskToolDef> plannable = new LinkedHashMap<>();
        for (TaskToolDef def : defs) {
            if (def.plannable()) {
                plannable.put(def.name(), def);
            }
        }
        Map<String, TaskToolDef> taskTools = new LinkedHashMap<>();
        for (TaskToolDef def : defs) taskTools.put(def.name(), def);
        return new Builtins(registry, Map.copyOf(plannable), Map.copyOf(taskTools));
    }

    private static TaskToolDef legacyTaskDefinition(TaskToolDefinition definition) {
        TaskToolDefinition.QueueTraits queueTraits = definition.queueTraits();
        return new TaskToolDef(
                definition.name(),
                definition.description(),
                definition.schema(),
                definition.factory() == null ? null : definition.factory()::create,
                definition.plannable(),
                definition.minAccess(),
                new TaskToolDef.QueueTraits(
                        queueTraits.interrupts(), queueTraits.mergeableCountArg(), queueTraits.requiresTradingScope()),
                definition.callerFactory() == null ? null : definition.callerFactory()::create,
                definition.creativeSensitive());
    }
}
