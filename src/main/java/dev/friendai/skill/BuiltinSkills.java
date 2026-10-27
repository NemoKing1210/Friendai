package dev.friendai.skill;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
/** Classpath-only packaged instruction catalogue. No path comes from owner/model input. */
public final class BuiltinSkills {
    private BuiltinSkills() {}
    public static List<InstructionSkill> load(ClassLoader loader) {
        try {
            var index=JsonParser.parseString(resource(loader,"index.json",32768)).getAsJsonArray();
            if(index.size()!=9)throw new IllegalArgumentException("Invalid builtin catalogue.");
            List<InstructionSkill> result=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(var element:index) {
                JsonObject row=element.getAsJsonObject();String id=row.get("id").getAsString();
                if(!id.matches("[a-z-]{1,40}")||!ids.add(id))throw new IllegalArgumentException("Invalid builtin id.");
                row.addProperty("body",resource(loader,id+".txt",16000));
                SkillDraft draft=SkillText.validate(SkillTransfer.read(row.toString()),s->s);
                result.add(new InstructionSkill("builtin:"+id,draft.name(),draft.description(),draft.body(),true,
                        InstructionSkill.Scope.SERVER,InstructionSkill.Source.BUILTIN,null,0,0,1,InstructionSkill.Activation.ACTIVE,
                        draft.alwaysOn(),draft.turnKinds(),draft.triggers(),draft.tags(),draft.includeProfile()));
            }
            return List.copyOf(result);
        } catch(IOException|RuntimeException e) { throw new IllegalStateException("Cannot load builtin instruction resources."); }
    }
    private static String resource(ClassLoader loader,String name,int max) throws IOException {
        try(InputStream in=loader.getResourceAsStream("assets/friendai/skills/"+name)) {
            if(in==null)throw new IOException("Missing builtin instruction resource.");
            byte[] data=in.readNBytes(max+1);if(data.length>max)throw new IOException("Oversized instruction resource.");
            return new String(data,StandardCharsets.UTF_8);
        }
    }
    public static Optional<String> titleKey(String id) {
        return Optional.ofNullable(switch(id) {
            case "builtin:smart-planning" -> "friendai.skill.smart_planning";
            case "builtin:autonomy" -> "friendai.skill.autonomy";
            case "builtin:building" -> "friendai.skill.building";
            case "builtin:tree-felling" -> "friendai.skill.tree_felling";
            case "builtin:survival" -> "friendai.skill.survival";
            case "builtin:trade-etiquette" -> "friendai.skill.trade_etiquette";
            case "builtin:memory-discipline" -> "friendai.skill.memory_discipline";
            case "builtin:verification" -> "friendai.skill.verification";
            case "builtin:first-contact" -> "friendai.skill.first_contact";
            default -> null;
        });
    }
}
