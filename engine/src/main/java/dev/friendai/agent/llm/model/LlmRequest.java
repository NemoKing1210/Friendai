package dev.friendai.agent.llm.model;

import java.util.List;

/**
 * One provider request.
 *
 * @param system the prompt, carrying the boundary between its stable prefix and its per-turn remainder
 * @param temperature null means "omit from the wire request"
 */
public record LlmRequest(
        String model,
        SystemPrompt system,
        List<Message> messages,
        List<ToolSpec> tools,
        Double temperature,
        int maxTokens,
        boolean jsonObject,
        dev.friendai.agent.llm.RequestTelemetry telemetry) {
    public LlmRequest {
        system = system == null ? SystemPrompt.EMPTY : system;
        telemetry = telemetry == null ? dev.friendai.agent.llm.RequestTelemetry.NONE : telemetry;
    }

    /** A caller with one string knows no boundary; its text is treated as entirely volatile. */
    public LlmRequest(
            String model,
            String system,
            List<Message> messages,
            List<ToolSpec> tools,
            Double temperature,
            int maxTokens) {
        this(
                model,
                SystemPrompt.of(system),
                messages,
                tools,
                temperature,
                maxTokens,
                false,
                dev.friendai.agent.llm.RequestTelemetry.NONE);
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
                SystemPrompt.of(system),
                messages,
                tools,
                temperature,
                maxTokens,
                jsonObject,
                dev.friendai.agent.llm.RequestTelemetry.NONE);
    }

    /** The historical shape with a telemetry scope. */
    public LlmRequest(
            String model,
            String system,
            List<Message> messages,
            List<ToolSpec> tools,
            Double temperature,
            int maxTokens,
            boolean jsonObject,
            dev.friendai.agent.llm.RequestTelemetry telemetry) {
        this(model, SystemPrompt.of(system), messages, tools, temperature, maxTokens, jsonObject, telemetry);
    }
}
