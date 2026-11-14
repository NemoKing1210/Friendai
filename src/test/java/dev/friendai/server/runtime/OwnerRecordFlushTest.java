package dev.friendai.server.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.features.cartography.TerrainCell;
import dev.friendai.features.cartography.TerrainGrid;
import dev.friendai.features.memory.CompanionProfile;
import dev.friendai.features.social.SocialMemorySave;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import dev.friendai.infrastructure.persistence.autonomy.FreeTimeStore;
import dev.friendai.infrastructure.persistence.cartography.TerrainStore;
import dev.friendai.infrastructure.persistence.goals.GoalStore;
import dev.friendai.infrastructure.persistence.memory.CompanionProfileStore;
import dev.friendai.infrastructure.persistence.social.SocialMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerRecordFlushTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @TempDir
    Path world;

    @Test
    void tickFlushesProfileGoalFreeTimeAndSocialMemoryBeforeShutdown() throws Exception {
        Path records = world.resolve("friendai");
        try (var writer = new OwnerStoreWriter();
                var profiles = new CompanionProfileStore(records.resolve("profiles"), writer);
                var goals = new GoalStore(records.resolve("goals"), writer);
                var freeTime = new FreeTimeStore(records.resolve("free-time"), writer);
                var social = new SocialMemoryStore(records.resolve("social"), writer);
                var terrain = new TerrainStore(records.resolve("terrain"), writer)) {
            profiles.save(OWNER, CompanionProfile.EMPTY);
            GoalBoard board = goals.board(OWNER, 3, 2);
            board.set("gather oak", List.of(), false, 1);
            goals.save(OWNER, board);
            new FreeTimeService(freeTime).start(OWNER, FreeTime.Origin.COMMAND, "wood", 1);
            SocialMemorySave kept = new SocialMemorySave(List.of("raised"), Map.of("raised", 1));
            social.save(OWNER, kept);
            TerrainGrid grid = new TerrainGrid();
            grid.observe(new TerrainCell(
                    new TerrainCell.CellKey("minecraft:overworld", 1, 2), "minecraft:plains", 64, false, "", 5));
            terrain.save(OWNER, grid);

            // One store is flushed per tick, in rotation: five ticks cover the five stores.
            OwnerRecordFlush flush =
                    new OwnerRecordFlush(profiles, goals, freeTime, social, terrain, null, () -> java.util.Map.of());
            for (int tick = 0; tick < 6; tick++) {
                flush.tick();
            }

            awaitFile(records.resolve("profiles").resolve(OWNER + ".json"));
            awaitFile(records.resolve("goals").resolve(OWNER + ".json"));
            awaitFile(records.resolve("free-time").resolve(OWNER + ".json"));
            awaitFile(records.resolve("social").resolve(OWNER + ".json"));
            awaitFile(records.resolve("terrain").resolve(OWNER + ".json"));
            assertTrue(Files.exists(records.resolve("profiles").resolve(OWNER + ".json")));
            assertTrue(Files.exists(records.resolve("goals").resolve(OWNER + ".json")));
            assertTrue(Files.exists(records.resolve("free-time").resolve(OWNER + ".json")));
            assertTrue(Files.exists(records.resolve("social").resolve(OWNER + ".json")));
            assertTrue(Files.exists(records.resolve("terrain").resolve(OWNER + ".json")));
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
