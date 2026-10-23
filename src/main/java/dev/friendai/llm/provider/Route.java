package dev.friendai.llm.provider;

import dev.friendai.llm.protocol.LlmProtocol;

public record Route(LlmProtocol protocol, Endpoint endpoint) {}
