package dev.friedai.brain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptBuilderTest {
    @Test
    void containsPersonaAndPerception() {
        String s = PromptBuilder.system(new Persona("Frida", "Cheerful helper", "auto"), "Position: 1 2 3");
        assertTrue(s.contains("Frida"));
        assertTrue(s.contains("Cheerful helper"));
        assertTrue(s.contains("Position: 1 2 3"));
    }

    @Test
    void languageAutoInstruction() {
        assertTrue(PromptBuilder.system(new Persona("F", "d", "auto"), "p").contains("Reply in the same language the player uses"));
        assertTrue(PromptBuilder.system(new Persona("F", "d", "Russian"), "p").contains("Always reply in Russian"));
    }

    @Test
    void planRules() {
        String s = PromptBuilder.system(new Persona("F", "d", "auto"), "p");
        assertTrue(s.contains("send them all at once with run_plan"));
        assertTrue(s.contains("If a plan stops at a failed step"));
    }
}
