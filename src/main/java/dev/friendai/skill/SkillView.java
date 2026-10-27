package dev.friendai.skill;
import dev.friendai.memory.*;
import java.util.List;
/** Bounded owner-only server view. Lists never carry every body. */
public record SkillView(boolean enabled,long revision,int page,int total,List<Row> rows,InstructionSkill detail,
        CompanionDescription description,OwnerProfile ownerProfile,String statusKey,boolean ok,String exportText) {
    public SkillView { rows=List.copyOf(rows); }
    public record Row(String id,String name,String description,boolean enabled,String source,String scope,String author,
            long createdAt,long updatedAt,int version,String activation) {
        public static Row of(InstructionSkill s,boolean effectiveEnabled) {
            return new Row(s.id(),s.name(),s.description(),s.enabled()&&effectiveEnabled,s.source().name(),s.scope().name(),
                    s.author()==null?"":s.author().toString(),s.createdAt(),s.updatedAt(),s.version(),s.activation().name());
        }
    }
}
