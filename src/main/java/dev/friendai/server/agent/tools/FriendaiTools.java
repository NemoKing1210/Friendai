package dev.friendai.server.agent.tools;

import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.features.acquisition.minecraft.tool.ObtainTools;
import dev.friendai.features.animals.minecraft.tool.AnimalTools;
import dev.friendai.features.combat.minecraft.tool.CombatTools;
import dev.friendai.features.construction.minecraft.tool.ConstructionTools;
import dev.friendai.features.cooking.minecraft.tool.CookingTools;
import dev.friendai.features.crafting.minecraft.tool.CraftingTools;
import dev.friendai.features.creative.minecraft.tool.CreativeTools;
import dev.friendai.features.enchanting.minecraft.tool.EnchantTools;
import dev.friendai.features.farming.minecraft.tool.FarmingTools;
import dev.friendai.features.fire.minecraft.tool.FireTools;
import dev.friendai.features.instructions.minecraft.tool.SkillToolAccess;
import dev.friendai.features.instructions.minecraft.tool.SkillTools;
import dev.friendai.features.interaction.minecraft.tool.BodyTools;
import dev.friendai.features.interaction.minecraft.tool.CommandTools;
import dev.friendai.features.interaction.minecraft.tool.InteractionTools;
import dev.friendai.features.inventory.minecraft.tool.InventoryTools;
import dev.friendai.features.inventory.minecraft.tool.StorageTools;
import dev.friendai.features.knowledge.minecraft.tool.ResourceTools;
import dev.friendai.features.memory.CompanionProfile;
import dev.friendai.features.memory.CompanionProfileRepository;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import dev.friendai.features.memory.minecraft.tool.MemoryTools;
import dev.friendai.features.memory.minecraft.tool.TrustAccess;
import dev.friendai.features.memory.minecraft.tool.TrustTools;
import dev.friendai.features.mining.minecraft.tool.BlockInfoTools;
import dev.friendai.features.mining.minecraft.tool.GatheringTools;
import dev.friendai.features.navigation.minecraft.tool.FlightTools;
import dev.friendai.features.navigation.minecraft.tool.MovementTools;
import dev.friendai.features.navigation.minecraft.tool.RelativeMovementTools;
import dev.friendai.features.placements.minecraft.tool.PlacedTools;
import dev.friendai.features.places.minecraft.tool.PlaceTools;
import dev.friendai.features.survival.minecraft.tool.SurvivalTools;
import dev.friendai.features.trading.minecraft.tool.TradingToolAccess;
import dev.friendai.features.trading.minecraft.tool.TradingTools;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.execution.tools.ControlTools;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.MinecraftObtainSubtaskFactory;
import dev.friendai.server.execution.tools.QueueToolAccess;
import dev.friendai.server.execution.tools.QueueTools;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.server.execution.tools.TaskToolRuntime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Builds the fixed tool registry from the feature-owned contributors for one server runtime. */
public final class FriendaiTools {
    /** A registry plus task definitions used by plan parsing and persisted-task restoration. */
    public record Builtins(
            ToolRegistry<CompanionEntity> registry,
            Map<String, TaskToolDefinition> plannable,
            Map<String, TaskToolDefinition> taskTools,
            Set<String> restorableTaskNames,
            ToolCatalog catalog) {}

    private FriendaiTools() {}

    public static ToolRegistry<CompanionEntity> create(IntSupplier mineRadius) {
        return create(mineRadius, MemoryAccess.DISABLED);
    }

    public static ToolRegistry<CompanionEntity> create(IntSupplier mineRadius, MemoryAccess memory) {
        return build(mineRadius, mineRadius, memory).registry();
    }

    public static ToolRegistry<CompanionEntity> create(
            IntSupplier mineRadius, IntSupplier treeRadius, MemoryAccess memory) {
        return build(mineRadius, treeRadius, memory).registry();
    }

    public static Builtins build(IntSupplier mineRadius, MemoryAccess memory) {
        return build(mineRadius, mineRadius, memory);
    }

    public static Builtins build(IntSupplier mineRadius, IntSupplier treeRadius, MemoryAccess memory) {
        return build(
                mineRadius,
                treeRadius,
                defaultDependencies(memory, null, null, TradingToolAccess.UNAVAILABLE),
                () -> true,
                () -> true);
    }

    /** Builds a registry for the live services owned by one server runtime. */
    public static Builtins build(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            ToolDependencies dependencies,
            BooleanSupplier mineCollectDrops,
            BooleanSupplier fellCollectDrops) {
        ToolRegistry<CompanionEntity> registry = new ToolRegistry<>();
        MinecraftObtainSubtaskFactory obtainSubtasks = new MinecraftObtainSubtaskFactory();
        FeatureToolRegistry featureTools =
                new FeatureToolRegistry(registry, definition -> TaskToolRuntime.tool(definition, dependencies.queue()));

        List<FeatureToolContributor> featureGroups = List.of(
                new MovementTools(),
                new RelativeMovementTools(),
                new BodyTools(),
                new InteractionTools(),
                new GatheringTools(
                        mineRadius,
                        treeRadius,
                        mineCollectDrops,
                        fellCollectDrops,
                        dependencies.resourceCandidates(),
                        dependencies.places()),
                new InventoryTools(),
                new ObtainTools(obtainSubtasks),
                new ResourceTools(obtainSubtasks),
                new ConstructionTools(obtainSubtasks::createForBuild, dependencies.placed()),
                new CookingTools(obtainSubtasks),
                new FarmingTools(dependencies.places()),
                new EnchantTools(),
                new CombatTools(),
                new SurvivalTools(),
                new AnimalTools(),
                new PlaceTools(dependencies.places()),
                new StorageTools(),
                new CraftingTools(),
                new BlockInfoTools(mineRadius, treeRadius, dependencies.resourceCandidates(), dependencies.places()),
                new PlacedTools(dependencies.placed()),
                new MemoryTools(dependencies.memory()),
                new TrustTools(dependencies.trust()),
                new ControlTools(dependencies.profiles()),
                new TradingTools(dependencies.trading()),
                new FireTools(),
                new CreativeTools(),
                new FlightTools(),
                new CommandTools());
        featureGroups.forEach(contributor -> contributor.register(featureTools));

        // Queue schemas are derived after all task contributions, so every plannable feature is available.
        new QueueTools(dependencies.queue()).register(featureTools);
        new GoalTools(dependencies.goals()).register(featureTools);
        new SkillTools(dependencies.skills()).register(featureTools);
        new PerceptionTools(
                        dependencies.perceptionContext(),
                        dependencies.resourceCandidates(),
                        dependencies.searchHintLine())
                .register(featureTools);
        new RoutingTools().register(featureTools);
        new FreeTimeTools(dependencies.freeTime()).register(featureTools);

        Map<String, TaskToolDefinition> taskTools = featureTools.taskDefinitions();
        Map<String, TaskToolDefinition> plannable = new LinkedHashMap<>();
        taskTools.forEach((name, definition) -> {
            if (definition.plannable()) plannable.put(name, definition);
        });
        return new Builtins(
                registry,
                Map.copyOf(plannable),
                taskTools,
                featureTools.restorableTaskNames(),
                new ToolCatalog(featureTools.catalogMetadata()));
    }

    /** Convenience overload used by focused tests that supply only perception and trading dependencies. */
    public static Builtins build(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            MemoryAccess memory,
            Supplier<PerceptionContext> perceptionContext) {
        return build(mineRadius, treeRadius, memory, perceptionContext, () -> true, () -> true);
    }

    /** Builds the runtime registry with live mining and tree settings from the server settings snapshot. */
    public static Builtins build(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            MemoryAccess memory,
            Supplier<PerceptionContext> perceptionContext,
            BooleanSupplier mineCollectDrops,
            BooleanSupplier fellCollectDrops) {
        return build(
                mineRadius,
                treeRadius,
                memory,
                perceptionContext,
                mineCollectDrops,
                fellCollectDrops,
                null,
                TradingToolAccess.UNAVAILABLE);
    }

    /** Convenience overload retained for GameTests that compose an explicit resource/trading service. */
    public static Builtins build(
            IntSupplier mineRadius,
            IntSupplier treeRadius,
            MemoryAccess memory,
            Supplier<PerceptionContext> perceptionContext,
            BooleanSupplier mineCollectDrops,
            BooleanSupplier fellCollectDrops,
            ResourceCandidates resourceCandidates,
            TradingToolAccess tradingToolAccess) {
        return build(
                mineRadius,
                treeRadius,
                defaultDependencies(memory, perceptionContext, resourceCandidates, tradingToolAccess),
                mineCollectDrops,
                fellCollectDrops);
    }

    private static ToolDependencies defaultDependencies(
            MemoryAccess memory,
            Supplier<PerceptionContext> perceptionContext,
            ResourceCandidates resources,
            TradingToolAccess trading) {
        QueueToolAccess queue = new QueueToolAccess() {
            @Override
            public dev.friendai.execution.tool.Caller currentCaller(
                    CompanionEntity companion, java.util.UUID principal) {
                return new dev.friendai.execution.tool.Caller(
                        principal,
                        "",
                        principal != null && principal.equals(companion.ownerUuid())
                                ? dev.friendai.execution.tool.AccessLevel.OWNER
                                : dev.friendai.execution.tool.AccessLevel.GUEST);
            }

            @Override
            public void linkPlan(CompanionEntity companion, long planId) {
                throw new IllegalStateException("queue access is unavailable");
            }
        };
        GoalToolAccess goals = new GoalToolAccess() {
            @Override
            public dev.friendai.agent.goal.GoalService goals(net.minecraft.server.level.ServerLevel level) {
                throw new IllegalStateException("goal access is unavailable");
            }

            @Override
            public void skillOutcome(
                    net.minecraft.server.level.ServerLevel level,
                    java.util.UUID owner,
                    String source,
                    dev.friendai.foundation.teaching.TeachingContext.OutcomeKind kind,
                    String summary) {}
        };
        SkillToolAccess skills = new SkillToolAccess() {
            @Override
            public dev.friendai.execution.tool.ToolResult request(java.util.UUID owner, String id) {
                return dev.friendai.execution.tool.ToolResult.error("Skill access is unavailable.");
            }

            @Override
            public dev.friendai.execution.tool.ToolResult teach(
                    java.util.UUID owner, dev.friendai.execution.tool.Caller caller, com.google.gson.JsonObject args) {
                return dev.friendai.execution.tool.ToolResult.error("Skill access is unavailable.");
            }
        };
        Map<UUID, CompanionProfile> profileValues = new HashMap<>();
        Map<UUID, Long> profileRevisions = new HashMap<>();
        CompanionProfileRepository profiles = new CompanionProfileRepository() {
            @Override
            public CompanionProfile profile(UUID owner) {
                return profileValues.getOrDefault(owner, CompanionProfile.EMPTY);
            }

            @Override
            public void save(UUID owner, CompanionProfile profile) {
                profileValues.put(owner, profile);
                profileRevisions.merge(owner, 1L, Long::sum);
            }

            @Override
            public long revision(UUID owner) {
                return profileRevisions.getOrDefault(owner, 0L);
            }
        };
        return new ToolDependencies(
                memory,
                profiles,
                companion -> perceptionContext == null ? null : perceptionContext.get(),
                companion -> resources,
                trading == null ? TradingToolAccess.UNAVAILABLE : trading,
                queue,
                goals,
                companion -> skills,
                companion -> unavailableTrust(),
                companion -> null,
                companion -> "");
    }

    private static TrustAccess unavailableTrust() {
        return new TrustAccess() {
            @Override
            public java.util.UUID ownerUuid(CompanionEntity companion) {
                return null;
            }

            @Override
            public boolean ownerOnline(CompanionEntity companion) {
                return false;
            }

            @Override
            public java.util.Optional<dev.friendai.features.memory.PlayerRef> findOnlinePlayer(String name) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<dev.friendai.features.memory.PlayerRecord> findKnownPlayer(
                    java.util.UUID owner, String name) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean isTrusted(java.util.UUID owner, java.util.UUID target) {
                return false;
            }

            @Override
            public dev.friendai.features.memory.TrustRequest request(
                    CompanionEntity companion, java.util.UUID target, String targetName, boolean trust) {
                throw new IllegalStateException("trust access is unavailable");
            }
        };
    }
}
