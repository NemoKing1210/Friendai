package dev.friendai.tool.builtin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.core.CreativeModeService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.PlanOptions;
import dev.friendai.queue.QueueOptimizer;
import dev.friendai.queue.QueueTradeScope;
import dev.friendai.queue.QueueText;
import dev.friendai.queue.QueuedStep;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.plan.StepFactory;
import dev.friendai.task.queue.CompanionGuardFacts;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.ArgValidator;
import dev.friendai.tool.Caller;
import dev.friendai.tool.QueueParser;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import dev.friendai.queue.Guard;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** LLM-facing queue operations. */
final class QueueTools {
    static final String TRADING_SCOPE_ARG = dev.friendai.queue.QueueTradeScope.ARG;
    private static final dev.friendai.queue.QueueLimits LIMITS=dev.friendai.queue.QueueLimits.DEFAULTS;
    private QueueTools() {}
    static void register(ToolRegistry<CompanionEntity> registry,List<TaskToolDef> defs) {
        Map<String,TaskToolDef> all=new LinkedHashMap<>();defs.stream().filter(TaskToolDef::plannable).forEach(d->all.put(d.name(),d));
        JsonObject stepSchema=stepSchema(all);
        registry.register(ToolKit.callerTool("queue_add","Add a durable multi-step plan. Use guards for world conditions and after only when priority changes must not bypass a dependency.",
                SchemaBuilder.object().string("goal","Short goal").array("steps","Steps to run",stepSchema,1,64).integer("priority","Default priority",-10,10).enumeration("on_failure","Default failure policy","ask","skip","stop_plan").integer("time_limit_min","Optional plan time limit; zero means none",0,1440).bool("optimize","Merge adjacent compatible count steps").required("steps").build(),AccessLevel.TRUSTED,()->true,(c,caller,args)->add(c,caller,args,all)));
        registry.register(ToolKit.instantTool("queue_show","Show queued jobs, plan status, waiting reasons and archives.",SchemaBuilder.object().integer("plan","Optional plan id").integer("limit","Maximum jobs",1,50).bool("include_done","Include terminal jobs").build(),AccessLevel.TRUSTED,(c,args)->ToolResult.ok(QueueText.show(c.taskRunner().queue(),new CompanionGuardFacts(c),args.has("plan")?args.get("plan").getAsLong():null,args.has("limit")?args.get("limit").getAsInt():20,args.has("include_done")&&args.get("include_done").getAsBoolean()))));
        registry.register(ToolKit.callerTool("queue_edit","Move, reprioritize, update, replace, retry, skip or cancel queued work.",
                SchemaBuilder.object().enumeration("action","Queue edit","move","priority","update","cancel","retry","skip","replace","optimize","clear").integer("job","Job id").integer("plan","Plan id").integer("before","Move before this job id").integer("priority","Priority",-10,10).string("args","Replacement JSON arguments").array("steps","Replacement steps",stepSchema,1,64).required("action").build(),AccessLevel.TRUSTED,()->true,(c,caller,args)->edit(c,caller,args,all)));
    }
    private static JsonObject stepSchema(Map<String,TaskToolDef> tools) {
        JsonObject schema=SchemaBuilder.object().enumeration("tool","Task tool",tools.keySet().toArray(String[]::new)).string("args","JSON object arguments").integer("priority","Priority",-10,10).enumeration("on_unmet","Guard behavior","wait","skip").enumeration("on_failure","Failure policy","ask","skip","stop_plan").integer("max_attempts","Attempts",1,3).build();
        JsonObject props=schema.getAsJsonObject("properties"); JsonObject guard=new JsonObject();guard.addProperty("type","object");JsonObject gp=new JsonObject();for(String n:List.of("type","item","block","dimension")){JsonObject p=new JsonObject();p.addProperty("type","string");gp.add(n,p);}for(String n:List.of("count","slots","percent","blocks","radius")){JsonObject p=new JsonObject();p.addProperty("type","integer");gp.add(n,p);}guard.add("properties",gp);
        JsonObject when=new JsonObject();when.addProperty("type","array");when.add("items",guard);props.add("when",when);
        JsonObject after=new JsonObject();after.addProperty("type","array");JsonObject ai=new JsonObject();ai.addProperty("type","integer");after.add("items",ai);props.add("after",after);
        JsonArray required=schema.has("required")?schema.getAsJsonArray("required"):new JsonArray();if(!schema.has("required"))schema.add("required",required);required.add("tool");return schema;
    }
    private static ToolResult add(CompanionEntity c,Caller caller,JsonObject args,Map<String,TaskToolDef> all) {
        return addPlan(c,caller,args,all,true,java.util.Set.of());
    }
    /**
     * Queues a plan like {@code queue_add}. {@code linkGoal} false keeps the plan off the owner's goal board, and a
     * step whose tool is in {@code denied} refuses the whole plan.
     */
    static ToolResult addPlan(CompanionEntity c,Caller caller,JsonObject args,Map<String,TaskToolDef> all,boolean linkGoal,java.util.Set<String> denied) {
        if(args.has("steps")&&args.get("steps").isJsonArray())for(var step:args.getAsJsonArray("steps")){
            if(step.isJsonObject()&&step.getAsJsonObject().has("tool")&&denied.contains(step.getAsJsonObject().get("tool").getAsString()))
                return ToolResult.error("Invalid plan: tool "+step.getAsJsonObject().get("tool").getAsString()+" is not allowed in free time");
        }
        Map<String,TaskToolDef> allowed=allowed(caller,all);Map<String,JsonObject> schemas=new LinkedHashMap<>();allowed.forEach((n,d)->schemas.put(n,d.schema()));
        var parsed=QueueParser.parse(args.getAsJsonArray("steps"),schemas,all.keySet(),TaskSettings.queueMaxStepsPerAdd,LIMITS.maxArgsChars(),LIMITS.maxAddChars());
        if(!parsed.ok())return ToolResult.error("Invalid plan: "+String.join("; ",parsed.errors()));
        String invalidGuard=validateGuards(parsed.steps());if(invalidGuard!=null)return ToolResult.error("Invalid plan: "+invalidGuard);
        String goal=args.has("goal")?args.get("goal").getAsString():"";int priority=args.has("priority")?args.get("priority").getAsInt():0;
        FailurePolicy policy=args.has("on_failure")?QueueParser.policy(args.get("on_failure").getAsString()).orElse(null):FailurePolicy.ASK;
        if(policy==null)return ToolResult.error("Invalid plan: on_failure must be ask, skip or stop_plan");
        long time=args.has("time_limit_min")?args.get("time_limit_min").getAsLong()*1200L:0;
        List<QueuedStep> steps=parsed.steps().stream().map(step -> bindCaller(step, allowed.get(step.tool()), caller)).toList();
        if (steps.stream().anyMatch(QueuedStep::creativeSensitive)
                && !CreativeModeService.mayStartWorldChangingTask(c, caller.uuid())) {
            return ToolResult.error("Creative mode is active; only its online operator owner can request world-changing tasks.");
        }
        UUID principal = caller.uuid();
        List<StepFactory> factories=steps.stream().map(s->(StepFactory)ctx->createTask(allowed.get(s.tool()),ctx.companion(),currentCaller(ctx.companion(),principal),s.args())).toList();        boolean optimize=args.has("optimize")&&args.get("optimize").getAsBoolean();
        long id;try{id=c.taskRunner().enqueuePlan(goal,steps,factories,new PlanOptions(priority,policy,time),planId->{
            if(!optimize)return;
            // Merge before the first job starts, then rebuild the factories so merged counts are used.
            QueueOptimizer.apply(c.taskRunner().queue(),planId,name->{TaskToolDef d=all.get(name);return d==null?null:d.queueTraits().mergeableCountArg();});
            registerFactories(c,c.taskRunner().queue().jobsOfPlan(planId).stream().map(dev.friendai.queue.Job::id).toList(),allowed,principal);
        });}catch(IllegalArgumentException e){return ToolResult.error("Invalid plan: "+e.getMessage());}
        if(linkGoal)GoalTools.linkPlan(c,id);
        return ToolResult.ok("plan #"+id+(goal.isBlank()?"":" \""+goal+"\"")+" added: "+steps.size()+" jobs");
    }
    private static ToolResult edit(CompanionEntity c,Caller caller,JsonObject args,Map<String,TaskToolDef> all) {
        if(!args.has("action"))return error("queue_edit requires action");String action=args.get("action").getAsString();var runner=c.taskRunner();var q=runner.queue();
        try {
            switch(action) {
                case "clear" -> { return ToolResult.ok(runner.clearQueue("cleared by the LLM")); }
                case "cancel" -> { if(args.has("plan"))runner.cancelPlan(args.get("plan").getAsLong(),"cancelled by the LLM");else runner.cancelJob(required(args,"job"),"cancelled by the LLM"); }
                case "retry" -> {
                    long id = required(args, "job");
                    var job = q.job(id).orElse(null);
                    JobData.validateTool(job, all, caller);
                    if (job.creativeSensitive()
                            && !CreativeModeService.mayStartWorldChangingTask(c, caller.uuid())) {
                        return error("Creative mode is active; only its online operator owner can retry world-changing tasks.");
                    }
                    var r = q.retry(id);
                    if (!r.ok()) return error(r.text());
                }
                case "skip" -> { var r=runner.skipJob(required(args,"job"));if(!r.ok())return error(r.text()); }
                case "priority" -> { if(!args.has("priority"))return error("queue_edit requires priority");int p=args.get("priority").getAsInt();var r=args.has("plan")?q.setPlanPriority(args.get("plan").getAsLong(),p):q.setPriority(required(args,"job"),p);if(!r.ok())return error(r.text()); }
                case "move" -> { var r=q.move(required(args,"job"),required(args,"before"));if(!r.ok())return error(r.text()); }
                case "update" -> { if(!args.has("args"))return error("queue_edit requires args");long id=required(args,"job");JobData.validateTool(q.job(id).orElse(null),all,caller);JsonObject obj=JsonParser.parseString(args.get("args").getAsString()).getAsJsonObject();TaskToolDef d=all.get(q.job(id).orElseThrow().tool());if(d.creativeSensitive()&&!CreativeModeService.mayStartWorldChangingTask(c,caller.uuid()))return error("Creative mode is active; only its online operator owner can request world-changing tasks.");var issues=ArgValidator.validate(d.schema(),obj);if(!issues.isEmpty())return error(String.join("; ",issues));var r=q.updateArgs(id,obj,caller.uuid(),d.creativeSensitive());if(!r.ok())return error(r.text());UUID principal=caller.uuid();c.taskRunner().registerFactory(id,ctx->createTask(d,ctx.companion(),currentCaller(ctx.companion(),principal),obj.deepCopy())); }
                case "replace" -> { if(!args.has("steps"))return error("queue_edit requires steps");long id=required(args,"job");var old=q.job(id).orElse(null);JobData.validateTool(old,all,caller);Map<String,TaskToolDef> allowed=allowed(caller,all);Map<String,JsonObject> schemas=new LinkedHashMap<>();allowed.forEach((n,d)->schemas.put(n,d.schema()));var parsed=QueueParser.parse(args.getAsJsonArray("steps"),schemas,all.keySet(),TaskSettings.queueMaxStepsPerAdd,LIMITS.maxArgsChars(),LIMITS.maxAddChars());if(!parsed.ok())return error("Invalid replacement: "+String.join("; ",parsed.errors()));String invalidGuard=validateGuards(parsed.steps());if(invalidGuard!=null)return error("Invalid replacement: "+invalidGuard);List<QueuedStep> steps=parsed.steps().stream().map(step->bindCaller(step,allowed.get(step.tool()),caller)).toList();if(steps.stream().anyMatch(QueuedStep::creativeSensitive)&&!CreativeModeService.mayStartWorldChangingTask(c,caller.uuid()))return error("Creative mode is active; only its online operator owner can request world-changing tasks.");long firstNewId=q.nextJobId();var r=q.replace(id,steps);if(!r.ok())return error(r.text());UUID principal=caller.uuid();for(int i=0;i<steps.size();i++){QueuedStep step=steps.get(i);TaskToolDef def=allowed.get(step.tool());c.taskRunner().registerFactory(firstNewId+i,ctx->createTask(def,ctx.companion(),currentCaller(ctx.companion(),principal),step.args()));} }                case "optimize" -> {Long plan=args.has("plan")?args.get("plan").getAsLong():null;var r=QueueOptimizer.apply(q,plan,n->{TaskToolDef d=all.get(n);return d==null?null:d.queueTraits().mergeableCountArg();});runner.wakeQueue();return ToolResult.ok(r.text());}
                default -> {return error("unknown queue_edit action: "+action);}
            }
        } catch(IllegalArgumentException e){return error(e.getMessage());}
        catch(RuntimeException e){return error("invalid queue edit arguments");}
        runner.wakeQueue();return ToolResult.ok("queue updated");
    }
    private static Map<String,TaskToolDef> allowed(Caller caller,Map<String,TaskToolDef> all){Map<String,TaskToolDef> map=new LinkedHashMap<>();all.forEach((n,d)->{if(caller.access().allows(d.minAccess()))map.put(n,d);});return map;}
    private static void registerFactories(CompanionEntity c,List<Long> ids,Map<String,TaskToolDef> allowed,UUID principal){for(long id:ids){var job=c.taskRunner().queue().job(id).orElse(null);if(job==null)continue;TaskToolDef def=allowed.get(job.tool());if(def!=null){JsonObject savedArgs=job.args();c.taskRunner().registerFactory(id,ctx->createTask(def,ctx.companion(),currentCaller(ctx.companion(),principal),savedArgs.deepCopy()));}}}
    private static QueuedStep bindCaller(QueuedStep step,TaskToolDef def,Caller caller){return new QueuedStep(step.tool(),step.args(),step.priority(),step.guards(),step.onUnmet(),step.onFailure(),step.maxAttempts(),step.afterSteps(),caller==null?null:caller.uuid(),def.creativeSensitive());}
    private static dev.friendai.task.Task createTask(TaskToolDef def,CompanionEntity c,Caller caller,JsonObject args){return def.callerFactory()==null?def.factory().create(c,args.deepCopy()):def.callerFactory().create(c,caller,args.deepCopy());}
    private static Caller currentCaller(CompanionEntity c,UUID principal){if(principal==null)return new Caller(null,"",AccessLevel.GUEST);AccessLevel access=c.ownerUuid()!=null&&c.ownerUuid().equals(principal)?AccessLevel.OWNER:AccessLevel.GUEST;if(c.level() instanceof ServerLevel level&&access!=AccessLevel.OWNER&&c.ownerUuid()!=null&&FriendaiServerState.get(level.getServer()).memory().book(c.ownerUuid()).isTrusted(principal))access=AccessLevel.TRUSTED;return new Caller(principal,"",access);}    private static String validateGuards(List<QueuedStep> steps){for(int i=0;i<steps.size();i++)for(var guard:steps.get(i).guards()){if(guard instanceof Guard.HasItem g&&ItemMatcher.parse(g.item()).isEmpty())return "step "+(i+1)+": unknown item '"+g.item()+"'";if(guard instanceof Guard.BlockWithin g&&BlockMatcher.parse(g.block()).isEmpty())return "step "+(i+1)+": unknown block '"+g.block()+"'";}return null;}
    private static long required(JsonObject a,String key){if(!a.has(key))throw new IllegalArgumentException("queue_edit requires "+key);return a.get(key).getAsLong();}
    private static ToolResult error(String text){return ToolResult.error(text==null?"invalid queue edit":text);}
    private static final class JobData { static void validateTool(dev.friendai.queue.Job job,Map<String,TaskToolDef> all,Caller caller){if(job==null)throw new IllegalArgumentException("unknown job");TaskToolDef def=all.get(job.tool());if(def==null)throw new IllegalArgumentException("job #"+job.id()+"'s tool is no longer available");if(!caller.access().allows(def.minAccess()))throw new IllegalArgumentException("job #"+job.id()+"'s tool is not available to this caller");} }
}
