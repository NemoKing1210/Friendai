package dev.friendai.server.execution.tools;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.QueueTradeScope;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.creative.CreativeModeService;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.plan.StepFactory;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Adapts a feature task definition to the execution queue and current caller authority. */
public final class TaskToolRuntime {
    private TaskToolRuntime() {}

    public static Tool<CompanionEntity> tool(TaskToolDefinition definition, QueueToolAccess access) {
        Objects.requireNonNull(access);
        validateRegistration(definition);
        return new Tool<>() {
            @Override
            public String name() {
                return definition.name();
            }

            @Override
            public String description() {
                return definition.description();
            }

            @Override
            public JsonObject schema() {
                return definition.schema();
            }

            @Override
            public AccessLevel minAccess() {
                return definition.minAccess();
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return execute(companion, new Caller(null, "", definition.minAccess()), args);
            }

            @Override
            public ToolResult execute(CompanionEntity companion, Caller caller, JsonObject args) {
                if (definition.creativeSensitive()
                        && !CreativeModeService.mayStartWorldChangingTask(
                                companion, caller == null ? null : caller.uuid())) {
                    return ToolResult.error(
                            "Creative mode is active; only its online operator owner can request world-changing tasks.");
                }

                Task task;
                try {
                    task = create(definition, companion, caller, args);
                } catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage());
                }

                if (definition.queueTraits().interrupts()) {
                    companion
                            .executionCoordinator()
                            .interrupt(task, definition.name(), caller, definition.creativeSensitive());
                    return ToolResult.ok(
                            "task #" + companion.executionCoordinator().currentId() + " started: " + task.describe());
                }

                QueueTradeScope.EnqueueCapture capture;
                try {
                    capture = QueueTradeScope.captureEnqueue(
                            args,
                            caller == null ? null : caller.tradingScope(),
                            definition.queueTraits().requiresTradingScope(),
                            1);
                } catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage());
                }
                JsonObject queuedArgs = capture.args();
                UUID principal = caller == null ? null : caller.uuid();
                long id;
                try {
                    id = companion
                            .executionCoordinator()
                            .enqueue(
                                    new QueuedStep(
                                            definition.name(),
                                            queuedArgs,
                                            null,
                                            List.of(),
                                            GuardAction.WAIT,
                                            FailurePolicy.STOP_PLAN,
                                            capture.maxAttempts(),
                                            List.of(),
                                            principal,
                                            definition.creativeSensitive()),
                                    guarded(access, definition, principal, queuedArgs));
                } catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage());
                }
                boolean active = companion
                        .executionCoordinator()
                        .queue()
                        .active()
                        .map(job -> job.id() == id)
                        .orElse(false);
                return ToolResult.ok(
                        active
                                ? "task #" + id + " started: " + task.describe()
                                : "job #" + id + " queued behind "
                                        + companion.executionCoordinator().pendingJobs() + " jobs");
            }
        };
    }

    public static boolean requiresFreshAuthority(JsonObject args, TaskToolDefinition definition) {
        return QueueTradeScope.requiresFreshAuthority(
                args, definition.queueTraits().requiresTradingScope());
    }

    public static void validateRegistration(TaskToolDefinition definition) {
        Objects.requireNonNull(definition, "task tool definition");
        if (definition.queueTraits().requiresTradingScope() && definition.callerFactory() == null) {
            throw new IllegalArgumentException("spending tools require a caller-aware task factory");
        }
    }

    /** Resolves the queued job by its stable id at activation, so later optimization or edits are honoured. */
    public static StepFactory queuedStepFactory(
            QueueToolAccess access, long jobId, java.util.Map<String, TaskToolDefinition> definitions) {
        Objects.requireNonNull(access);
        Objects.requireNonNull(definitions);
        return new StepFactory() {
            @Override
            public Task create(TaskContext context) {
                var job = context.companion()
                        .executionCoordinator()
                        .queue()
                        .job(jobId)
                        .orElseThrow(() -> new IllegalStateException("queued task " + jobId + " was removed"));
                TaskToolDefinition definition = definitions.get(job.tool());
                if (definition == null)
                    throw new IllegalStateException("task factory is unavailable for " + job.tool());
                JsonObject args = job.args();
                Caller caller = access.currentCaller(context.companion(), job.principal())
                        .withTradingScope(QueueTradeScope.read(args));
                return TaskToolRuntime.create(definition, context.companion(), caller, args);
            }

            @Override
            public boolean authorized(TaskContext context) {
                var job = context.companion().executionCoordinator().queue().job(jobId);
                if (job.isEmpty()) return false;
                TaskToolDefinition definition = definitions.get(job.get().tool());
                if (definition == null) return false;
                return access.currentCaller(context.companion(), job.get().principal())
                        .access()
                        .allows(definition.minAccess());
            }
        };
    }

    /** A queued factory that re-checks the requester's current access when the job finally activates. */
    private static StepFactory guarded(
            QueueToolAccess access, TaskToolDefinition definition, UUID principal, JsonObject args) {
        return new StepFactory() {
            @Override
            public Task create(TaskContext context) {
                Caller current = access.currentCaller(context.companion(), principal)
                        .withTradingScope(QueueTradeScope.read(args));
                return TaskToolRuntime.create(definition, context.companion(), current, args.deepCopy());
            }

            @Override
            public boolean authorized(TaskContext context) {
                return access.currentCaller(context.companion(), principal)
                        .access()
                        .allows(definition.minAccess());
            }
        };
    }

    private static Task create(
            TaskToolDefinition definition, CompanionEntity companion, Caller caller, JsonObject args) {
        JsonObject safeArgs = args == null ? new JsonObject() : args.deepCopy();
        if (definition.callerFactory() != null) {
            return definition.callerFactory().create(companion, caller, safeArgs);
        }
        return definition.factory().create(companion, safeArgs);
    }
}
