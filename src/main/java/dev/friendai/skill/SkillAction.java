package dev.friendai.skill;
/** A validated action; global authority is checked separately from submitted fields. */
public record SkillAction(
        Operation operation, String id, int expectedVersion, SkillDraft draft, boolean server, String text) {
    public enum Operation {
        CREATE,
        EDIT,
        DUPLICATE,
        DELETE,
        TOGGLE,
        APPROVE,
        REJECT,
        IMPORT,
        EXPORT
    }

    public static SkillAction create(SkillDraft draft) {
        return new SkillAction(Operation.CREATE, "", 0, draft, false, "");
    }
}
