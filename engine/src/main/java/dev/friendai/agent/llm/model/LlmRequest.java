package dev.friendai.agent.llm.model;

import java.util.List;

/** @param temperature null means "omit from the wire request" */
public record LlmRequest(
        String model,
        String system,
        List<Message> messages,
        List<ToolSpec> tools,
        Double temperature,
        int maxTokens,
        boolean jsonObject,
        dev.friendai.agent.llm.RequestTelemetry telemetry) {
    public LlmRequest(
            String model,
            String system,
            List<Message> messages,
            List<ToolSpec> tools,
            Double temperature,
            int maxTokens) {
        this(model, system, messages, tools, temperature, maxTokens, false);
    }

    /** The historical shape: a request with no telemetry scope. */
    public LlmRequest(
            String model,
            String system,
            List<Message> messages,
            List<ToolSpec> tools,
            Double temperature,
            int maxTokens,
            boolean jsonObject) {
        this(
                model,
                system,
                messages,
                tools,
                temperature,
                maxTokens,
                jsonObject,
                dev.friendai.agent.llm.RequestTelemetry.NONE);
    }

    public LlmRequest {
        telemetry = telemetry == null ? dev.friendai.agent.llm.RequestTelemetry.NONE : telemetry;
    }
}
