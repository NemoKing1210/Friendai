package dev.friendai.skill;

import dev.friendai.agent.prompt.*;
import java.util.Optional;

/** A cuttable immutable section. No callback accesses mutable books. */
public record SkillPromptSection(String id, String text, int priority) implements PromptSection {
    @Override
    public Optional<String> render(TurnContext context) {
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }
}
