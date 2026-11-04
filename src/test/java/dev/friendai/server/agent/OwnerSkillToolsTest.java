package dev.friendai.server.agent;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.agent.goal.Criterion;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.SkillTurnSession;
import dev.friendai.execution.queue.Guard;
import dev.friendai.features.memory.CompanionProfile;
import dev.friendai.features.memory.CompanionProfileRepository;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.Subject;
import dev.friendai.foundation.teaching.TeachingContext.OutcomeKind;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.server.instructions.SkillService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerSkillToolsTest {
    @TempDir
    Path dir;

    private final UUID owner = UUID.randomUUID();
    private final MemoryBook book = new MemoryBook();
    private final ServerConfig config = ServerConfig.defaults();
    private final List<BrainEvent> posted = new ArrayList<>();
    private final GoalOutcomeRecorder outcomes = new GoalOutcomeRecorder(() -> config, id -> book);

    @Test
    void anEnabledOutcomeReachesTheBrainAndTheGoalMemory() {
        config.skills.enabled = true;
        try (SkillService skills = new SkillService(dir.resolve("skills"), () -> config, new Profiles(), null)) {
            OwnerSkillTools tools = tools(skills);

            tools.outcome(owner, "goal:7", OutcomeKind.GOAL_SUCCESS, "Collected 32 oak logs");

            assertEquals(1, posted.size());
            assertInstanceOf(BrainEvent.SkillOutcome.class, posted.get(0));
            assertFalse(factTexts().isEmpty());
        }
    }

    @Test
    void aDisabledOutcomeIsSilentButStillLeavesTheGoalMemory() {
        config.skills.enabled = false;
        try (SkillService skills = new SkillService(dir.resolve("skills"), () -> config, new Profiles(), null)) {
            OwnerSkillTools tools = tools(skills);
            GoalBoard board = new GoalBoard(3, 2);
            long goalId = board.set(
                            "Collect 32 oak logs",
                            List.of(new Criterion("world-checked", Optional.of(new Guard.TimeElapsed(100)))),
                            false,
                            100L)
                    .value()
                    .id();
            outcomes.captureCheckability(owner, board);

            tools.outcome(owner, "goal:" + goalId, OutcomeKind.GOAL_SUCCESS, "Collected 32 oak logs");

            assertTrue(posted.isEmpty());
            assertEquals(List.of("goal \"Collected 32 oak logs\" — verified"), factTexts());
        }
    }

    @Test
    void theOwnerCatalogueIsReadThroughTheSkillService() {
        try (SkillService skills = new SkillService(dir.resolve("skills"), () -> config, new Profiles(), null)) {
            OwnerSkillTools tools = tools(skills);

            assertFalse(tools.help().error());
            assertFalse(tools.list(owner, 0).error());
        }
    }

    private OwnerSkillTools tools(SkillService skills) {
        return new OwnerSkillTools(
                skills,
                () -> config,
                id -> SkillTurnSession.NONE,
                id -> null,
                (id, event) -> posted.add(event),
                outcomes);
    }

    private List<String> factTexts() {
        return book.facts(Subject.player(owner)).stream()
                .map(fact -> fact.text())
                .toList();
    }

    private static final class Profiles implements CompanionProfileRepository {
        private final Map<UUID, CompanionProfile> profiles = new HashMap<>();

        @Override
        public CompanionProfile profile(UUID owner) {
            return profiles.getOrDefault(owner, CompanionProfile.EMPTY);
        }

        @Override
        public void save(UUID owner, CompanionProfile profile) {
            profiles.put(owner, profile);
        }

        @Override
        public long revision(UUID owner) {
            return 0L;
        }
    }
}
