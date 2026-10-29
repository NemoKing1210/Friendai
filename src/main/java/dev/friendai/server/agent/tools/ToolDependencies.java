package dev.friendai.server.agent.tools;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.instructions.minecraft.tool.SkillToolAccess;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import dev.friendai.features.memory.minecraft.tool.TrustAccess;
import dev.friendai.features.placements.minecraft.PlacedService;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.features.trading.minecraft.tool.TradingToolAccess;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.server.agent.FreeTimeDriver;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.execution.tools.QueueToolAccess;
import java.util.Objects;
import java.util.function.Function;

/** Server-owned capabilities and live data suppliers needed by the built-in feature contributors. */
public record ToolDependencies(
        MemoryAccess memory,
        Function<CompanionEntity, PerceptionContext> perceptionContext,
        Function<CompanionEntity, ResourceCandidates> resourceCandidates,
        TradingToolAccess trading,
        QueueToolAccess queue,
        GoalToolAccess goals,
        Function<CompanionEntity, SkillToolAccess> skills,
        Function<CompanionEntity, TrustAccess> trust,
        Function<CompanionEntity, FreeTimeDriver> freeTime,
        Function<CompanionEntity, String> searchHintLine,
        PlaceService places,
        PlacedService placed) {
    public ToolDependencies(
            MemoryAccess memory,
            Function<CompanionEntity, PerceptionContext> perceptionContext,
            Function<CompanionEntity, ResourceCandidates> resourceCandidates,
            TradingToolAccess trading,
            QueueToolAccess queue,
            GoalToolAccess goals,
            Function<CompanionEntity, SkillToolAccess> skills,
            Function<CompanionEntity, TrustAccess> trust,
            Function<CompanionEntity, FreeTimeDriver> freeTime,
            Function<CompanionEntity, String> searchHintLine) {
        this(
                memory,
                perceptionContext,
                resourceCandidates,
                trading,
                queue,
                goals,
                skills,
                trust,
                freeTime,
                searchHintLine,
                PlaceService.unavailable(),
                PlacedService.unavailable());
    }

    public ToolDependencies {
        Objects.requireNonNull(memory);
        Objects.requireNonNull(perceptionContext);
        Objects.requireNonNull(resourceCandidates);
        Objects.requireNonNull(trading);
        Objects.requireNonNull(queue);
        Objects.requireNonNull(goals);
        Objects.requireNonNull(skills);
        Objects.requireNonNull(trust);
        Objects.requireNonNull(freeTime);
        Objects.requireNonNull(searchHintLine);
        Objects.requireNonNull(places);
        Objects.requireNonNull(placed);
    }
}
