package dev.friendai.agent.llm.stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.StopReason;
import org.junit.jupiter.api.Test;

/** A streamed reply is complete only when the provider said so, never because some content arrived. */
class StreamAssemblyTest {
    @Test
    void textWithoutATerminalFrameIsNotACompleteReply() {
        StreamAssembly assembly = new StreamAssembly();
        assembly.text("Hel");
        assembly.text("lo");

        assertTrue(assembly.started(), "text arrived");
        assertFalse(assembly.terminal(), "nothing said the reply ended");
    }

    @Test
    void toolCallFragmentsWithoutATerminalFrameAreNotACompleteReply() {
        StreamAssembly assembly = new StreamAssembly();
        assembly.toolCall(0, "call-1", "mine_blocks", "{\"count\":");

        assertTrue(assembly.started(), "a tool call arrived");
        assertFalse(assembly.terminal(), "nothing said the reply ended");
    }

    @Test
    void aStopReasonIsTerminal() {
        StreamAssembly assembly = new StreamAssembly();
        assembly.text("done");
        assembly.stop(StopReason.END);

        assertTrue(assembly.terminal());
    }

    @Test
    void aDoneFrameIsTerminalEvenWithoutContent() {
        StreamAssembly assembly = new StreamAssembly();
        assembly.done();

        assertFalse(assembly.started(), "an empty stream carried nothing");
        assertTrue(assembly.terminal());
    }
}
