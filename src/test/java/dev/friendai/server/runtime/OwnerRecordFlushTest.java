package dev.friendai.server.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.features.memory.CompanionProfile;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import dev.friendai.infrastructure.persistence.autonomy.FreeTimeStore;
import dev.friendai.infrastructure.persistence.goals.GoalStore;
import dev.friendai.infrastructure.persistence.memory.CompanionProfileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerRecordFlushTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @TempDir
    Path world;

    @Test
    void tickFlushesProfileGoalAndFreeTimeBeforeShutdown() throws Exception {
        Path records = world.resolve("friendai");
        try (var writer = new OwnerStoreWriter();
                var profiles = new CompanionProfileStore(records.resolve("profiles"), writer);
                var goals = new GoalStore(records.resolve("goals"), writer);
                var freeTime = new FreeTimeStore(records.resolve("free-time"), writer)) {
            profiles.save(OWNER, CompanionProfile.EMPTY);
            GoalBoard board = goals.board(OWNER, 3, 2);
            board.set("gather oak", List.of(), false, 1);
            goals.save(OWNER, board);
            new FreeTimeService(freeTime).start(OWNER, FreeTime.Origin.COMMAND, "wood", 1);

            new OwnerRecordFlush(profiles, goals, freeTime).tick();

            awaitFile(records.resolve("profiles").resolve(OWNER + ".json"));
            awaitFile(records.resolve("goals").resolve(OWNER + ".json"));
            awaitFile(records.resolve("free-time").resolve(OWNER + ".json"));
            assertTrue(Files.exists(records.resolve("profiles").resolve(OWNER + ".json")));
            assertTrue(Files.exists(records.resolve("goals").resolve(OWNER + ".json")));
            assertTrue(Files.exists(records.resolve("free-time").resolve(OWNER + ".json")));
        }
    }

    private static void awaitFile(Path path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(
                Files.exists(path),
                "periodic tick did not persist " + path.getParent().getFileName());
    }
}
