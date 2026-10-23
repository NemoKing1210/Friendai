package dev.friedai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friedai.tool.AccessLevel;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BrainEventTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void chatLine() {
        assertEquals("[Steve]: hello", new BrainEvent.PlayerSaid(ID, "Steve", "hello", BrainEvent.Source.CHAT).toPromptLine());
    }

    @Test
    void voiceLine() {
        assertEquals("[Steve, by voice]: hello", new BrainEvent.PlayerSaid(ID, "Steve", "hello", BrainEvent.Source.VOICE).toPromptLine());
    }

    @Test
    void roleLine() {
        assertEquals("[Bob (guest)]: hi",
                new BrainEvent.PlayerSaid(ID, "Bob", "hi", BrainEvent.Source.CHAT).toPromptLine(AccessLevel.GUEST));
        assertEquals("[Bob (guest), by voice]: hi",
                new BrainEvent.PlayerSaid(ID, "Bob", "hi", BrainEvent.Source.VOICE).toPromptLine(AccessLevel.GUEST));
    }

    @Test
    void finishedLine() {
        assertEquals("[event] task #7 mine_blocks finished: mined 3 logs",
                new BrainEvent.TaskFinished(7, "mine_blocks", true, "mined 3 logs").toPromptLine());
    }

    @Test
    void failedLine() {
        assertEquals("[event] task #7 mine_blocks failed: no logs nearby",
                new BrainEvent.TaskFinished(7, "mine_blocks", false, "no logs nearby").toPromptLine());
    }

    @Test
    void cancelledLine() {
        assertEquals("[event] task #7 mine_blocks cancelled: replaced by a new task",
                new BrainEvent.TaskCancelled(7, "mine_blocks", "replaced by a new task").toPromptLine());
    }
}
