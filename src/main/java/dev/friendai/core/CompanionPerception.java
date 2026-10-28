package dev.friendai.core;

import dev.friendai.brain.PerceptionSource;
import dev.friendai.entity.CompanionCarryData;
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
        return withLastDeath(PerceptionBuilder.brief(companion, null).toPromptText());
    }

    @Override
    public String describe(Caller caller) {
        return withLastDeath(PerceptionBuilder.brief(companion, caller == null ? null : caller.uuid())
                .toPromptText());
    }

    /** The one fact the model must be able to answer "what happened?" with, bounded to a single sentence. */
    private String withLastDeath(String perception) {
        var server = companion.level().getServer();
        var owner = companion.ownerUuid();
        if (server == null || owner == null) {
            return perception;
        }
        String death = CompanionCarryData.get(server).lastDeathLine(owner);
        return death.isEmpty() ? perception : perception + "\n" + death;
    }
}
