package dev.friendai.protocol.places;

import dev.friendai.protocol.ClientRequest;
import dev.friendai.protocol.ProtocolFamily;

public sealed interface PlaceRequest extends ClientRequest
        permits PlaceRequest.Open, PlaceRequest.Close, PlaceRequest.Rename, PlaceRequest.Forget {
    @Override
    default ProtocolFamily family() {
        return ProtocolFamily.PLACES;
    }

    record Open() implements PlaceRequest {
        @Override
        public int operationId() {
            return 1;
        }
    }

    record Close() implements PlaceRequest {
        @Override
        public int operationId() {
            return 2;
        }
    }

    record Rename(String oldName, String newName) implements PlaceRequest {
        @Override
        public int operationId() {
            return 3;
        }
    }

    record Forget(String name) implements PlaceRequest {
        @Override
        public int operationId() {
            return 4;
        }
    }
}
