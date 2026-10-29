package dev.friendai.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.protocol.debug.DebugRequest;
import dev.friendai.protocol.speech.SpeechRequest;
import org.junit.jupiter.api.Test;

final class DebugSpeechRequestCodecTest {
    @Test
    void encodesDebugToggleAsCanonicalBooleanAndExplicitOperation() {
        byte[] encoded = ClientRequestCodec.encode(4, new DebugRequest.MovementStreamSet(false));

        assertArrayEquals(new byte[] {1, 6, 2, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 1, 0}, encoded);
        assertEquals(
                new DebugRequest.MovementStreamSet(false),
                ((DecodedClientRequest) ((DecodeResult.Valid<?>) ClientRequestCodec.decode(encoded)).value())
                        .request());
    }

    @Test
    void boundsTranscriptByUtf8BytesAndRejectsNoncanonicalDebugBoolean() {
        SpeechRequest.Transcript max = new SpeechRequest.Transcript("é".repeat(250));
        byte[] encoded = ClientRequestCodec.encode(1, max);

        assertEquals(
                max,
                ((DecodedClientRequest) ((DecodeResult.Valid<?>) ClientRequestCodec.decode(encoded)).value())
                        .request());
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(2, new SpeechRequest.Transcript("é".repeat(250) + "a")));

        byte[] badBoolean = {1, 6, 2, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 2};
        assertInstanceOf(DecodeResult.Malformed.class, ClientRequestCodec.decode(badBoolean));
    }
}
