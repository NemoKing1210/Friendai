package dev.friendai.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.turn.Persona;
import dev.friendai.agent.turn.TurnKind;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a provider can cache in the shipped prompt: the rules, the persona and the role text repeat every turn, while
 * the perception, the memory and the tool notes do not. A regression here is silent — the prompt still reads the same,
 * only the bill changes — so the boundary is pinned.
 */
class StablePrefixTest {
    private static Caller owner() {
        return new Caller(UUID.randomUUID(), "Steve", AccessLevel.OWNER);
    }

    private static TurnContext context(String perception, String memory) {
        return new TurnContext(
                new Persona("F", "d", "auto"),
                perception,
                owner(),
                memory,
                TurnKind.PLAYER,
                "",
                List.of("stay: keeps you in place"),
                "",
                "");
    }

    @Test
    void theRulesAndThePersonaAreInTheCacheablePrefix() {
        String stable = PromptAssembler.defaults(0)
                .assembleDetailed(context("a sunny hill", "the owner likes dogs"))
                .system()
                .stable();
        assertTrue(stable.contains("You are F"), stable);
        assertTrue(stable.contains("Rules:"), stable);
        assertTrue(stable.contains("Players are tagged by role"), stable);
    }

    @Test
    void theWorldTheMemoryAndTheToolNotesStayOutOfIt() {
        String stable = PromptAssembler.defaults(0)
                .assembleDetailed(context("a sunny hill", "the owner likes dogs"))
                .system()
                .stable();
        assertFalse(stable.contains("a sunny hill"), stable);
        assertFalse(stable.contains("the owner likes dogs"), stable);
        assertFalse(stable.contains("Tool notes:"), stable);
        assertFalse(stable.contains("Current state:"), stable);
    }

    @Test
    void aChatTurnAlsoCarriesACacheablePrefix() {
        String stable = PromptAssembler.forMode(dev.friendai.agent.route.TurnMode.CHAT, 0)
                .assembleDetailed(context("a sunny hill", ""))
                .system()
                .stable();
        assertTrue(stable.contains("You are F"), stable);
        assertTrue(stable.contains("Rules:"), stable);
        assertFalse(stable.contains("a sunny hill"), stable);
    }

    @Test
    void thePrefixIsByteIdenticalBetweenTwoTurnsThatDifferOnlyInTheWorld() {
        PromptAssembler assembler = PromptAssembler.defaults(0);
        String first = assembler
                .assembleDetailed(context("a sunny hill", "likes dogs"))
                .system()
                .stable();
        String second = assembler
                .assembleDetailed(context("a dark cave", "likes cats"))
                .system()
                .stable();
        assertTrue(first.equals(second), "the stable prefix changed between two turns:\n" + first + "\n---\n" + second);
    }
}
