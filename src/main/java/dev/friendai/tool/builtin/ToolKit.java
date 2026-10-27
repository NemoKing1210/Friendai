package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.QueueTradeScope;
import dev.friendai.queue.QueuedStep;
import dev.friendai.task.Task;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.Tool;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;

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
        CallerAction routed = def.callerFactory() == null ? action : (c, caller, args) -> {
            Task task;
            try { task = def.callerFactory().create(c, caller, args.deepCopy()); }
            catch (IllegalArgumentException e) { return ToolResult.error(e.getMessage()); }
            if (def.queueTraits().interrupts()) {
                c.taskRunner().interrupt(task, def.name());
                return ToolResult.ok("task #" + c.taskRunner().currentId() + " started: " + task.describe());
            }
            try {
                var captured = QueueTradeScope.captureEnqueue(args, caller.tradingScope(),
                        def.queueTraits().requiresTradingScope(), 1);
                JsonObject queuedArgs = captured.args();
                Caller queuedCaller = caller.withTradingScope(captured.tradingScope());
                long id = c.taskRunner().enqueue(new QueuedStep(def.name(), queuedArgs, null, List.of(), GuardAction.WAIT,
                                FailurePolicy.STOP_PLAN, captured.maxAttempts(), List.of()),
                        ctx -> def.callerFactory().create(ctx.companion(), queuedCaller, queuedArgs.deepCopy()));
                boolean active = c.taskRunner().queue().active().map(job -> job.id() == id).orElse(false);
                return ToolResult.ok(active ? "task #" + id + " started: " + task.describe()
                        : "job #" + id + " queued behind " + c.taskRunner().pendingJobs() + " jobs");
            } catch (IllegalArgumentException e) { return ToolResult.error(e.getMessage()); }
        };
        registry.register(callerTool(def.name(), def.description(), def.schema(), def.minAccess(), () -> true, routed));
    }

    static Tool<CompanionEntity> taskTool(TaskToolDef def) {
        validateRegistration(def, false);
        return tool(def.name(), def.description(), def.schema(), def.minAccess(), (c, args) -> {
            Task task;
            try {
                task = def.factory().create(c, args);
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
            if (def.queueTraits().interrupts()) {
                c.taskRunner().interrupt(task, def.name());
                return ToolResult.ok("task #" + c.taskRunner().currentId() + " started: " + task.describe());
            }
            Task preview = task;
            long id;
            try { id = c.taskRunner().enqueue(new QueuedStep(def.name(), args, null, List.of(), GuardAction.WAIT, FailurePolicy.STOP_PLAN, 1, List.of()),
                    ctx -> def.factory().create(ctx.companion(), args.deepCopy())); }
            catch (IllegalArgumentException e) { return ToolResult.error(e.getMessage()); }
            boolean active = c.taskRunner().queue().active().map(job -> job.id() == id).orElse(false);
            return ToolResult.ok(active ? "task #" + id + " started: " + preview.describe() : "job #" + id + " queued behind " + c.taskRunner().pendingJobs() + " jobs");
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
        int id = c.taskRunner().start(task, name);
        return ToolResult.ok("task #" + id + " started: " + task.describe());
    }

    static Tool<CompanionEntity> instantTool(String name, String description, JsonObject schema, Action action) {
        return tool(name, description, schema, AccessLevel.TRUSTED, action);
    }

    static Tool<CompanionEntity> instantTool(String name, String description, Action action) {
        return tool(name, description, SchemaBuilder.object().build(), AccessLevel.TRUSTED, action);
    }

    static Tool<CompanionEntity> instantTool(
            String name, String description, AccessLevel minAccess, Action action) {
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
                args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt()));
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
