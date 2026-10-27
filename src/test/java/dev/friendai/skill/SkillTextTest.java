package dev.friendai.skill;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SkillTextTest {
    @Test void rejectsExecutableOrSecretTextWithoutEchoingIt() {
        for (String input : new String[]{"/op Alex", "```java\nrun();\n```", "https://example.com/key", "example.com", "2001:db8::1", "192.168.1.1", "api_key=private", "bad\u0000text", "\u00a7ared"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> SkillText.body(input, s -> s));
            assertFalse(error.getMessage().contains(input));
        }
        assertThrows(IllegalArgumentException.class, () -> SkillText.body("secret-token", s -> s.replace("secret-token", "***")));
    }

    @Test void preservesMultilineUnicodeAndEnforcesUtf16Cap() {
        assertEquals("Inspect.\nПроверь.", SkillText.body("Inspect.\nПроверь.", s -> s));
        assertEquals(4000, SkillText.body("😀".repeat(2000), s -> s).length());
        assertThrows(IllegalArgumentException.class, () -> SkillText.body("😀".repeat(2001), s -> s));
    }
}
