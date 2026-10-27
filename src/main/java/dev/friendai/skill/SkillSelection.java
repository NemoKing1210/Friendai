package dev.friendai.skill;
import java.util.List;
public record SkillSelection(List<InstructionSkill> skills,int renderedChars) {
    public SkillSelection { skills=List.copyOf(skills); }
}
