package dev.friendai.skill;
import com.google.gson.*;
import java.util.Locale;
/** Bounded JSON parsing at the menu/command boundary, never throwing to its caller. */
public final class SkillMenuActions {
    private final SkillActions actions;
    private final SkillActor actor;
    public SkillMenuActions(SkillActions actions,SkillActor actor) { this.actions=actions;this.actor=actor; }
    public SkillActionResult apply(String operation,String json) {
        try {
            if(json==null || json.length()>32768 || operation==null || operation.length()>32)return SkillActionResult.error();
            boolean desiredOperation=operation.equals("enable")||operation.equals("disable");
            var op=desiredOperation?SkillAction.Operation.TOGGLE:SkillAction.Operation.valueOf(operation.toUpperCase(Locale.ROOT));
            JsonObject o=JsonParser.parseString(json).getAsJsonObject();
            boolean global=o.has("server")&&o.get("server").getAsBoolean();
            String id=o.has("id")?o.get("id").getAsString():"";
            int version=o.has("expectedVersion")?o.get("expectedVersion").getAsInt():0;
            SkillDraft draft=(op==SkillAction.Operation.CREATE||op==SkillAction.Operation.EDIT)?SkillTransfer.read(json):null;
            return actions.apply(actor,new SkillAction(op,id,version,draft,global,desiredOperation?operation:json));
        } catch(RuntimeException | StackOverflowError e) { return SkillActionResult.error(); }
    }
}
