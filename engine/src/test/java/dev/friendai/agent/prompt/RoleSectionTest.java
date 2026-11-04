package dev.friendai.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.goal.GoalView;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.turn.Persona;
import dev.friendai.agent.turn.PromptBuilder;
import dev.friendai.agent.turn.TurnKind;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the companion is told about handing over the result of work its owner asked for. */
class RoleSectionTest {
    private static final Persona PERSONA = new Persona("F", "d", "auto");
    private final ToolCatalog catalog = ToolCatalog.defaults();

    private String prompt(AccessLevel access) {
        Caller caller = new Caller(UUID.randomUUID(), "S", access);
        return PromptBuilder.system(
                PERSONA,
                "Position: 1 2 3",
                caller,
                "",
                TurnKind.PLAYER,
                GoalView.EMPTY,
                0,
                catalog.hints(EnumSet.allOf(ToolGroup.class)));
    }

    @Test
    void theOwnersRequestIsThePermissionToHandItemsOver() {
        String s = prompt(AccessLevel.OWNER);
        assertTrue(
                s.contains("their request is the permission") && s.contains("finish it by calling give_items"),
                "the owner's request must be stated as the permission to hand the items over: " + s);
    }

    @Test
    void aPlanThatMakesItemsForTheOwnerEndsWithDelivery() {
        assertTrue(
                prompt(AccessLevel.OWNER).contains("ends with give_items"),
                "the rules must say a plan for the owner ends with the hand-over");
    }

    @Test
    void aGuestIsToldNothingAboutHandingItemsOver() {
        // The owner-only role line is what this section can prove: the tool note reaches every caller through the
        // catalog, so a guest sees the sentence without the rule that follows from it.
        String s = prompt(AccessLevel.GUEST);
        assertFalse(s.contains("finish it by calling give_items"), s);
    }
}
