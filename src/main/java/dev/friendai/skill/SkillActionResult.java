package dev.friendai.skill;
import dev.friendai.memory.MenuStatus;
import java.util.Optional;
/** Never contains an unvalidated submitted body on failure. */
public record SkillActionResult(MenuStatus status, Optional<InstructionSkill> skill, String text) {
    public static SkillActionResult ok(InstructionSkill skill) {
        return new SkillActionResult(MenuStatus.ok("friendai.menu.skills.saved"),Optional.ofNullable(skill),"");
    }
    public static SkillActionResult error() {
        return new SkillActionResult(MenuStatus.error("friendai.menu.skills.invalid"),Optional.empty(),"");
    }
}
