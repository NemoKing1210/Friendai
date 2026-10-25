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
    private QueueParser() {}
    public static Result parse(JsonArray input, Map<String, JsonObject> schemas, Set<String> allTools, int maxSteps, int maxArgs, int maxTotal) {
        List<QueuedStep> steps = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (input == null || input.isEmpty()) return new Result(List.of(), List.of("a plan needs at least 1 step"));
        if (input.size() > maxSteps) return new Result(List.of(), List.of("a plan can have at most " + maxSteps + " steps"));
        int total = 0;
        for (int index=0; index<input.size(); index++) {
            int number=index+1; if (!input.get(index).isJsonObject()) { errors.add("step "+number+": must be an object with a 'tool' string"); continue; }
            JsonObject obj=input.get(index).getAsJsonObject(); if (!obj.has("tool") || !obj.get("tool").isJsonPrimitive()) { errors.add("step "+number+": must be an object with a 'tool' string"); continue; }
            String tool=obj.get("tool").getAsString(); if (!allTools.contains(tool)) { errors.add("step "+number+": unknown tool '"+tool+"'"); continue; } if (!schemas.containsKey(tool)) { errors.add("step "+number+": '"+tool+"' cannot be used in a plan"); continue; }
            JsonObject args=new JsonObject(); try { if(obj.has("args")&&!obj.get("args").getAsString().isBlank()) args=JsonParser.parseString(obj.get("args").getAsString()).getAsJsonObject(); } catch(RuntimeException e){ errors.add("step "+number+": args is not a JSON object"); continue; }
            int size=args.toString().length(); total+=size; if(size>maxArgs){errors.add("step "+number+": arguments are too large (max "+maxArgs+" characters)");continue;}
            List<Guard> guards=new ArrayList<>(); if(obj.has("when")) try { for(var g:obj.getAsJsonArray("when")) guards.add(Guard.parse(g.getAsJsonObject()).orElseThrow()); } catch(RuntimeException e){errors.add("step "+number+": invalid guard");continue;}
            List<Integer> after=new ArrayList<>(); if(obj.has("after")) try { for(var a:obj.getAsJsonArray("after")){int value=a.getAsInt();if(value<1||value>index)throw new IllegalArgumentException();after.add(value);} }catch(RuntimeException e){errors.add("step "+number+": after may only name an earlier step");continue;}
            GuardAction action=obj.has("on_unmet")&&"skip".equals(obj.get("on_unmet").getAsString())?GuardAction.SKIP:GuardAction.WAIT;
            FailurePolicy policy=obj.has("on_failure")?policy(obj.get("on_failure").getAsString()).orElse(null):null;
            steps.add(new QueuedStep(tool,args,obj.has("priority")?obj.get("priority").getAsInt():null,guards,action,policy,obj.has("max_attempts")?obj.get("max_attempts").getAsInt():1,after));
        }
        if(total>maxTotal) errors.add("the plan is too large (max "+maxTotal+" characters of arguments)");
        return errors.isEmpty()?new Result(List.copyOf(steps),List.of()):new Result(List.of(),List.copyOf(errors));
    }
    public static Optional<FailurePolicy> policy(String text){ return switch(text){case "ask"->Optional.of(FailurePolicy.ASK);case "skip"->Optional.of(FailurePolicy.SKIP);case "stop_plan"->Optional.of(FailurePolicy.STOP_PLAN);default->Optional.empty();}; }
}
