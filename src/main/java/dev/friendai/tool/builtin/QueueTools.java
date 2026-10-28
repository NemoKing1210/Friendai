package dev.friendai.tool.builtin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.core.CreativeModeService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.Guard;
import dev.friendai.queue.PlanCodec;
import dev.friendai.queue.PlanDefinition;
import dev.friendai.queue.PlanNode;
import dev.friendai.queue.PlanOptions;
import dev.friendai.queue.QueueOptimizer;
import dev.friendai.queue.QueueText;
import dev.friendai.queue.QueueTradeScope;
import dev.friendai.queue.QueuedStep;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.plan.PlanStepFactory;
import dev.friendai.task.plan.StepFactory;
import dev.friendai.task.queue.CompanionGuardFacts;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.ArgValidator;
import dev.friendai.tool.Caller;
import dev.friendai.tool.QueueParser;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** LLM-facing queue operations. */
final class QueueTools {
    static final String TRADING_SCOPE_ARG = dev.friendai.queue.QueueTradeScope.ARG;
    private static final dev.friendai.queue.QueueLimits LIMITS = dev.friendai.queue.QueueLimits.DEFAULTS;

    private QueueTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        Map<String, TaskToolDef> all = new LinkedHashMap<>();
        defs.stream().filter(TaskToolDef::plannable).forEach(d -> all.put(d.name(), d));
        JsonObject stepSchema = stepSchema(all);
        registry.register(ToolKit.callerTool(
                "queue_add",
                "Add a durable multi-step plan. Use guards for world conditions and after only when priority changes must not bypass a dependency.",
                SchemaBuilder.object()
                        .string("goal", "Short goal")
                        .array("steps", "Steps to run", stepSchema, 1, 64)
                        .integer("priority", "Default priority", -10, 10)
                        .enumeration("on_failure", "Default failure policy", "ask", "skip", "stop_plan")
                        .integer("time_limit_min", "Optional plan time limit; zero means none", 0, 1440)
                        .bool("optimize", "Merge adjacent compatible count steps")
                        .required("steps")
                        .build(),
                AccessLevel.TRUSTED,
                () -> true,
                (c, caller, args) -> add(c, caller, args, all)));
        registry.register(ToolKit.instantTool(
                "plan_help",
                "Explain the accepted conditional plan grammar and its limits.",
                AccessLevel.TRUSTED,
                (c, args) -> ToolResult.ok(planHelp())));
        registry.register(ToolKit.callerTool(
                "plan_validate",
                "Validate a plan without adding it to the queue; return normalized JSON and all errors.",
                SchemaBuilder.object()
                        .array("steps", "Plan nodes to validate", stepSchema, 1, 64)
                        .required("steps")
                        .build(),
                AccessLevel.TRUSTED,
                () -> true,
                (c, caller, args) -> validateTool(args.getAsJsonArray("steps"), caller, all)));
        registry.register(ToolKit.instantTool(
                "queue_show",
                "Show queued jobs, plan status, waiting reasons and archives.",
                SchemaBuilder.object()
                        .integer("plan", "Optional plan id")
                        .integer("limit", "Maximum jobs", 1, 50)
                        .bool("include_done", "Include terminal jobs")
                        .build(),
                AccessLevel.TRUSTED,
                (c, args) -> ToolResult.ok(QueueText.show(
                        c.taskRunner().queue(),
                        new CompanionGuardFacts(c),
                        args.has("plan") ? args.get("plan").getAsLong() : null,
                        args.has("limit") ? args.get("limit").getAsInt() : 20,
                        args.has("include_done") && args.get("include_done").getAsBoolean()))));
        registry.register(ToolKit.callerTool(
                "queue_edit",
                "Move, reprioritize, update, replace, retry, skip or cancel queued work.",
                SchemaBuilder.object()
                        .enumeration(
                                "action",
                                "Queue edit",
                                "move",
                                "priority",
                                "update",
                                "cancel",
                                "retry",
                                "skip",
                                "replace",
                                "optimize",
                                "clear")
                        .integer("job", "Job id")
                        .integer("plan", "Plan id")
                        .integer("before", "Move before this job id")
                        .integer("priority", "Priority", -10, 10)
                        .string("args", "Replacement JSON arguments")
                        .array("steps", "Replacement steps", stepSchema, 1, 64)
                        .required("action")
                        .build(),
                AccessLevel.TRUSTED,
                () -> true,
                (c, caller, args) -> edit(c, caller, args, all)));
    }

    private static JsonObject stepSchema(Map<String, TaskToolDef> tools) {
        JsonObject schema = SchemaBuilder.object()
                .enumeration("tool", "Task tool", tools.keySet().toArray(String[]::new))
                .string("args", "JSON object arguments")
                .integer("priority", "Priority", -10, 10)
                .enumeration("on_unmet", "Guard behavior", "wait", "skip")
                .enumeration("on_failure", "Failure policy", "ask", "skip", "stop_plan")
                .integer("max_attempts", "Attempts", 1, 3)
                .build();
        JsonObject props = schema.getAsJsonObject("properties");
        JsonObject guard = new JsonObject();
        guard.addProperty("type", "object");
        JsonObject gp = new JsonObject();
        for (String n : List.of("type", "item", "block", "dimension")) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "string");
            gp.add(n, p);
        }
        for (String n : List.of("count", "slots", "percent", "blocks", "radius")) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "integer");
            gp.add(n, p);
        }
        guard.add("properties", gp);
        JsonObject legacyWhen = new JsonObject();
        legacyWhen.addProperty("type", "array");
        legacyWhen.add("items", guard);
        JsonObject conditionalWhen = new JsonObject();
        conditionalWhen.addProperty("type", "object");
        JsonObject when = new JsonObject();
        JsonArray whenShapes = new JsonArray();
        whenShapes.add(legacyWhen);
        whenShapes.add(conditionalWhen);
        when.add("oneOf", whenShapes);
        props.add("when", when);
        JsonObject after = new JsonObject();
        after.addProperty("type", "array");
        JsonObject ai = new JsonObject();
        ai.addProperty("type", "integer");
        after.add("items", ai);
        props.add("after", after);
        JsonObject node = new JsonObject();
        node.addProperty("type", "object");
        JsonObject branches = new JsonObject();
        branches.addProperty("type", "array");
        JsonObject child = new JsonObject();
        child.addProperty("type", "object");
        branches.add("items", child);
        props.add("then", branches);
        props.add("else", branches.deepCopy());
        JsonObject repeat = new JsonObject();
        repeat.addProperty("type", "object");
        JsonObject repeatProps = new JsonObject();
        JsonObject body = branches.deepCopy();
        repeatProps.add("steps", body);
        repeatProps.add("until", node.deepCopy());
        JsonObject iterations = new JsonObject();
        iterations.addProperty("type", "integer");
        iterations.addProperty("minimum", 1);
        iterations.addProperty("maximum", 64);
        repeatProps.add("max_iterations", iterations);
        repeat.add("properties", repeatProps);
        props.add("repeat", repeat);
        return schema;
    }

    private static ToolResult add(CompanionEntity c, Caller caller, JsonObject args, Map<String, TaskToolDef> all) {
        return addPlan(c, caller, args, all, true, java.util.Set.of());
    }
    /**
     * Queues a plan like {@code queue_add}. {@code linkGoal} false keeps the plan off the owner's goal board, and a
     * step whose tool is in {@code denied} refuses the whole plan.
     */
    static ToolResult addPlan(
            CompanionEntity c,
            Caller caller,
            JsonObject args,
            Map<String, TaskToolDef> all,
            boolean linkGoal,
            java.util.Set<String> denied) {
        JsonArray input = args.getAsJsonArray("steps");
        String forbidden = findDeniedTool(input, denied);
        if (forbidden != null)
            return ToolResult.error("Invalid plan: tool " + forbidden + " is not allowed in free time");
        Map<String, TaskToolDef> allowed = allowed(caller, all);
        Map<String, JsonObject> schemas = new LinkedHashMap<>();
        allowed.forEach((n, d) -> schemas.put(n, d.schema()));
        var parsed = QueueParser.parsePlan(
                input,
                schemas,
                all.keySet(),
                TaskSettings.queueMaxStepsPerAdd,
                LIMITS.maxArgsChars(),
                LIMITS.maxAddChars());
        if (!parsed.ok()) return ToolResult.error("Invalid plan: " + String.join("; ", parsed.errors()));
        String invalidGuard = validatePlanGuards(parsed.plan().nodes(), "");
        if (invalidGuard != null) return ToolResult.error("Invalid plan: " + invalidGuard);
        String goal = args.has("goal") ? args.get("goal").getAsString() : "";
        int priority = args.has("priority") ? args.get("priority").getAsInt() : 0;
        FailurePolicy policy = args.has("on_failure")
                ? QueueParser.policy(args.get("on_failure").getAsString()).orElse(null)
                : FailurePolicy.ASK;
        if (policy == null) return ToolResult.error("Invalid plan: on_failure must be ask, skip or stop_plan");
        long time = args.has("time_limit_min") ? args.get("time_limit_min").getAsLong() * 1200L : 0;
        PlanDefinition bound;
        try {
            bound = bindPlan(parsed.plan(), allowed, caller);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid plan: " + e.getMessage());
        }
        if (containsCreativeSensitive(bound.nodes())
                && !CreativeModeService.mayStartWorldChangingTask(c, caller.uuid())) {
            return ToolResult.error(
                    "Creative mode is active; only its online operator owner can request world-changing tasks.");
        }
        boolean optimize = args.has("optimize") && args.get("optimize").getAsBoolean();
        long id;
        try {
            if (optimize && allActions(bound.nodes())) {
                List<QueuedStep> steps = flatActions(bound.nodes());
                List<StepFactory> factories = steps.stream()
                        .map(step -> (StepFactory) ctx -> createTask(
                                allowed.get(step.tool()),
                                ctx.companion(),
                                currentCaller(ctx.companion(), step.principal())
                                        .withTradingScope(QueueTradeScope.read(step.args())),
                                step.args()))
                        .toList();
                id = c.taskRunner()
                        .enqueuePlan(goal, steps, factories, new PlanOptions(priority, policy, time), planId -> {
                            QueueOptimizer.apply(c.taskRunner().queue(), planId, name -> {
                                TaskToolDef def = all.get(name);
                                return def == null ? null : def.queueTraits().mergeableCountArg();
                            });
                            registerFactories(
                                    c,
                                    c.taskRunner().queue().jobsOfPlan(planId).stream()
                                            .map(dev.friendai.queue.Job::id)
                                            .toList(),
                                    allowed,
                                    caller.uuid());
                        });
            } else {
                id = c.taskRunner()
                        .enqueueStructuredPlan(
                                goal, bound, new PlanOptions(priority, policy, time), planFactories(allowed));
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid plan: " + e.getMessage());
        }
        if (linkGoal) GoalTools.linkPlan(c, id);
        return ToolResult.ok("plan #" + id + (goal.isBlank() ? "" : " \"" + goal + "\"") + " added: "
                + bound.worstCaseActions() + " worst-case actions");
    }

    private static ToolResult validateTool(JsonArray steps, Caller caller, Map<String, TaskToolDef> all) {
        Map<String, TaskToolDef> allowed = allowed(caller, all);
        Map<String, JsonObject> schemas = new LinkedHashMap<>();
        allowed.forEach((n, d) -> schemas.put(n, d.schema()));
        var parsed = QueueParser.parsePlan(
                steps,
                schemas,
                all.keySet(),
                TaskSettings.queueMaxStepsPerAdd,
                LIMITS.maxArgsChars(),
                LIMITS.maxAddChars());
        if (!parsed.ok())
            return ToolResult.error("Invalid plan:\n- " + String.join("\n- ", parsed.errors()) + "\nSubmitted plan:\n"
                    + normalizeForDisplay(steps));
        return ToolResult.ok("Valid plan (worst-case actions: " + parsed.plan().worstCaseActions() + "):\n"
                + PlanCodec.toJson(parsed.plan()));
    }

    private static JsonArray normalizeForDisplay(JsonArray input) {
        JsonArray result = new JsonArray();
        for (JsonElement element : input) {
            if (!element.isJsonObject()) {
                result.add(element.deepCopy());
                continue;
            }
            JsonObject node = element.getAsJsonObject().deepCopy();
            if (node.has("tool") && node.has("args") && node.get("args").isJsonObject())
                node.addProperty("args", node.get("args").toString());
            if (node.has("then") && node.get("then").isJsonArray())
                node.add("then", normalizeForDisplay(node.getAsJsonArray("then")));
            if (node.has("else") && node.get("else").isJsonArray())
                node.add("else", normalizeForDisplay(node.getAsJsonArray("else")));
            if (node.has("repeat") && node.get("repeat").isJsonObject()) {
                JsonObject repeat = node.getAsJsonObject("repeat").deepCopy();
                if (repeat.has("steps") && repeat.get("steps").isJsonArray())
                    repeat.add("steps", normalizeForDisplay(repeat.getAsJsonArray("steps")));
                node.add("repeat", repeat);
            }
            result.add(node);
        }
        return result;
    }

    private static String planHelp() {
        return "Plan nodes are actions, conditionals, or repeats. A conditional is {\"when\": condition, \"then\": [nodes], \"else\": [nodes]}; else may be omitted. "
                + "A repeat is {\"repeat\": {\"steps\": [nodes], \"until\": condition, \"max_iterations\": 1..64}}. "
                + "Conditions support all_of/any_of (1..8 conditions), not, and the existing guards plus block_in_front, biome, fluid_ahead, distance_travelled, and time_elapsed. "
                + "Condition and plan nesting is limited to 8; worst-case actions must fit the queue limit (currently 32), each args object is at most 8192 characters, and normalized plan JSON is at most 49152 characters. "
                + "Action when arrays retain legacy guard behavior. Use plan_validate to check a tree without queue changes.";
    }

    private static PlanDefinition bindPlan(PlanDefinition plan, Map<String, TaskToolDef> allowed, Caller caller) {
        return new PlanDefinition(bindNodes(plan.nodes(), allowed, caller));
    }

    private static List<PlanNode> bindNodes(List<PlanNode> nodes, Map<String, TaskToolDef> allowed, Caller caller) {
        return nodes.stream()
                .map(node -> {
                    if (node instanceof PlanNode.Action action)
                        return (PlanNode) new PlanNode.Action(bindCaller(
                                action.step(), allowed.get(action.step().tool()), caller));
                    if (node instanceof PlanNode.Conditional conditional)
                        return new PlanNode.Conditional(
                                conditional.condition(),
                                bindNodes(conditional.thenNodes(), allowed, caller),
                                bindNodes(conditional.elseNodes(), allowed, caller));
                    PlanNode.Repeat repeat = (PlanNode.Repeat) node;
                    return new PlanNode.Repeat(
                            bindNodes(repeat.body(), allowed, caller), repeat.until(), repeat.maxIterations());
                })
                .toList();
    }

    private static Map<String, PlanStepFactory> planFactories(Map<String, TaskToolDef> allowed) {
        Map<String, PlanStepFactory> result = new LinkedHashMap<>();
        allowed.forEach((name, def) -> result.put(name, new PlanStepFactory() {
            @Override
            public dev.friendai.task.Task create(dev.friendai.task.TaskContext context, QueuedStep step) {
                Caller caller = currentCaller(context.companion(), step.principal())
                        .withTradingScope(QueueTradeScope.read(step.args()));
                return createTask(def, context.companion(), caller, step.args());
            }

            @Override
            public boolean authorized(dev.friendai.task.TaskContext context, QueuedStep step) {
                return currentCaller(context.companion(), step.principal())
                        .access()
                        .allows(def.minAccess());
            }
        }));
        return result;
    }

    private static boolean allActions(List<PlanNode> nodes) {
        return nodes.stream().allMatch(PlanNode.Action.class::isInstance);
    }

    private static List<QueuedStep> flatActions(List<PlanNode> nodes) {
        return nodes.stream().map(n -> ((PlanNode.Action) n).step()).toList();
    }

    private static boolean containsCreativeSensitive(List<PlanNode> nodes) {
        for (PlanNode node : nodes) {
            if (node instanceof PlanNode.Action action && action.step().creativeSensitive()) return true;
            if (node instanceof PlanNode.Conditional conditional
                    && (containsCreativeSensitive(conditional.thenNodes())
                            || containsCreativeSensitive(conditional.elseNodes()))) return true;
            if (node instanceof PlanNode.Repeat repeat && containsCreativeSensitive(repeat.body())) return true;
        }
        return false;
    }

    private static String findDeniedTool(JsonArray nodes, Set<String> denied) {
        for (JsonElement element : nodes)
            if (element.isJsonObject()) {
                JsonObject node = element.getAsJsonObject();
                if (node.has("tool") && denied.contains(node.get("tool").getAsString()))
                    return node.get("tool").getAsString();
                if (node.has("then") && node.get("then").isJsonArray()) {
                    String found = findDeniedTool(node.getAsJsonArray("then"), denied);
                    if (found != null) return found;
                }
                if (node.has("else") && node.get("else").isJsonArray()) {
                    String found = findDeniedTool(node.getAsJsonArray("else"), denied);
                    if (found != null) return found;
                }
                if (node.has("repeat")
                        && node.get("repeat").isJsonObject()
                        && node.getAsJsonObject("repeat").has("steps")) {
                    String found = findDeniedTool(node.getAsJsonObject("repeat").getAsJsonArray("steps"), denied);
                    if (found != null) return found;
                }
            }
        return null;
    }

    private static String validatePlanGuards(List<PlanNode> nodes, String parent) {
        for (int i = 0; i < nodes.size(); i++) {
            String path = parent.isEmpty() ? "step " + (i + 1) : parent + " #" + (i + 1);
            PlanNode node = nodes.get(i);
            if (node instanceof PlanNode.Action action)
                for (Guard guard : action.step().guards()) {
                    String problem = validateGuard(guard);
                    if (problem != null) return path + ": " + problem;
                }
            else if (node instanceof PlanNode.Conditional conditional) {
                String problem = validateGuard(conditional.condition());
                if (problem != null) return path + " → if: " + problem;
                problem = validatePlanGuards(conditional.thenNodes(), path + " → then");
                if (problem != null) return problem;
                problem = validatePlanGuards(conditional.elseNodes(), path + " → else");
                if (problem != null) return problem;
            } else {
                PlanNode.Repeat repeat = (PlanNode.Repeat) node;
                String problem = validateGuard(repeat.until());
                if (problem != null) return path + " → repeat: " + problem;
                problem = validatePlanGuards(repeat.body(), path + " → repeat");
                if (problem != null) return problem;
            }
        }
        return null;
    }

    private static String validateGuard(Guard guard) {
        if (guard instanceof Guard.HasItem item
                && ItemMatcher.parse(item.item()).isEmpty()) return "unknown item '" + item.item() + "'";
        if (guard instanceof Guard.BlockWithin block
                && BlockMatcher.parse(block.block()).isEmpty()) return "unknown block '" + block.block() + "'";
        if (guard instanceof Guard.BlockInFront block
                && BlockMatcher.parse(block.block()).isEmpty()) return "unknown block '" + block.block() + "'";
        if (guard instanceof Guard.AllOf group)
            for (Guard child : group.conditions()) {
                String bad = validateGuard(child);
                if (bad != null) return bad;
            }
        if (guard instanceof Guard.AnyOf group)
            for (Guard child : group.conditions()) {
                String bad = validateGuard(child);
                if (bad != null) return bad;
            }
        if (guard instanceof Guard.Not not) return validateGuard(not.condition());
        return null;
    }

    private static ToolResult edit(CompanionEntity c, Caller caller, JsonObject args, Map<String, TaskToolDef> all) {
        if (!args.has("action")) return error("queue_edit requires action");
        String action = args.get("action").getAsString();
        var runner = c.taskRunner();
        var q = runner.queue();
        try {
            switch (action) {
                case "clear" -> {
                    return ToolResult.ok(runner.clearQueue("cleared by the LLM"));
                }
                case "cancel" -> {
                    if (args.has("plan")) runner.cancelPlan(args.get("plan").getAsLong(), "cancelled by the LLM");
                    else runner.cancelJob(required(args, "job"), "cancelled by the LLM");
                }
                case "retry" -> {
                    long id = required(args, "job");
                    var job = q.job(id).orElse(null);
                    JobData.validateTool(job, all, caller);
                    if (job.creativeSensitive() && !CreativeModeService.mayStartWorldChangingTask(c, caller.uuid())) {
                        return error(
                                "Creative mode is active; only its online operator owner can retry world-changing tasks.");
                    }
                    TaskToolDef def = all.get(job.tool());
                    boolean spending = job.args().has(TRADING_SCOPE_ARG)
                            || (def != null && def.queueTraits().requiresTradingScope());
                    QueueTradeScope.retry(job.args(), spending, caller.access() == AccessLevel.OWNER);
                    var r = q.retry(id);
                    if (!r.ok()) return error(r.text());
                }
                case "skip" -> {
                    var r = runner.skipJob(required(args, "job"));
                    if (!r.ok()) return error(r.text());
                }
                case "priority" -> {
                    if (!args.has("priority")) return error("queue_edit requires priority");
                    int p = args.get("priority").getAsInt();
                    var r = args.has("plan")
                            ? q.setPlanPriority(args.get("plan").getAsLong(), p)
                            : q.setPriority(required(args, "job"), p);
                    if (!r.ok()) return error(r.text());
                }
                case "move" -> {
                    var r = q.move(required(args, "job"), required(args, "before"));
                    if (!r.ok()) return error(r.text());
                }
                case "update" -> {
                    if (!args.has("args")) return error("queue_edit requires args");
                    long id = required(args, "job");
                    JobData.validateTool(q.job(id).orElse(null), all, caller);
                    JsonObject obj = JsonParser.parseString(args.get("args").getAsString())
                            .getAsJsonObject();
                    TaskToolDef d = all.get(q.job(id).orElseThrow().tool());
                    if (d.creativeSensitive() && !CreativeModeService.mayStartWorldChangingTask(c, caller.uuid()))
                        return error(
                                "Creative mode is active; only its online operator owner can request world-changing tasks.");
                    var issues = ArgValidator.validate(d.schema(), obj);
                    if (!issues.isEmpty()) return error(String.join("; ", issues));
                    var r = q.updateArgs(id, obj, caller.uuid(), d.creativeSensitive());
                    if (!r.ok()) return error(r.text());
                    UUID principal = caller.uuid();
                    c.taskRunner()
                            .registerFactory(
                                    id,
                                    ctx -> createTask(
                                            d,
                                            ctx.companion(),
                                            currentCaller(ctx.companion(), principal),
                                            obj.deepCopy()));
                }
                case "replace" -> {
                    if (!args.has("steps")) return error("queue_edit requires steps");
                    long id = required(args, "job");
                    var old = q.job(id).orElse(null);
                    JobData.validateTool(old, all, caller);
                    Map<String, TaskToolDef> allowed = allowed(caller, all);
                    Map<String, JsonObject> schemas = new LinkedHashMap<>();
                    allowed.forEach((n, d) -> schemas.put(n, d.schema()));
                    var parsed = QueueParser.parse(
                            args.getAsJsonArray("steps"),
                            schemas,
                            all.keySet(),
                            TaskSettings.queueMaxStepsPerAdd,
                            LIMITS.maxArgsChars(),
                            LIMITS.maxAddChars());
                    if (!parsed.ok()) return error("Invalid replacement: " + String.join("; ", parsed.errors()));
                    String invalidGuard = validateGuards(parsed.steps());
                    if (invalidGuard != null) return error("Invalid replacement: " + invalidGuard);
                    List<QueuedStep> steps = parsed.steps().stream()
                            .map(step -> bindCaller(step, allowed.get(step.tool()), caller))
                            .toList();
                    if (steps.stream().anyMatch(QueuedStep::creativeSensitive)
                            && !CreativeModeService.mayStartWorldChangingTask(c, caller.uuid()))
                        return error(
                                "Creative mode is active; only its online operator owner can request world-changing tasks.");
                    long firstNewId = q.nextJobId();
                    var r = q.replace(id, steps);
                    if (!r.ok()) return error(r.text());
                    UUID principal = caller.uuid();
                    for (int i = 0; i < steps.size(); i++) {
                        QueuedStep step = steps.get(i);
                        TaskToolDef def = allowed.get(step.tool());
                        c.taskRunner()
                                .registerFactory(
                                        firstNewId + i,
                                        ctx -> createTask(
                                                def,
                                                ctx.companion(),
                                                currentCaller(ctx.companion(), principal),
                                                step.args()));
                    }
                }
                case "optimize" -> {
                    Long plan = args.has("plan") ? args.get("plan").getAsLong() : null;
                    var r = QueueOptimizer.apply(q, plan, n -> {
                        TaskToolDef d = all.get(n);
                        return d == null ? null : d.queueTraits().mergeableCountArg();
                    });
                    runner.wakeQueue();
                    return ToolResult.ok(r.text());
                }
                default -> {
                    return error("unknown queue_edit action: " + action);
                }
            }
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            return error("invalid queue edit arguments");
        }
        runner.wakeQueue();
        return ToolResult.ok("queue updated");
    }

    private static Map<String, TaskToolDef> allowed(Caller caller, Map<String, TaskToolDef> all) {
        Map<String, TaskToolDef> map = new LinkedHashMap<>();
        all.forEach((n, d) -> {
            if (caller.access().allows(d.minAccess())) map.put(n, d);
        });
        return map;
    }

    private static void registerFactories(
            CompanionEntity c, List<Long> ids, Map<String, TaskToolDef> allowed, UUID principal) {
        for (long id : ids) {
            var job = c.taskRunner().queue().job(id).orElse(null);
            if (job == null) continue;
            TaskToolDef def = allowed.get(job.tool());
            if (def != null) {
                JsonObject savedArgs = job.args();
                c.taskRunner()
                        .registerFactory(
                                id,
                                ctx -> createTask(
                                        def,
                                        ctx.companion(),
                                        currentCaller(ctx.companion(), principal)
                                                .withTradingScope(QueueTradeScope.read(savedArgs)),
                                        savedArgs.deepCopy()));
            }
        }
    }

    private static QueuedStep bindCaller(QueuedStep step, TaskToolDef def, Caller caller) {
        boolean spending = def != null && def.queueTraits().requiresTradingScope();
        JsonObject scoped =
                QueueTradeScope.capture(step.args(), caller == null ? null : caller.tradingScope(), spending);
        return new QueuedStep(
                step.tool(),
                scoped,
                step.priority(),
                step.guards(),
                step.onUnmet(),
                step.onFailure(),
                spending ? 1 : step.maxAttempts(),
                step.afterSteps(),
                caller == null ? null : caller.uuid(),
                def != null && def.creativeSensitive());
    }

    private static dev.friendai.task.Task createTask(
            TaskToolDef def, CompanionEntity c, Caller caller, JsonObject args) {
        return def.callerFactory() == null
                ? def.factory().create(c, args.deepCopy())
                : def.callerFactory().create(c, caller, args.deepCopy());
    }

    private static Caller currentCaller(CompanionEntity c, UUID principal) {
        if (principal == null) return new Caller(null, "", AccessLevel.GUEST);
        AccessLevel access =
                c.ownerUuid() != null && c.ownerUuid().equals(principal) ? AccessLevel.OWNER : AccessLevel.GUEST;
        if (c.level() instanceof ServerLevel level
                && access != AccessLevel.OWNER
                && c.ownerUuid() != null
                && FriendaiServerState.get(level.getServer())
                        .memory()
                        .book(c.ownerUuid())
                        .isTrusted(principal)) access = AccessLevel.TRUSTED;
        return new Caller(principal, "", access);
    }

    private static String validateGuards(List<QueuedStep> steps) {
        for (int i = 0; i < steps.size(); i++)
            for (var guard : steps.get(i).guards()) {
                if (guard instanceof Guard.HasItem g
                        && ItemMatcher.parse(g.item()).isEmpty())
                    return "step " + (i + 1) + ": unknown item '" + g.item() + "'";
                if (guard instanceof Guard.BlockWithin g
                        && BlockMatcher.parse(g.block()).isEmpty())
                    return "step " + (i + 1) + ": unknown block '" + g.block() + "'";
            }
        return null;
    }

    private static long required(JsonObject a, String key) {
        if (!a.has(key)) throw new IllegalArgumentException("queue_edit requires " + key);
        return a.get(key).getAsLong();
    }

    private static ToolResult error(String text) {
        return ToolResult.error(text == null ? "invalid queue edit" : text);
    }

    private static final class JobData {
        static void validateTool(dev.friendai.queue.Job job, Map<String, TaskToolDef> all, Caller caller) {
            if (job == null) throw new IllegalArgumentException("unknown job");
            TaskToolDef def = all.get(job.tool());
            if (def == null) throw new IllegalArgumentException("job #" + job.id() + "'s tool is no longer available");
            if (!caller.access().allows(def.minAccess()))
                throw new IllegalArgumentException("job #" + job.id() + "'s tool is not available to this caller");
        }
    }
}
