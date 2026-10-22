package dev.friedai.llm.provider;

import dev.friedai.llm.protocol.LlmProtocol;

public record Route(LlmProtocol protocol, Endpoint endpoint) {}
