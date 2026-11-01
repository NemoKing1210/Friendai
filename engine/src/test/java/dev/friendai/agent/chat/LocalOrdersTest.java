package dev.friendai.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.ToolCall;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LocalOrdersTest {
    private static String name(String text) {
        return LocalOrders.parse(text).map(ToolCall::name).orElse("UNKNOWN");
    }

    @Test
    void theSafeOrdersRunInBothLanguages() {
        assertEquals("follow_player", name("follow me"));
        assertEquals("follow_player", name("Follow me!"));
        assertEquals("follow_player", name("please follow me"));
        assertEquals("follow_player", name("иди за мной"));
        assertEquals("stay", name("stay here"));
        assertEquals("stay", name("жди здесь"));
        assertEquals("go_to", name("come here"));
        assertEquals("go_to", name("подойди ко мне"));
        assertEquals("stop", name("stop"));
        assertEquals("stop", name("остановись"));
        assertEquals("queue_show", name("status"));
        assertEquals("get_inventory", name("inventory"));
    }

    @Test
    void aPoliteQuestionIsStillAnOrder() {
        assertEquals("follow_player", name("can you follow me?"));
        assertEquals("stay", name("could you stay?"));
    }

    @Test
    void questionsAndRetellingsStayWithTheModel() {
        assertTrue(LocalOrders.parse("why did you follow me?").isEmpty());
        assertTrue(LocalOrders.parse("what are you doing?").isEmpty());
        assertTrue(LocalOrders.parse("you followed me!").isEmpty());
        assertTrue(LocalOrders.parse("how far can you go?").isEmpty());
        assertTrue(LocalOrders.parse("почему ты за мной идешь?").isEmpty());
        assertTrue(LocalOrders.parse("что ты делаешь?").isEmpty());
        assertTrue(LocalOrders.parse("").isEmpty());
        assertTrue(LocalOrders.parse("bring me diamonds").isEmpty());
    }

    @Test
    void theCallCarriesTheSameArgumentsTheModelWouldUse() {
        Optional<ToolCall> come = LocalOrders.parse("come here");
        assertTrue(come.isPresent());
        assertEquals("go_to", come.get().name());
        assertEquals("owner", come.get().arguments().get("target").getAsString());
        assertFalse(come.get().rawArguments().isBlank());
    }
}
