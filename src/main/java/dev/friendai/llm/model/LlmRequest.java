package dev.friendai.llm.model;

import java.util.List;

/** @param temperature null means "omit from the wire request" */
public record LlmRequest(
        String model,
        String system,
        List<Message> messages,
        List<ToolSpec> tools,
        Double temperature,
        int maxTokens,
        boolean jsonObject) {
    public LlmRequest(
            String model,
            String system,
            List<Message> messages,
            List<ToolSpec> tools,
            Double temperature,
            int maxTokens) {
        this(model, system, messages, tools, temperature, maxTokens, false);
    }
}
