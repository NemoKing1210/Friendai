package dev.friedai.brain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friedai.tool.AccessLevel;
import dev.friedai.tool.Caller;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static String system(Persona persona, String perception) {
        return PromptBuilder.system(persona, perception, new Caller(ID, "Steve", AccessLevel.OWNER));
    }

    @Test
    void containsPersonaAndPerception() {
        String s = system(new Persona("Frida", "Cheerful helper", "auto"), "Position: 1 2 3");
        assertTrue(s.contains("Frida"));
        assertTrue(s.contains("Cheerful helper"));
        assertTrue(s.contains("Position: 1 2 3"));
    }

    @Test
    void languageAutoInstruction() {
        assertTrue(system(new Persona("F", "d", "auto"), "p").contains("Reply in the same language the player uses"));
        assertTrue(system(new Persona("F", "d", "Russian"), "p").contains("Always reply in Russian"));
    }

    @Test
    void speakerAndRoleRules() {
        String guest = PromptBuilder.system(new Persona("F", "d", "auto"), "p", new Caller(ID, "Bob", AccessLevel.GUEST));
        assertTrue(guest.contains("Players are tagged by role: owner, trusted or guest."));
        assertTrue(guest.contains("Current speaker: Bob (guest)."));
        assertTrue(guest.contains("Guests can only chat"));

        String owner = PromptBuilder.system(new Persona("F", "d", "auto"), "p", new Caller(ID, "", AccessLevel.OWNER));
        assertTrue(owner.contains("Current speaker: your owner (task events)."));
    }
}
