package dev.friendai.agent.llm.model;

public enum StopReason {
    END,
    TOOL_USE,
    MAX_TOKENS,
    OTHER
}
