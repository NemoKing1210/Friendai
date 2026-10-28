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
}
