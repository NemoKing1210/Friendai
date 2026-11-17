package dev.friendai.server.agent;

import com.google.gson.JsonObject;
import dev.friendai.agent.autonomy.AutonomyFacts;
import dev.friendai.agent.autonomy.AutonomySettings;
import dev.friendai.agent.autonomy.Executor;
import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.FreeTimeLogEvents;
import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.autonomy.Need;
import dev.friendai.agent.autonomy.NeedKind;
import dev.friendai.agent.autonomy.NeedsAssessor;
import dev.friendai.agent.autonomy.ReflexPlans;
import dev.friendai.agent.autonomy.ReflexStep;
import dev.friendai.agent.autonomy.ShiftPolicy;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.construction.minecraft.build.BlueprintResolver;
import dev.friendai.features.construction.minecraft.build.BlueprintStore;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Queues the deterministic free-time templates: one named need, or the first agenda entry that has one. */
final class ReflexQueue {
    /** What the queue needs from the driver: the duplicate guard, the plan queue and the shift bookkeeping. */
    interface Host {
        Optional<String> refusalToStart(CompanionEntity companion, boolean ownerGoalActive, List<ReflexStep> steps);

        Optional<Long> queue(
                UUID owner,
                CompanionEntity companion,
                String goal,
                List<ReflexStep> steps,
                int minutes,
                Map<String, TaskToolDefinition> tools);

        /** The stage a shift started from no longer applies. */
        void stageCleared(UUID owner);

        void factsInvalidated(UUID owner);
    }

    private final Host host;
    private final FreeTimeService service;
    private final CompanionSessions brains;
    private final Map<String, TaskToolDefinition> taskTools;

    ReflexQueue(
            Host host, FreeTimeService service, CompanionSessions brains, Map<String, TaskToolDefinition> taskTools) {
        this.host = host;
        this.service = service;
        this.brains = brains;
        this.taskTools = taskTools;
    }

    boolean template(
            UUID owner,
            CompanionEntity companion,
            Need need,
            Optional<List<ReflexStep>> steps,
            AutonomySettings s,
            long nowMillis,
            boolean ownerGoalActive) {
        if (need.kind() == NeedKind.SHELTER
                && steps.isPresent()
                && steps.get().getFirst().tool().equals("build_structure")) {
            steps = localShelter(companion, steps.get().getFirst());
        }
        if (steps.isEmpty()) {
            service.cooldown(owner, need.kind(), nowMillis + s.failCooldownMin() * 60_000L);
            return false;
        }
        if (host.refusalToStart(companion, ownerGoalActive, steps.get()).isPresent()) {
            return false; // the queue already has this work, or the owner's work comes first
        }
        boolean shelter = need.kind() == NeedKind.SHELTER || need.kind() == NeedKind.SURVIVE;
        int minutes = shelter ? FreeTimeDriver.SHELTER_MINUTES : s.shiftMinutes();
        Optional<Long> plan =
                host.queue(owner, companion, "free time: " + need.text(), steps.get(), minutes, taskTools);
        if (plan.isEmpty()) {
            service.cooldown(owner, need.kind(), nowMillis + s.failCooldownMin() * 60_000L);
            return false;
        }
        host.stageCleared(owner);
        service.beginShift(
                owner, need.text(), List.of(), "", need.kind(), true, nowMillis, nowMillis + minutes * 60_000L);
        service.linkPlan(owner, plan.get());
        brains.logFreeTime(FreeTimeLogEvents.start(
                owner,
                companion.getUUID(),
                brains.logSession(owner),
                plan.get(),
                need.kind().name(),
                need.text()));
        host.factsInvalidated(owner);
        return true;
    }

    /** The template is five blocks wide with a south-facing doorway. New shelters go north of the companion. */
    private static Optional<List<ReflexStep>> localShelter(CompanionEntity companion, ReflexStep build) {
        ServerLevel level = (ServerLevel) companion.level();
        JsonObject buildArgs = build.args();
        var blueprint = BlueprintResolver.resolve(buildArgs, level, companion.ownerUuid())
                .blueprint();
        if (blueprint.isEmpty()) return Optional.empty();
        BlockPos origin = BlueprintStore.forLevel(level)
                .findResumable(
                        blueprint.get(),
                        companion.ownerUuid(),
                        level.dimension().identifier().toString(),
                        null)
                .map(BlueprintStore.InProgress::origin)
                .orElseGet(
                        () -> newShelterOrigin(companion, level, blueprint.get().height()));
        if (origin == null) return Optional.empty();
        buildArgs.addProperty("x", origin.getX());
        buildArgs.addProperty("y", origin.getY());
        buildArgs.addProperty("z", origin.getZ());
        JsonObject inside = new JsonObject();
        inside.addProperty("x", origin.getX() + 2.5);
        inside.addProperty("y", origin.getY() + 1);
        inside.addProperty("z", origin.getZ() + 2.5);
        return Optional.of(List.of(
                new ReflexStep(build.tool(), buildArgs),
                new ReflexStep("go_to", inside),
                new ReflexStep("stay", null)));
    }

    private static BlockPos newShelterOrigin(CompanionEntity companion, ServerLevel level, int height) {
        BlockPos center = companion.blockPosition().north(5);
        if (!level.hasChunkAt(center.offset(-2, 0, -2))
                || !level.hasChunkAt(center.offset(-2, 0, 2))
                || !level.hasChunkAt(center.offset(2, 0, -2))
                || !level.hasChunkAt(center.offset(2, 0, 2))) {
            return null;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
        return y + height - 1 > level.getMaxY() ? null : new BlockPos(center.getX() - 2, y, center.getZ() - 2);
    }

    /**
     * Starts the first agenda entry that has a deterministic template, with reflexes first.
     */
    boolean firstTemplate(
            UUID owner,
            CompanionEntity companion,
            AutonomyFacts f,
            AutonomySettings s,
            long nowMillis,
            boolean ownerGoalActive) {
        FreeTime state = service.state(owner);
        List<Need> needs = NeedsAssessor.assess(f, state, s);
        for (Need need : needs) {
            if (need.executor() != Executor.REFLEX) {
                continue;
            }
            Optional<List<ReflexStep>> steps = ReflexPlans.plan(need, f, s);
            if (steps.isPresent() && template(owner, companion, need, steps, s, nowMillis, ownerGoalActive)) {
                return true;
            }
        }
        for (NeedKind kind : ShiftPolicy.FALLBACK_ORDER) {
            for (Need need : needs) {
                if (need.kind() != kind) {
                    continue;
                }
                Optional<List<ReflexStep>> steps = ReflexPlans.fallback(need, f);
                if (steps.isPresent() && template(owner, companion, need, steps, s, nowMillis, ownerGoalActive)) {
                    return true;
                }
            }
        }
        return false;
    }
}
