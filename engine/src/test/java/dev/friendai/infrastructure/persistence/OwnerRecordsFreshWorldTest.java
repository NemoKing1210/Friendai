package dev.friendai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.autonomy.NeedKind;
import dev.friendai.agent.goal.GoalService;
import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.CompanionDescription;
import dev.friendai.features.memory.CompanionProfile;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.OwnerProfile;
import dev.friendai.features.memory.Subject;
import dev.friendai.infrastructure.persistence.autonomy.FreeTimeStore;
import dev.friendai.infrastructure.persistence.goals.GoalStore;
import dev.friendai.infrastructure.persistence.memory.CompanionProfileStore;
import dev.friendai.infrastructure.persistence.memory.MemoryStore;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerRecordsFreshWorldTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @TempDir
    Path world;

    @Test
    void allOwnerDomainsReloadFromAnIndependentWorldDirectory() {
        Path root = world.resolve("friendai");
        try (var writer = new OwnerStoreWriter()) {
            var memory = new MemoryStore(root.resolve("memory"), writer);
            var profiles = new CompanionProfileStore(root.resolve("profiles"), writer);
            var goals = new GoalStore(root.resolve("goals"), writer);
            var freeTime = new FreeTimeStore(root.resolve("free-time"), writer);

            memory.book(OWNER)
                    .addFact(Subject.WORLD, Category.NOTE, "fresh world", OWNER, "Owner", 1, MemoryLimits.DEFAULTS);
            profiles.save(
                    OWNER,
                    new CompanionProfile(
                            "Mira", new CompanionDescription("Guide", "Details"), new OwnerProfile("en", "Owner")));
            var goalService = new GoalService(goals, () -> 2);
            assertTrue(goalService
                    .board(OWNER)
                    .set("gather oak", List.of(), false, 4)
                    .ok());
            goalService.save(OWNER);
            var freeTimeService = new FreeTimeService(freeTime);
            freeTimeService.start(OWNER, FreeTime.Origin.IDLE, "food", 20);
            freeTimeService.beginShift(OWNER, "find food", List.of(), "", NeedKind.HUNGER, false, 21, 500);

            freeTime.close();
            goals.close();
            profiles.close();
            memory.close();
        }

        try (var writer = new OwnerStoreWriter()) {
            var memory = new MemoryStore(root.resolve("memory"), writer);
            var profiles = new CompanionProfileStore(root.resolve("profiles"), writer);
            var goals = new GoalStore(root.resolve("goals"), writer);
            var freeTime = new FreeTimeStore(root.resolve("free-time"), writer);

            assertEquals("fresh world", memory.book(OWNER).allFacts().getFirst().text());
            assertEquals("Mira", profiles.profile(OWNER).name());
            assertEquals(
                    "gather oak",
                    goals.board(OWNER, 3, 2).primary().orElseThrow().text());
            assertEquals("find food", freeTime.state(OWNER).shift().goal());

            freeTime.close();
            goals.close();
            profiles.close();
            memory.close();
        }
    }
}
