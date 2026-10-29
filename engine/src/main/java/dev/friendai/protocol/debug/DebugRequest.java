package dev.friendai.protocol.debug;

import dev.friendai.protocol.ClientRequest;
import dev.friendai.protocol.ProtocolFamily;

public sealed interface DebugRequest extends ClientRequest
        permits DebugRequest.PromptGet, DebugRequest.MovementStreamSet {
    @Override
    default ProtocolFamily family() {
        return ProtocolFamily.DEBUG;
    }

    record PromptGet() implements DebugRequest {
        @Override
        public int operationId() {
            return 1;
        }
    }

    record MovementStreamSet(boolean enabled) implements DebugRequest {
        @Override
        public int operationId() {
            return 2;
        }
    }
}
