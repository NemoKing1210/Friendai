package dev.friendai.foundation.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class UserMessageTest {
    @Test
    void ofKeepsKeyAndArgs() {
        UserMessage m = UserMessage.of("k", "a", 1);
        assertEquals("k", m.key());
        assertEquals(List.of("a", 1), m.args());
    }
}
