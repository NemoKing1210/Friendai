package dev.friendai.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.turn.Persona;
import dev.friendai.agent.turn.TurnKind;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PromptAssemblerTest {
    private static final TurnContext CTX = new TurnContext(
            new Persona("F", "d", "auto"),
            "p",
            new Caller(UUID.randomUUID(), "S", AccessLevel.OWNER),
            "",
            TurnKind.PLAYER,
            "",
            List.of(),
            "");

    private static PromptSection section(String id, int priority, String text) {
        return new PromptSection() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Optional<String> render(TurnContext ctx) {
                return text == null ? Optional.empty() : Optional.of(text);
            }
        };
    }

    @Test
    void assemblesInListOrder() {
        PromptAssembler a = new PromptAssembler(List.of(section("a", 1, "first"), section("b", 1, "second")), 0);
        assertEquals("first\nsecond", a.assemble(CTX));
    }

    @Test
    void skipsSectionsThatRenderEmpty() {
        PromptAssembler a = new PromptAssembler(
                List.of(section("a", 1, "first"), section("b", 1, null), section("c", 1, "third")), 0);
        assertEquals("first\nthird", a.assemble(CTX));
    }

    @Test
    void cutsLowestPriorityFirstWhenOverBudget() {
        PromptAssembler a = new PromptAssembler(
                List.of(
                        section("keep", 90, "important text"),
                        section("low", 10, "droppable filler"),
                        section("mid", 50, "medium text")),
                30);
        String out = a.assemble(CTX);
        assertFalse(out.contains("droppable"), out);
        assertTrue(out.contains("important text"), out);
    }

    @Test
    void neverCutSectionsSurviveAnyBudget() {
        PromptAssembler a = new PromptAssembler(
                List.of(section("core", PromptSection.NEVER_CUT, "core"), section("extra", 5, "extra")), 1);
        assertEquals("core", a.assemble(CTX));
    }

    @Test
    void unlimitedBudgetKeepsEverything() {
        PromptAssembler a = new PromptAssembler(List.of(section("a", 1, "x".repeat(5000)), section("b", 2, "y")), 0);
        assertEquals(5002, a.assemble(CTX).length());
    }
}
