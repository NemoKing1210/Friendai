package dev.friendai.protocol.speech;

import dev.friendai.protocol.ClientRequest;
import dev.friendai.protocol.ProtocolFamily;

public sealed interface SpeechRequest extends ClientRequest permits SpeechRequest.Transcript {
    @Override
    default ProtocolFamily family() {
        return ProtocolFamily.SPEECH;
    }

    record Transcript(String text) implements SpeechRequest {
        @Override
        public int operationId() {
            return 1;
        }
    }
}
