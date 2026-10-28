package dev.friendai.agent.llm.provider;

import dev.friendai.agent.llm.protocol.LlmProtocol;

public record Route(LlmProtocol protocol, Endpoint endpoint) {}
