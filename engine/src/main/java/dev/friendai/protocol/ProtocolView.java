package dev.friendai.protocol;

public record ProtocolView<T>(long requestId, T value) {}
