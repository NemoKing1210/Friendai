package dev.friendai.features.instructions;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SkillHelpTest {
    private static final String BODY = "Decide whether the lesson is a fact or a procedure.";

    @Test
    void returnsTheShippedChecklistWithGeneratedLimits() {
        String help = SkillHelp.render(BODY, 6, 2, true);
        assertTrue(help.contains(BODY), "the procedure body is returned verbatim");
        assertTrue(help.contains(String.valueOf(SkillLimits.BODY)));
        assertTrue(help.contains(String.valueOf(SkillLimits.TERMS)));
        for (var kind : InstructionSkill.Kind.values()) {
            assertTrue(help.contains(kind.name().toLowerCase(java.util.Locale.ROOT)), "missing " + kind);
        }
        assertTrue(help.contains("6 writes per hour and 2 per outcome"));
        assertTrue(help.contains("stays pending until the owner approves"));
    }

    @Test
    void namesTheRealRailsAndFailsHonestlyWithoutABody() {
        assertTrue(SkillHelp.render(BODY, 4, 1, false).contains("active at once"));
        assertTrue(SkillHelp.render(BODY, 0, 2, true).contains("Teaching writes are disabled"));
        assertTrue(SkillHelp.render(BODY, 6, 0, true).contains("Teaching writes are disabled"));
        assertEquals("The authoring checklist is unavailable.", SkillHelp.render("  ", 6, 2, true));
    }
}
