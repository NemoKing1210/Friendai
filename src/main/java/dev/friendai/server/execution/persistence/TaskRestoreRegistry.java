package dev.friendai.server.execution.persistence;

import com.google.gson.JsonObject;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanRecord;
import dev.friendai.execution.queue.QueueTradeScope;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.construction.minecraft.task.BuildCheckpoints;
import dev.friendai.features.farming.minecraft.task.FarmCheckpoints;
import dev.friendai.features.navigation.minecraft.task.NavigationCheckpoints;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.execution.tools.CheckpointRestorer;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import dev.friendai.task.plan.PlanEntry;
import dev.friendai.task.plan.PlanSave;
import dev.friendai.task.plan.PlanStepFactory;
import dev.friendai.task.plan.SequenceTask;
import dev.friendai.task.plan.StepFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;

/** Resolves persisted tool/task keys against this server's current fixed contribution set. */
public final class TaskRestoreRegistry {
    /** Checkpoint decoders composed from the current contribution set, keyed by the persisted task type. */
    private static final Map<String, CheckpointRestorer> BUILTIN_CHECKPOINTS = Map.of(
            "follow",
            NavigationCheckpoints::follow,
            "stay",
            NavigationCheckpoints::stay,
            "go_to",
            NavigationCheckpoints::goTo,
            "tend_farm",
            FarmCheckpoints::tendFarm,
            "build_structure",
            (state, context) -> BuildCheckpoints.build(
                    state,
                    context,
                    dev.friendai.features.construction.minecraft.task.BuildTask.ObtainFactory.UNAVAILABLE));

    private final Map<String, TaskToolDefinition> taskTools;
    private final Map<String, TaskToolDefinition> planTools;
    private final Map<String, CheckpointRestorer> checkpoints;
    private final Supplier<ServerSettings> settings;
    private final BiFunction<UUID, UUID, Caller> callers;

    public TaskRestoreRegistry(
            Map<String, TaskToolDefinition> taskTools,
            Map<String, TaskToolDefinition> planTools,
            Supplier<ServerSettings> settings,
            BiFunction<UUID, UUID, Caller> callers) {
        this(taskTools, planTools, taskTools.keySet(), settings, callers);
    }

    public TaskRestoreRegistry(
            Map<String, TaskToolDefinition> taskTools,
            Map<String, TaskToolDefinition> planTools,
            Set<String> restorableTaskNames,
            Supplier<ServerSettings> settings,
            BiFunction<UUID, UUID, Caller> callers) {
        this.taskTools = restorable(taskTools, restorableTaskNames);
        this.planTools = restorable(planTools, restorableTaskNames);
        this.checkpoints = checkpoints(this.taskTools);
        this.settings = settings;
        this.callers = callers;
    }

    private static Map<String, TaskToolDefinition> restorable(
            Map<String, TaskToolDefinition> tools, Set<String> restorableTaskNames) {
        Map<String, TaskToolDefinition> eligible = new HashMap<>();
        tools.forEach((name, definition) -> {
            if (restorableTaskNames.contains(name)) eligible.put(name, definition);
        });
        return Map.copyOf(eligible);
    }

    public Map<Long, StepFactory> stepFactories(JobQueue queue) {
        Map<Long, StepFactory> factories = new HashMap<>();
        for (Job job : queue.jobs()) {
            TaskToolDefinition def = taskTools.get(job.tool());
            if (def == null) continue;
            JsonObject args = job.args();
            UUID principal = job.principal();
            factories.put(job.id(), context -> create(def, context, principal, args));
        }
        return factories;
    }

    public Map<Long, Map<String, PlanStepFactory>> planFactories(JobQueue queue) {
        Map<Long, Map<String, PlanStepFactory>> result = new HashMap<>();
        for (PlanRecord plan : queue.plans()) {
            if (plan.definition() == null || plan.cursor() == null) continue;
            Map<String, TaskToolDefinition> definitions = new HashMap<>();
            collectPlanTools(plan.definition().nodes(), definitions);
            Map<String, PlanStepFactory> byTool = new HashMap<>();
            for (var entry : definitions.entrySet()) {
                TaskToolDefinition def = entry.getValue();
                if (def == null || !def.plannable()) continue;
                byTool.put(entry.getKey(), new PlanStepFactory() {
                    @Override
                    public Task create(TaskContext context, QueuedStep step) {
                        JsonObject args = step.args();
                        return TaskRestoreRegistry.this.create(def, context, step.principal(), args);
                    }

                    @Override
                    public boolean authorized(TaskContext context, QueuedStep step) {
                        return callers.apply(context.companion().ownerUuid(), step.principal())
                                .access()
                                .allows(def.minAccess());
                    }
                });
            }
            result.put(plan.id(), Map.copyOf(byTool));
        }
        return result;
    }

    public boolean hasTaskTool(String tool) {
        return taskTools.containsKey(tool);
    }

    public boolean hasPlanTools(PlanRecord plan) {
        if (plan.definition() == null) return plan.cursor() == null;
        Map<String, TaskToolDefinition> definitions = new HashMap<>();
        collectPlanTools(plan.definition().nodes(), definitions);
        return definitions.values().stream().allMatch(def -> def != null && def.plannable());
    }

    public boolean authorized(Job job, UUID owner) {
        TaskToolDefinition def = taskTools.get(job.tool());
        return def != null && callers.apply(owner, job.principal()).access().allows(def.minAccess());
    }

    public boolean requiresFreshAuthority(Job job) {
        TaskToolDefinition def = taskTools.get(job.tool());
        return QueueTradeScope.requiresFreshAuthority(
                job.args(), def != null && def.queueTraits().requiresTradingScope());
    }

    public boolean requiresFreshAuthority(PlanRecord plan) {
        return plan.definition() != null
                && structuredPlanNeedsFreshAuthority(plan.definition().nodes());
    }

    public Optional<Task> restore(TaskState state, dev.friendai.entity.CompanionEntity companion) {
        if (state == null || !(companion.level() instanceof ServerLevel level)) return Optional.empty();
        return restore(state, new TaskContext(companion, level));
    }

    /** Restores through the contributed decoders; caller and creative authority are checked before activation. */
    public Optional<Task> restore(TaskState state, TaskContext context) {
        if (state == null || context == null) return Optional.empty();
        if (state.type().equals("run_plan"))
            return restorePlan(state.data(), context.companion(), (ServerLevel) context.level());
        CheckpointRestorer restorer = checkpoints.get(state.type());
        return restorer == null ? Optional.empty() : restorer.restore(state, context);
    }

    /**
     * Level-based restore for tests and diagnostics over the same contributor methods; production restore uses the
     * decoders that task definitions contribute to this registry.
     */
    public static Optional<Task> restoreCheckpoint(TaskState saved, ServerLevel level) {
        if (saved == null) return Optional.empty();
        CheckpointRestorer restorer = BUILTIN_CHECKPOINTS.get(saved.type());
        if (restorer == null) return Optional.empty();
        return restorer.restore(saved, new TaskContext(null, level, null, null, null));
    }

    public boolean interruptedSearch(Job job) {
        if (job.tool().equals("search_resource") || job.tool().equals("find_target")) return true;
        return job.tool().equals("obtain_item")
                && job.args().has("explore")
                && job.args().get("explore").getAsBoolean();
    }

    private Task create(TaskToolDefinition def, TaskContext context, UUID principal, JsonObject args) {
        Caller caller =
                callers.apply(context.companion().ownerUuid(), principal).withTradingScope(QueueTradeScope.read(args));
        return def.callerFactory() == null
                ? def.factory().create(context.companion(), args.deepCopy())
                : def.callerFactory().create(context.companion(), caller, args.deepCopy());
    }

    private Optional<Task> restorePlan(String saved, dev.friendai.entity.CompanionEntity companion, ServerLevel level) {
        Optional<PlanSave.Saved> decoded = PlanSave.decode(saved);
        if (decoded.isEmpty()) return Optional.empty();
        PlanSave.Saved data = decoded.get();
        if (data.steps().stream().anyMatch(step -> {
            TaskToolDefinition def = planTools.get(step.tool());
            return QueueTradeScope.requiresFreshAuthority(
                    step.args(), def != null && def.queueTraits().requiresTradingScope());
        })) return Optional.empty();
        List<PlanEntry> entries = new ArrayList<>(data.steps().size());
        for (PlanSave.Step step : data.steps()) {
            TaskToolDefinition def = planTools.get(step.tool());
            if (def == null
                    || !def.plannable()
                    || step.principal() == null
                    || !callers.apply(companion.ownerUuid(), step.principal())
                            .access()
                            .allows(def.minAccess())) return Optional.empty();
            JsonObject args = step.args();
            UUID principal = step.principal();
            entries.add(new PlanEntry(step.tool(), args, principal, context -> {
                Caller caller = callers.apply(context.companion().ownerUuid(), principal)
                        .withTradingScope(QueueTradeScope.read(args));
                if (!caller.access().allows(def.minAccess()))
                    throw new IllegalArgumentException("saved actor no longer has access");
                return def.callerFactory() == null
                        ? def.factory().create(context.companion(), args.deepCopy())
                        : def.callerFactory().create(context.companion(), caller, args.deepCopy());
            }));
        }
        Task active =
                data.active() == null ? null : restore(data.active(), companion).orElse(null);
        if (data.active() != null && active == null) return Optional.empty();
        return Optional.of(SequenceTask.resumed(
                data.goal(),
                entries,
                data.index(),
                data.done(),
                active,
                () -> settings.get().tasks().taskTimeoutSec() * 20));
    }

    private static Map<String, CheckpointRestorer> checkpoints(Map<String, TaskToolDefinition> tools) {
        Map<String, CheckpointRestorer> result = new HashMap<>();
        for (TaskToolDefinition definition : tools.values()) {
            if (definition.checkpointRestorer() == null) continue;
            CheckpointRestorer previous = result.put(definition.checkpointType(), definition.checkpointRestorer());
            if (previous != null)
                throw new IllegalArgumentException("duplicate checkpoint type: " + definition.checkpointType());
        }
        return Map.copyOf(result);
    }

    private void collectPlanTools(List<PlanNode> nodes, Map<String, TaskToolDefinition> result) {
        for (PlanNode node : nodes) {
            if (node instanceof PlanNode.Action action)
                result.put(action.step().tool(), planTools.get(action.step().tool()));
            else if (node instanceof PlanNode.Conditional conditional) {
                collectPlanTools(conditional.thenNodes(), result);
                collectPlanTools(conditional.elseNodes(), result);
            } else collectPlanTools(((PlanNode.Repeat) node).body(), result);
        }
    }

    private boolean structuredPlanNeedsFreshAuthority(List<PlanNode> nodes) {
        for (PlanNode node : nodes) {
            if (node instanceof PlanNode.Action action) {
                TaskToolDefinition def = planTools.get(action.step().tool());
                if (QueueTradeScope.requiresFreshAuthority(
                        action.step().args(), def != null && def.queueTraits().requiresTradingScope())) return true;
            } else if (node instanceof PlanNode.Conditional conditional) {
                if (structuredPlanNeedsFreshAuthority(conditional.thenNodes())
                        || structuredPlanNeedsFreshAuthority(conditional.elseNodes())) return true;
            } else if (structuredPlanNeedsFreshAuthority(((PlanNode.Repeat) node).body())) return true;
        }
        return false;
    }
}
