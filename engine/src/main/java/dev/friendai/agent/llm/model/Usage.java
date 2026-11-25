package dev.friendai.agent.llm.model;

/**
 * Tokens one response was billed for.
 *
 * @param inputTokens every prompt token, cached ones included, as the provider counts them
 * @param outputTokens the tokens generated
 * @param cacheReadTokens prompt tokens the provider served from its prefix cache, billed at a fraction of the price
 * @param cacheWriteTokens prompt tokens written to the prefix cache this request, billed above the base price
 */
public record Usage(int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens) {
    /** A provider that reports no cache figures, or a scripted response. */
    public Usage(int inputTokens, int outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }
}
