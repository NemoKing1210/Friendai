package dev.friendai.protocol.memory;

import dev.friendai.protocol.ClientRequest;
import dev.friendai.protocol.ProtocolFamily;
import java.util.UUID;

public sealed interface MemoryRequest extends ClientRequest
        permits MemoryRequest.Open,
                MemoryRequest.Close,
                MemoryRequest.FactAdd,
                MemoryRequest.FactEdit,
                MemoryRequest.FactForget,
                MemoryRequest.FactClear,
                MemoryRequest.TrustSet,
                MemoryRequest.PersonaSet,
                MemoryRequest.TranscriptClear {
    @Override
    default ProtocolFamily family() {
        return ProtocolFamily.MEMORY;
    }

    record Open() implements MemoryRequest {
        @Override
        public int operationId() {
            return 1;
        }
    }

    record Close() implements MemoryRequest {
        @Override
        public int operationId() {
            return 2;
        }
    }

    record FactAdd(String groupKey, int categoryId, String text) implements MemoryRequest {
        @Override
        public int operationId() {
            return 3;
        }
    }

    record FactEdit(int factId, int categoryId, String text) implements MemoryRequest {
        @Override
        public int operationId() {
            return 4;
        }
    }

    record FactForget(int factId) implements MemoryRequest {
        @Override
        public int operationId() {
            return 5;
        }
    }

    record FactClear(String groupKey) implements MemoryRequest {
        @Override
        public int operationId() {
            return 6;
        }
    }

    record TrustSet(UUID target, boolean trusted) implements MemoryRequest {
        @Override
        public int operationId() {
            return 7;
        }
    }

    record PersonaSet(String text) implements MemoryRequest {
        @Override
        public int operationId() {
            return 8;
        }
    }

    /** Empties the sender's own recent-chat transcript. */
    record TranscriptClear() implements MemoryRequest {
        @Override
        public int operationId() {
            return 9;
        }
    }
}
