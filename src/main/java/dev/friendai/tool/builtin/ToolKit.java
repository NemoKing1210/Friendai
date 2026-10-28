package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.core.CreativeModeService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.QueueTradeScope;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.task.Task;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockMatcher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Helpers shared by the tool groups: turning a definition into a {@link Tool} and parsing item/block arguments. */
final class ToolKit {
    private ToolKit() {}

    /** Registers a task tool and contributes its definition to the task registry. */
    static void add(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs, TaskToolDef def) {
        validateRegistration(def, false);
        defs.add(def);
        registry.register(taskTool(def));
    }

    /**
     * Registers a task tool that needs the caller of the turn and contributes its definition to the task registry.
     */
    static void addCaller(
            ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs, TaskToolDef def, CallerAction action) {
        validateRegistration(def, true);
        defs.add(def);
        CallerAction routed = (c, caller, args) -> {
            if (def.creativeSensitive()
                    && !CreativeModeService.mayStartWorldChangingTask(c, caller == null ? null : caller.uuid())) {
                return creativeAuthorizationError();
            }
            if (def.callerFactory() == null) return action.run(c, caller, args);
            Task task;
            try {
                task = def.callerFactory().create(c, caller, args.deepCopy());
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
            if (def.queueTraits().interrupts()) {
                c.executionCoordinator().interrupt(task, def.name(), caller, def.creativeSensitive());
                return ToolResult.ok("task #" + c.executionCoordinator().currentId() + " started: " + task.describe());
            }
            try {
                QueueTradeScope.EnqueueCapture capture = QueueTradeScope.captureEnqueue(
                        args,
                        caller == null ? null : caller.tradingScope(),
                        def.queueTraits().requiresTradingScope(),
                        1);
                JsonObject queuedArgs = capture.args();
                Caller queuedCaller = caller == null ? null : caller.withTradingScope(capture.tradingScope());
                long id = c.executionCoordinator()
                        .enqueue(
                                new QueuedStep(
                                        def.name(),
                                        queuedArgs,
                                        null,
                                        List.of(),
                                        GuardAction.WAIT,
                                        FailurePolicy.STOP_PLAN,
                                        capture.maxAttempts(),
                                        List.of(),
                                        caller == null ? null : caller.uuid(),
                                        def.creativeSensitive()),
                                ctx -> def.callerFactory()
                                        .create(
                                                ctx.companion(),
                                                currentCaller(ctx.companion(), queuedCaller),
                                                queuedArgs.deepCopy()));
                boolean active = c.executionCoordinator()
                        .queue()
                        .active()
                        .map(job -> job.id() == id)
                        .orElse(false);
                return ToolResult.ok(
                        active
                                ? "task #" + id + " started: " + task.describe()
                                : "job #" + id + " queued behind "
                                        + c.executionCoordinator().pendingJobs() + " jobs");
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
        };
        registry.register(callerTool(def.name(), def.description(), def.schema(), def.minAccess(), () -> true, routed));
    }

    static Tool<CompanionEntity> taskTool(TaskToolDef def) {
        validateRegistration(def, false);
        return callerTool(
                def.name(), def.description(), def.schema(), def.minAccess(), () -> true, (c, caller, args) -> {
                    if (def.creativeSensitive()
                            && !CreativeModeService.mayStartWorldChangingTask(
                                    c, caller == null ? null : caller.uuid())) {
                        return creativeAuthorizationError();
                    }
                    Task task;
                    try {
                        task = def.callerFactory() == null
                                ? def.factory().create(c, args)
                                : def.callerFactory().create(c, caller, args.deepCopy());
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error(e.getMessage());
                    }
                    if (def.queueTraits().interrupts()) {
                        c.executionCoordinator().interrupt(task, def.name(), caller, def.creativeSensitive());
                        return ToolResult.ok(
                                "task #" + c.executionCoordinator().currentId() + " started: " + task.describe());
                    }
                    Task preview = task;
                    QueueTradeScope.EnqueueCapture capture;
                    try {
                        capture = QueueTradeScope.captureEnqueue(
                                args,
                                caller == null ? null : caller.tradingScope(),
                                def.queueTraits().requiresTradingScope(),
                                1);
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error(e.getMessage());
                    }
                    JsonObject queuedArgs = capture.args();
                    Caller queuedCaller = caller == null ? null : caller.withTradingScope(capture.tradingScope());
                    long id;
                    try {
                        id = c.executionCoordinator()
                                .enqueue(
                                        new QueuedStep(
                                                def.name(),
                                                queuedArgs,
                                                null,
                                                List.of(),
                                                GuardAction.WAIT,
                                                FailurePolicy.STOP_PLAN,
                                                capture.maxAttempts(),
                                                List.of(),
                                                caller == null ? null : caller.uuid(),
                                                def.creativeSensitive()),
                                        ctx -> def.callerFactory() == null
                                                ? def.factory().create(ctx.companion(), queuedArgs.deepCopy())
                                                : def.callerFactory()
                                                        .create(
                                                                ctx.companion(),
                                                                currentCaller(ctx.companion(), queuedCaller),
                                                                queuedArgs.deepCopy()));
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error(e.getMessage());
                    }
                    boolean active = c.executionCoordinator()
                            .queue()
                            .active()
                            .map(job -> job.id() == id)
                            .orElse(false);
                    return ToolResult.ok(
                            active
                                    ? "task #" + id + " started: " + preview.describe()
                                    : "job #" + id + " queued behind "
                                            + c.executionCoordinator().pendingJobs() + " jobs");
                });
    }

    static boolean requiresFreshAuthority(JsonObject args, TaskToolDef def) {
        return QueueTradeScope.requiresFreshAuthority(args, def.queueTraits().requiresTradingScope());
    }

    static void validateRegistration(TaskToolDef def, boolean callerAware) {
        if (def == null) throw new IllegalArgumentException("task tool definition is required");
        if (def.queueTraits().requiresTradingScope()) {
            if (!callerAware) {
                throw new IllegalArgumentException("spending tools must be registered through a caller-aware path");
            }
            if (def.callerFactory() == null) {
                throw new IllegalArgumentException("spending tools require a caller-aware task factory");
            }
        }
    }

    /** Starts a task the tool has already built and reports its id. */
    static ToolResult startTask(CompanionEntity c, String name, Task task) {
        int id = c.executionCoordinator().start(task, name);
        return ToolResult.ok("task #" + id + " started: " + task.describe());
    }

    static ToolResult startTask(CompanionEntity c, String name, Task task, Caller caller) {
        if (!CreativeModeService.mayStartWorldChangingTask(c, caller == null ? null : caller.uuid())) {
            return creativeAuthorizationError();
        }
        int id = c.executionCoordinator().start(task, name, caller, true);
        return ToolResult.ok("task #" + id + " started: " + task.describe());
    }

    private static ToolResult creativeAuthorizationError() {
        return ToolResult.error(
                "Creative mode is active; only its online operator owner can request world-changing tasks.");
    }

    private static Caller currentCaller(CompanionEntity companion, Caller submitted) {
        return currentCaller(companion, submitted == null ? null : submitted.uuid());
    }

    private static Caller currentCaller(CompanionEntity companion, UUID principal) {
        if (principal == null) return new Caller(null, "", AccessLevel.GUEST);
        AccessLevel access =
                companion.ownerUuid() != null && companion.ownerUuid().equals(principal)
                        ? AccessLevel.OWNER
                        : AccessLevel.GUEST;
        if (companion.level() instanceof ServerLevel level) {
            if (access != AccessLevel.OWNER
                    && companion.ownerUuid() != null
                    && FriendaiServerState.get(level.getServer())
                            .memory()
                            .book(companion.ownerUuid())
                            .isTrusted(principal)) {
                access = AccessLevel.TRUSTED;
            }
        }
        return new Caller(principal, "", access);
    }

    static Tool<CompanionEntity> instantTool(String name, String description, JsonObject schema, Action action) {
        return tool(name, description, schema, AccessLevel.TRUSTED, action);
    }

    static Tool<CompanionEntity> instantTool(String name, String description, Action action) {
        return tool(name, description, SchemaBuilder.object().build(), AccessLevel.TRUSTED, action);
    }

    static Tool<CompanionEntity> instantTool(String name, String description, AccessLevel minAccess, Action action) {
        return tool(name, description, SchemaBuilder.object().build(), minAccess, action);
    }

    /** A tool that needs the caller of the turn, and can be turned off at runtime. */
    static Tool<CompanionEntity> callerTool(
            String name,
            String description,
            JsonObject schema,
            AccessLevel minAccess,
            BooleanSupplier enabled,
            CallerAction action) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonObject schema() {
                return schema;
            }

            @Override
            public AccessLevel minAccess() {
                return minAccess;
            }

            @Override
            public boolean enabled() {
                return enabled.getAsBoolean();
            }

            @Override
            public ToolResult execute(CompanionEntity ctx, JsonObject args) {
                return action.run(ctx, new Caller(null, "", minAccess), args);
            }

            @Override
            public ToolResult execute(CompanionEntity ctx, Caller caller, JsonObject args) {
                return action.run(ctx, caller, args);
            }
        };
    }

    /** An instant tool with arguments, visible only at {@code minAccess} or above. */
    static Tool<CompanionEntity> instantTool(
            String name, String description, JsonObject schema, AccessLevel minAccess, Action action) {
        return tool(name, description, schema, minAccess, action);
    }

    static ItemMatcher parseItemMatcher(String idOrTag) {
        return ItemMatcher.parse(idOrTag)
                .orElseThrow(() -> new IllegalArgumentException("unknown item or tag: " + idOrTag));
    }

    static BlockMatcher parseBlockMatcher(String idOrTag) {
        return BlockMatcher.parse(idOrTag)
                .orElseThrow(() -> new IllegalArgumentException("unknown block or tag: " + idOrTag));
    }

    /** All of x, y, z or none. */
    static Optional<BlockPos> optionalPos(JsonObject args) {
        int present = 0;
        for (String name : new String[] {"x", "y", "z"}) {
            if (args.has(name) && !args.get(name).isJsonNull()) {
                present++;
            }
        }
        if (present == 0) {
            return Optional.empty();
        }
        if (present != 3) {
            throw new IllegalArgumentException("give all of x, y, z or none");
        }
        return Optional.of(new BlockPos(
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt()));
    }

    private static Tool<CompanionEntity> tool(
            String name, String description, JsonObject schema, AccessLevel minAccess, Action action) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AccessLevel minAccess() {
                return minAccess;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonObject schema() {
                return schema;
            }

            @Override
            public ToolResult execute(CompanionEntity ctx, JsonObject args) {
                return action.run(ctx, args);
            }
        };
    }

    @FunctionalInterface
    interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }

    @FunctionalInterface
    interface CallerAction {
        ToolResult run(CompanionEntity companion, Caller caller, JsonObject args);
    }
}
