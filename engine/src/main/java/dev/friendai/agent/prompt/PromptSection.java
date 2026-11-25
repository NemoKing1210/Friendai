package dev.friendai.agent.prompt;

import java.util.Optional;

/** One block of the system prompt. Pure: it renders from {@link TurnContext} data only. */
public interface PromptSection {
    /** Priority of a section the budget never cuts. */
    int NEVER_CUT = Integer.MAX_VALUE;

    String id();

    /** Higher means cut last when the prompt is over budget. */
    int priority();

    /** The section text without a trailing newline, or empty when the section has nothing to say this turn. */
    Optional<String> render(TurnContext ctx);

    /**
     * Whether this text is identical from turn to turn, so a provider can cache everything up to it. The assembler
     * keeps every stable section ahead of the volatile ones; a section that reads the world, memory, the mission, the
     * queue or the tool list changes every turn and must say so by leaving this false.
     */
    default boolean stable() {
        return false;
    }
}
