package dev.friendai.server.agent;

import dev.friendai.agent.turn.PerceptionSource;
import dev.friendai.entity.CompanionCarryData;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Caller;
import dev.friendai.server.agent.perception.PerceptionBuilder;
import dev.friendai.server.agent.perception.PerceptionContext;
import java.util.function.Supplier;

/** The brain's view of one companion's world, including where the speaker stands and looks. */
public final class CompanionPerception implements PerceptionSource {
    private final CompanionEntity companion;
    private final Supplier<PerceptionContext> context;

    public CompanionPerception(CompanionEntity companion, Supplier<PerceptionContext> context) {
        this.companion = companion;
        this.context = context;
    }

    @Override
    public String describe() {
        return withLastDeath(
                PerceptionBuilder.brief(companion, null, context.get()).toPromptText());
    }

    @Override
    public String describe(Caller caller) {
        return withLastDeath(PerceptionBuilder.brief(companion, caller == null ? null : caller.uuid(), context.get())
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
