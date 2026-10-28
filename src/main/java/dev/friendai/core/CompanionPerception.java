package dev.friendai.core;

import dev.friendai.brain.PerceptionSource;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.perception.PerceptionBuilder;
import dev.friendai.tool.Caller;

/** The brain's view of one companion's world, including where the speaker stands and looks. */
public final class CompanionPerception implements PerceptionSource {
    private final CompanionEntity companion;

    public CompanionPerception(CompanionEntity companion) {
        this.companion = companion;
    }

    @Override
    public String describe() {
        return PerceptionBuilder.brief(companion, null).toPromptText();
    }

    @Override
    public String describe(Caller caller) {
        return PerceptionBuilder.brief(companion, caller == null ? null : caller.uuid())
                .toPromptText();
    }
}
