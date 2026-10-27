package dev.friendai.skill;
import dev.friendai.memory.*;
import java.util.*;
/** Stable paging and selected-body lookup over already filtered data. */
public final class SkillViewBuilder {
    private SkillViewBuilder() {}
    public static SkillView build(boolean enabled,long revision,List<InstructionSkill> all,Set<String> disabled,
            int requestedPage,String selected,CompanionDescription description,OwnerProfile profile,SkillActionResult result) {
        int page=Math.clamp(requestedPage,0,Math.max(0,(all.size()-1)/16));
        List<SkillView.Row> rows=all.stream().skip((long)page*16).limit(16).map(s->SkillView.Row.of(s,!disabled.contains(s.id()))).toList();
        InstructionSkill detail=all.stream().filter(s->s.id().equals(selected)).findFirst().orElse(null);
        return new SkillView(enabled,revision,page,all.size(),rows,detail,description,profile,
                result==null?"":result.status().message().key(),result==null||result.status().ok(),result==null?"":result.text());
    }
}
