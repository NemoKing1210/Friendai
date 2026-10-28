package dev.friendai.server.execution.persistence;

import com.google.gson.JsonObject;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanRecord;
import dev.friendai.execution.queue.QueueTradeScope;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.construction.blueprint.Blueprint;
import dev.friendai.features.construction.blueprint.PartKind;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import dev.friendai.task.build.BlueprintStore;
import dev.friendai.task.build.Placement;
import dev.friendai.task.builtin.BuildTask;
import dev.friendai.task.builtin.FollowTask;
import dev.friendai.task.builtin.GoToTask;
import dev.friendai.task.builtin.StayTask;
import dev.friendai.task.builtin.TendFarmTask;
import dev.friendai.task.plan.PlanEntry;
import dev.friendai.task.plan.PlanSave;
import dev.friendai.task.plan.PlanStepFactory;
import dev.friendai.task.plan.SequenceTask;
import dev.friendai.task.plan.StepFactory;
import dev.friendai.tool.builtin.TaskToolDef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;

/** Resolves persisted tool/task keys against this server's current fixed contribution set. */
public final class TaskRestoreRegistry {
    private final Map<String, TaskToolDef> taskTools;
    private final Map<String, TaskToolDef> planTools;
    private final Supplier<ServerSettings> settings;
    private final BiFunction<UUID, UUID, Caller> callers;

    public TaskRestoreRegistry(
            Map<String, TaskToolDef> taskTools,
            Map<String, TaskToolDef> planTools,
            Supplier<ServerSettings> settings,
            BiFunction<UUID, UUID, Caller> callers) {
        this.taskTools = Map.copyOf(taskTools);
        this.planTools = Map.copyOf(planTools);
        this.settings = settings;
        this.callers = callers;
    }

    public Map<Long, StepFactory> stepFactories(JobQueue queue) {
        Map<Long, StepFactory> factories = new HashMap<>();
        for (Job job : queue.jobs()) {
            TaskToolDef def = taskTools.get(job.tool());
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
            Map<String, TaskToolDef> definitions = new HashMap<>();
            collectPlanTools(plan.definition().nodes(), definitions);
            Map<String, PlanStepFactory> byTool = new HashMap<>();
            for (var entry : definitions.entrySet()) {
                TaskToolDef def = entry.getValue();
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
        Map<String, TaskToolDef> definitions = new HashMap<>();
        collectPlanTools(plan.definition().nodes(), definitions);
        return definitions.values().stream().allMatch(def -> def != null && def.plannable());
    }

    public boolean authorized(Job job, UUID owner) {
        TaskToolDef def = taskTools.get(job.tool());
        return def != null && callers.apply(owner, job.principal()).access().allows(def.minAccess());
    }

    public boolean requiresFreshAuthority(Job job) {
        TaskToolDef def = taskTools.get(job.tool());
        return QueueTradeScope.requiresFreshAuthority(
                job.args(), def != null && def.queueTraits().requiresTradingScope());
    }

    public boolean requiresFreshAuthority(PlanRecord plan) {
        return plan.definition() != null
                && structuredPlanNeedsFreshAuthority(plan.definition().nodes());
    }

    public Optional<Task> restore(TaskState state, dev.friendai.entity.CompanionEntity companion) {
        if (state == null || !(companion.level() instanceof ServerLevel level)) return Optional.empty();
        if (state.type().equals("run_plan")) return restorePlan(state.data(), companion, level);
        return restoreCheckpoint(state, level);
    }

    /** Restores a checkpoint by its stable task key; a world is required only for world-backed tasks. */
    public static Optional<Task> restoreCheckpoint(TaskState saved, ServerLevel level) {
        if (saved == null) return Optional.empty();
        return restoreBuiltin(saved, level);
    }

    public boolean interruptedSearch(Job job) {
        if (job.tool().equals("search_resource") || job.tool().equals("find_target")) return true;
        return job.tool().equals("obtain_item")
                && job.args().has("explore")
                && job.args().get("explore").getAsBoolean();
    }

    private Task create(TaskToolDef def, TaskContext context, UUID principal, JsonObject args) {
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
            TaskToolDef def = planTools.get(step.tool());
            return QueueTradeScope.requiresFreshAuthority(
                    step.args(), def != null && def.queueTraits().requiresTradingScope());
        })) return Optional.empty();
        List<PlanEntry> entries = new ArrayList<>(data.steps().size());
        for (PlanSave.Step step : data.steps()) {
            TaskToolDef def = planTools.get(step.tool());
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

    private static Optional<Task> restoreBuiltin(TaskState saved, ServerLevel level) {
        try {
            String data = saved.data();
            return switch (saved.type()) {
                case "follow" -> Optional.of(new FollowTask(true));
                case "stay" -> Optional.of(new StayTask());
                case "go_to" -> {
                    String[] p = data.split(",");
                    if (p.length != 3) yield Optional.empty();
                    yield Optional.of(new GoToTask(
                            new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]))));
                }
                case "tend_farm" -> {
                    String[] p = data.split(",");
                    if (p.length != 4) yield Optional.empty();
                    yield Optional.of(new TendFarmTask(
                            new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])),
                            Integer.parseInt(p[3])));
                }
                case "build_structure" -> restoreBuild(data, level);
                default -> Optional.empty();
            };
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<Task> restoreBuild(String saved, ServerLevel level) {
        try {
            if (level == null) return Optional.empty();
            String[] p = saved.split(",");
            if (p.length != 7 && p.length != 8 || !BlueprintStore.validBuildId(p[0])) return Optional.empty();
            BlockPos origin = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            Direction facing = switch (p[4]) {
                case "north" -> Direction.NORTH;
                case "south" -> Direction.SOUTH;
                case "east" -> Direction.EAST;
                case "west" -> Direction.WEST;
                default -> null;
            };
            if (facing == null
                    || !(p[5].equals("true") || p[5].equals("false"))
                    || !(p[6].equals("true") || p[6].equals("false"))) return Optional.empty();
            Set<PartKind> parts = new HashSet<>();
            if (p.length == 8 && !p[7].isBlank()) {
                for (String name : p[7].split("\\+")) {
                    Optional<PartKind> kind = PartKind.parse(name);
                    if (kind.isEmpty()) return Optional.empty();
                    parts.add(kind.get());
                }
            }
            Rotation rotation = Placement.rotationFor(facing);
            BlueprintStore.InProgress inProgress =
                    BlueprintStore.forLevel(level).loadInProgress(p[0]).orElse(null);
            if (inProgress == null || inProgress.rotation() != rotation) return Optional.empty();
            Blueprint blueprint = inProgress.blueprint();
            return Optional.of(BuildTask.resume(
                    blueprint,
                    origin,
                    rotation,
                    Boolean.parseBoolean(p[5]),
                    Boolean.parseBoolean(p[6]),
                    inProgress.scaffold(),
                    p[0],
                    Set.copyOf(parts)));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private void collectPlanTools(List<PlanNode> nodes, Map<String, TaskToolDef> result) {
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
                TaskToolDef def = planTools.get(action.step().tool());
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
