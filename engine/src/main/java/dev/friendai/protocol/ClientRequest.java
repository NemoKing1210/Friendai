package dev.friendai.protocol;

public interface ClientRequest {
    ProtocolFamily family();

    int operationId();
}
