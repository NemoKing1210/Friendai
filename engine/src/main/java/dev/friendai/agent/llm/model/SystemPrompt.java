package dev.friendai.agent.llm.model;

/**
 * A system prompt in two parts: the text that is identical from turn to turn and the text that changes with the world.
 *
 * <p>Providers cache a request <em>prefix</em>, not a request. The tool schemas and the leading system text are billed
 * once and read back cheaply while they stay byte-identical, and nothing after the first changed character can be
 * reused. Keeping the stable text first and naming where it ends is what lets a protocol put its cache breakpoint
 * there; the volatile remainder follows it and is read at full price.
 *
 * @param stable the leading text, identical between the turns of a conversation
 * @param volatileText the per-turn remainder: perception, memory, mission, tool notes and skill instructions
 */
public record SystemPrompt(String stable, String volatileText) {
    /** A prompt with nothing stable in it: no prefix a provider could reuse. */
    public static final SystemPrompt EMPTY = new SystemPrompt("", "");

    public SystemPrompt {
        stable = stable == null ? "" : stable;
        volatileText = volatileText == null ? "" : volatileText;
    }

    /**
     * A prompt whose text the caller cannot separate. It is treated as entirely volatile: a wrong cache breakpoint
     * costs a cache write on every turn instead of saving one, so the unclaimed case stays uncached.
     */
    public static SystemPrompt of(String text) {
        return new SystemPrompt("", text);
    }

    /** The whole prompt, stable part first. */
    public String text() {
        if (stable.isEmpty()) {
            return volatileText;
        }
        return volatileText.isEmpty() ? stable : stable + "\n" + volatileText;
    }

    /** Whether a provider has a prefix worth caching. */
    public boolean hasStablePart() {
        return !stable.isEmpty();
    }
}
