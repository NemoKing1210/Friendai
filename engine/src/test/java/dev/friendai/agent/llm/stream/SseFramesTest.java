package dev.friendai.agent.llm.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseFramesTest {
    private static List<SseFrames.Frame> all(SseFrames frames, String... chunks) {
        List<SseFrames.Frame> out = new ArrayList<>();
        for (String chunk : chunks) {
            out.addAll(frames.feed(chunk.getBytes(StandardCharsets.UTF_8)));
        }
        out.addAll(frames.finish());
        return out;
    }

    @Test
    void framesSplitAcrossChunksAndLineEndingsStillDecode() {
        SseFrames frames = new SseFrames();
        List<SseFrames.Frame> out = all(frames, "event: delta\r\ndata: {\"a\":1}\r\n", "\r\n", "data: second\n", "\n");
        assertEquals(2, out.size(), out.toString());
        assertEquals("delta", out.get(0).event());
        assertEquals("{\"a\":1}", out.get(0).data());
        assertEquals("message", out.get(1).event(), "an event without a name is a message");
        assertEquals("second", out.get(1).data());
    }

    @Test
    void aMultiByteCharacterSplitAcrossChunksSurvives() {
        byte[] text = "data: привет\n\n".getBytes(StandardCharsets.UTF_8);
        SseFrames frames = new SseFrames();
        List<SseFrames.Frame> out = new ArrayList<>();
        // Cut in the middle of a two-byte character.
        int cut = 8;
        while ((text[cut] & 0xC0) != 0x80) cut++;
        out.addAll(frames.feed(java.util.Arrays.copyOfRange(text, 0, cut)));
        out.addAll(frames.feed(java.util.Arrays.copyOfRange(text, cut, text.length)));
        out.addAll(frames.finish());
        assertEquals("привет", out.get(0).data());
    }

    @Test
    void commentsAndMultiLineDataAreHandled() {
        SseFrames frames = new SseFrames();
        List<SseFrames.Frame> out = all(frames, ": keep-alive\n", "data: one\n", "data: two\n", "\n");
        assertEquals(1, out.size());
        assertEquals("one\ntwo", out.get(0).data());
    }

    @Test
    void aTornFinalFrameIsDeliveredAtTheEnd() {
        SseFrames frames = new SseFrames();
        List<SseFrames.Frame> out = all(frames, "data: [DONE]");
        assertEquals(1, out.size());
        assertEquals("[DONE]", out.get(0).data());
        assertTrue(all(new SseFrames(), "data: x\n\n").size() == 1);
    }
}
