package dev.friendai.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.Guard;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.QueuedStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pure validation of LLM queue step payloads. */
public final class QueueParser {
    public record Result(List<QueuedStep> steps, List<String> errors) { public boolean ok() { return errors.isEmpty(); } }
    public static final int MAX_GUARDS = 8, MAX_AFTER = 8;
    private QueueParser() {}
    public static Result parse(JsonArray input, Map<String, JsonObject> schemas, Set<String> allTools, int maxSteps, int maxArgs, int maxTotal) {
        List<QueuedStep> steps = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (input == null || input.isEmpty()) return new Result(List.of(), List.of("a plan needs at least 1 step"));
        if (input.size() > maxSteps) return new Result(List.of(), List.of("a plan can have at most " + maxSteps + " steps"));
        int total = 0;
        for (int index=0; index<input.size(); index++) {
            int number=index+1; if (!input.get(index).isJsonObject()) { errors.add("step "+number+": must be an object with a 'tool' string"); continue; }
            try {
                JsonObject obj=input.get(index).getAsJsonObject();
                if (!obj.has("tool") || !obj.get("tool").isJsonPrimitive() || !obj.get("tool").getAsJsonPrimitive().isString()) { errors.add("step "+number+": must be an object with a 'tool' string"); continue; }
                String tool=obj.get("tool").getAsString(); if (!allTools.contains(tool)) { errors.add("step "+number+": unknown tool '"+tool+"'"); continue; } if (!schemas.containsKey(tool)) { errors.add("step "+number+": '"+tool+"' cannot be used in a plan"); continue; }
                JsonObject args=new JsonObject();
                if(obj.has("args")) {
                    if (!obj.get("args").isJsonPrimitive() || !obj.get("args").getAsJsonPrimitive().isString()) { errors.add("step "+number+": args must be a JSON object string"); continue; }
                    String raw=obj.get("args").getAsString(); if(!raw.isBlank()) { var parsed=JsonParser.parseString(raw); if(!parsed.isJsonObject()) { errors.add("step "+number+": args is not a JSON object"); continue; } args=parsed.getAsJsonObject().deepCopy(); }
                }
                int size=args.toString().length(); total+=size; if(size>maxArgs){errors.add("step "+number+": arguments are too large (max "+maxArgs+" characters)");continue;}
                List<String> issues=ArgValidator.validate(schemas.get(tool),args); if(!issues.isEmpty()){errors.add("step "+number+": "+String.join("; ",issues));continue;}
                List<Guard> guards=new ArrayList<>(); if(obj.has("when")) { if(!obj.get("when").isJsonArray()) {errors.add("step "+number+": invalid guard");continue;} try{if(obj.getAsJsonArray("when").size()>MAX_GUARDS){errors.add("step "+number+": at most "+MAX_GUARDS+" guards");continue;}for(var g:obj.getAsJsonArray("when")) guards.add(Guard.parse(g.getAsJsonObject()).orElseThrow());}catch(RuntimeException e){errors.add("step "+number+": invalid guard");continue;} }
                List<Integer> after=new ArrayList<>(); if(obj.has("after")) { if(!obj.get("after").isJsonArray()) {errors.add("step "+number+": after may only name an earlier step");continue;} try{if(obj.getAsJsonArray("after").size()>MAX_AFTER){errors.add("step "+number+": at most "+MAX_AFTER+" dependencies");continue;}for(var a:obj.getAsJsonArray("after")){int value=a.getAsInt();if(value<1||value>index)throw new IllegalArgumentException();after.add(value);}}catch(RuntimeException e){errors.add("step "+number+": after may only name an earlier step");continue;} }
                GuardAction action=GuardAction.WAIT; if(obj.has("on_unmet")){String raw=obj.get("on_unmet").getAsString();if(raw.equals("skip"))action=GuardAction.SKIP;else if(!raw.equals("wait"))throw new IllegalArgumentException();}
                FailurePolicy failure=obj.has("on_failure")?policy(obj.get("on_failure").getAsString()).orElseThrow():null;
                Integer priority=obj.has("priority")?obj.get("priority").getAsInt():null; if(priority!=null&&(priority < -10||priority>10))throw new IllegalArgumentException();
                int attempts=obj.has("max_attempts")?obj.get("max_attempts").getAsInt():1; if(attempts<1||attempts>3)throw new IllegalArgumentException();
                steps.add(new QueuedStep(tool,args,priority,guards,action,failure,attempts,after));
            } catch(RuntimeException e) { errors.add("step "+number+": contains invalid arguments or queue options"); }
        }
        if(total>maxTotal) errors.add("the plan is too large (max "+maxTotal+" characters of arguments)");
        return errors.isEmpty()?new Result(List.copyOf(steps),List.of()):new Result(List.of(),List.copyOf(errors));
    }
    public static Optional<FailurePolicy> policy(String text){ return switch(text){case "ask"->Optional.of(FailurePolicy.ASK);case "skip"->Optional.of(FailurePolicy.SKIP);case "stop_plan"->Optional.of(FailurePolicy.STOP_PLAN);default->Optional.empty();}; }
}
