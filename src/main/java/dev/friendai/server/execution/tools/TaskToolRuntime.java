package dev.friendai.server.execution.tools;

import com.google.gson.JsonObject;
import dev.friendai.core.CreativeModeService;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.QueueTradeScope;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.task.Task;
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
                                    context -> {
                                        Caller current = access.currentCaller(context.companion(), principal)
                                                .withTradingScope(QueueTradeScope.read(queuedArgs));
                                        return create(definition, context.companion(), current, queuedArgs.deepCopy());
                                    });
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

    private static Task create(
            TaskToolDefinition definition, CompanionEntity companion, Caller caller, JsonObject args) {
        JsonObject safeArgs = args == null ? new JsonObject() : args.deepCopy();
        if (definition.callerFactory() != null) {
            return definition.callerFactory().create(companion, caller, safeArgs);
        }
        return definition.factory().create(companion, safeArgs);
    }
}
