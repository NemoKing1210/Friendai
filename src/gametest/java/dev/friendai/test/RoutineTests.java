package dev.friendai.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.routine.Routine;
import dev.friendai.agent.routine.Routine.Outcome;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.PlanStatus;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import dev.friendai.infrastructure.persistence.routines.RoutineStore;
import dev.friendai.server.agent.tools.FriendaiTools;
import dev.friendai.server.runtime.ServerRuntime;
import java.nio.file.Path;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;

/** Owner-defined routines through the real server driver, queue and world tasks. */
public final class RoutineTests {
    @GameTest(maxTicks = 1_800)
    public void morningRoutineFeedsAndMinesOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        MinecraftServer server = h.getLevel().getServer();
        ServerRuntime runtime = GameTestSupport.runtime(server);
        var owner = GameTestSupport.onlinePlayer(h, "routine", 3, 2, 3, true);
        CompanionEntity c = companion(h, owner.id());
        h.spawn(EntityTypes.COW, 4, 2, 4);
        h.spawn(EntityTypes.COW, 5, 2, 4);
        c.inventory().addItem(new ItemStack(Items.WHEAT, 2));
        h.setBlock(6, 2, 2, Blocks.OAK_LOG);
        BlockPos log = h.absolutePos(new BlockPos(6, 2, 2));

        var driver = runtime.sessions().routines();
        ToolResult set = driver.set(
                c,
                caller(owner.id()),
                routine(
                        "morning",
                        "morning",
                        "{\"tool\":\"breed_animals\",\"args\":\"{\\\"target\\\":\\\"minecraft:cow\\\",\\\"pairs\\\":1}\"}",
                        mineAt(log)));
        h.assertFalse(set.error(), set.content());

        Routine routine = driver.state(owner.id()).orElseThrow();
        setClockTo(server, routine);
        driver.tick();

        h.runAfterDelay(900, () -> {
            driver.tick();
            Routine now = driver.state(owner.id()).orElseThrow();
            h.assertValueEqual(now.lastOutcome(), Outcome.SUCCEEDED, "outcome: " + now.lastSummary());
            h.assertValueEqual(c.inventory().countItem(Items.WHEAT), 0, "both cows were fed");
            h.assertTrue(h.getBlockState(new BlockPos(6, 2, 2)).is(Blocks.AIR), "the routine mined its log");
            h.assertValueEqual(countReports(owner), 1L, "one outcome line, got " + owner.drainSystemMessages());

            for (int i = 0; i < 5; i++) {
                driver.tick();
            }
            h.assertValueEqual(countReports(owner), 0L, "no repeated outcome line");
            long markers = c.executionCoordinator().queue().plans().stream()
                    .filter(plan -> plan.goal().startsWith("routine:"))
                    .count();
            h.assertTrue(markers <= 1, "repeated ticks must not enqueue another routine plan: " + markers);

            runtime.routines().flushAll();

            // The next occurrence must start a fresh plan, not read the archived plan from the previous one.
            setClockTo(server, now);
            driver.tick();
            h.assertTrue(
                    c.executionCoordinator().queue().plans().stream()
                            .anyMatch(plan -> plan.goal().startsWith("routine:")),
                    "the second occurrence is a new plan");
            driver.cancel(c, caller(owner.id()), nameArgs("morning"));
            Path dir =
                    server.getWorldPath(LevelResource.ROOT).resolve("friendai").resolve("routines");
            h.succeedWhen(() -> {
                // flushAll only queues the snapshot; wait for the writer before reopening the store.
                if (runtime.routines().status(owner.id()).dirty()) return;
                try (var writer = new OwnerStoreWriter();
                        var reopened = new RoutineStore(dir, writer)) {
                    h.assertValueEqual(
                            reopened.state(owner.id()).orElseThrow().lastOutcome(),
                            Outcome.SUCCEEDED,
                            "the routine survives a restart");
                }
            });
        });
    }

    @GameTest(maxTicks = 800)
    public void ownerOrderComesFirst(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        MinecraftServer server = h.getLevel().getServer();
        ServerRuntime runtime = GameTestSupport.runtime(server);
        var owner = GameTestSupport.onlinePlayer(h, "routine-order", 3, 2, 3, true);
        CompanionEntity c = companion(h, owner.id());
        var driver = runtime.sessions().routines();
        Caller caller = caller(owner.id());
        h.setBlock(8, 2, 8, Blocks.OAK_LOG);
        ToolResult set =
                driver.set(c, caller, routine("morning", "interval_min", mineAt(h.absolutePos(new BlockPos(8, 2, 8)))));
        h.assertFalse(set.error(), set.content());

        var builtins = FriendaiTools.build(() -> 16, MemoryAccess.DISABLED);
        h.setBlock(11, 2, 11, Blocks.OAK_LOG);
        JsonArray ownerSteps = new JsonArray();
        ownerSteps.add(JsonParser.parseString(mineAt(h.absolutePos(new BlockPos(11, 2, 11))))
                .getAsJsonObject());
        var ownerPlan = JsonParser.parseString("{\"goal\":\"owner order\",\"priority\":0,\"steps\":[]}")
                .getAsJsonObject();
        ownerPlan.add("steps", ownerSteps);
        ToolResult queued = GameTestToolRegistry.addPlan(c, caller, ownerPlan, builtins.plannable(), false, Set.of());
        h.assertFalse(queued.error(), queued.content());
        long ownerPlanId = c.executionCoordinator().queue().plans().getFirst().id();

        Routine routine = driver.state(owner.id()).orElseThrow();
        setClockTo(server, routine);
        driver.tick();
        h.assertTrue(
                c.executionCoordinator().queue().plans().stream()
                        .noneMatch(plan -> plan.goal().startsWith("routine:")),
                "the routine must wait behind an owner order");
        h.assertValueEqual(
                driver.state(owner.id()).orElseThrow().lastOutcome(), Outcome.NONE, "no occurrence started yet");

        c.executionCoordinator().cancelPlan(ownerPlanId, "test released the order");
        driver.tick();
        h.assertTrue(
                c.executionCoordinator().queue().plans().stream()
                        .anyMatch(plan -> plan.goal().startsWith("routine:")),
                "the routine starts once the owner order is gone");

        driver.cancel(c, caller, nameArgs("morning"));
        h.succeed();
    }

    @GameTest(maxTicks = 800)
    public void aCancelledRoutineDispatchesNothing(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        MinecraftServer server = h.getLevel().getServer();
        ServerRuntime runtime = GameTestSupport.runtime(server);
        var owner = GameTestSupport.onlinePlayer(h, "routine-cancel", 3, 2, 3, true);
        CompanionEntity c = companion(h, owner.id());
        var driver = runtime.sessions().routines();
        Caller caller = caller(owner.id());
        h.setBlock(8, 2, 8, Blocks.OAK_LOG);
        BlockPos log = h.absolutePos(new BlockPos(8, 2, 8));
        ToolResult set = driver.set(c, caller, routine("morning", "interval_min", mineAt(log)));
        h.assertFalse(set.error(), set.content());
        Routine routine = driver.state(owner.id()).orElseThrow();
        setClockTo(server, routine);
        driver.tick();
        long planId = driver.state(owner.id()).orElseThrow().lastPlanId();
        h.assertTrue(planId > 0, "the routine queued its plan");

        ToolResult cancelled = driver.cancel(c, caller, nameArgs("morning"));
        h.assertFalse(cancelled.error(), cancelled.content());
        h.assertTrue(driver.state(owner.id()).isEmpty(), "the routine is gone");
        h.assertTrue(
                c.executionCoordinator().queue().plan(planId).isEmpty()
                        || c.executionCoordinator().queue().planStatus(planId) != PlanStatus.ACTIVE,
                "the routine plan is no longer active");
        driver.tick();
        h.assertTrue(
                c.executionCoordinator().queue().plans().stream()
                        .noneMatch(plan -> plan.goal().startsWith("routine:")),
                "a cancelled routine does not dispatch again");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void disabledAutonomyPausesDispatchOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        MinecraftServer server = h.getLevel().getServer();
        ServerRuntime runtime = GameTestSupport.runtime(server);
        var owner = GameTestSupport.onlinePlayer(h, "routine-disabled", 3, 2, 3, true);
        CompanionEntity c = companion(h, owner.id());
        var driver = runtime.sessions().routines();
        Caller caller = caller(owner.id());
        h.setBlock(8, 2, 8, Blocks.OAK_LOG);
        ToolResult set =
                driver.set(c, caller, routine("morning", "interval_min", mineAt(h.absolutePos(new BlockPos(8, 2, 8)))));
        h.assertFalse(set.error(), set.content());
        Routine routine = driver.state(owner.id()).orElseThrow();
        setClockTo(server, routine);

        ServerConfig original = runtime.config();
        ServerConfig disabled = ServerConfig.defaults();
        disabled.autonomy.enabled = false;
        try {
            runtime.applyConfig(disabled);
            driver.tick();
            driver.tick();
            h.assertTrue(
                    c.executionCoordinator().queue().plans().stream()
                            .noneMatch(plan -> plan.goal().startsWith("routine:")),
                    "autonomy off must not dispatch");
            h.assertValueEqual(
                    driver.state(owner.id()).orElseThrow().lastOutcome(), Outcome.NONE, "no occurrence started");
            h.assertValueEqual(countKeys(owner, "friendai.routine.paused"), 1L, "one paused line");
            h.assertValueEqual(countKeys(owner, "friendai.routine.paused"), 0L, "no repeat on another tick");
        } finally {
            runtime.applyConfig(original);
        }
        driver.tick();
        h.assertTrue(
                c.executionCoordinator().queue().plans().stream()
                        .anyMatch(plan -> plan.goal().startsWith("routine:")),
                "re-enabling autonomy dispatches the due routine");
        driver.cancel(c, caller, nameArgs("morning"));
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aMissedWindowIsSkippedInsteadOfReplayed(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        MinecraftServer server = h.getLevel().getServer();
        ServerRuntime runtime = GameTestSupport.runtime(server);
        var owner = GameTestSupport.onlinePlayer(h, "routine-missed", 3, 2, 3, true);
        CompanionEntity c = companion(h, owner.id());
        var driver = runtime.sessions().routines();
        h.setBlock(8, 2, 8, Blocks.OAK_LOG);
        ToolResult set = driver.set(
                c,
                caller(owner.id()),
                routine("morning", "interval_min", mineAt(h.absolutePos(new BlockPos(8, 2, 8)))));
        h.assertFalse(set.error(), set.content());
        Routine routine = driver.state(owner.id()).orElseThrow();
        long now = routine.nextDueTick() + 5 * Routine.TICKS_PER_MINUTE + 1;
        setClock(server, now);
        driver.tick();

        Routine skipped = driver.state(owner.id()).orElseThrow();
        h.assertValueEqual(skipped.lastOutcome(), Outcome.SKIPPED, "a missed interval is skipped");
        h.assertTrue(skipped.nextDueTick() > now, "the next occurrence is in the future");
        h.assertTrue(
                c.executionCoordinator().queue().plans().stream()
                        .noneMatch(plan -> plan.goal().startsWith("routine:")),
                "nothing is replayed");
        driver.cancel(c, caller(owner.id()), nameArgs("morning"));
        h.succeed();
    }

    private static CompanionEntity companion(GameTestHelper h, java.util.UUID owner) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner);
        CompanionManager.get(h.getLevel().getServer()).register(c);
        return c;
    }

    private static Caller caller(java.util.UUID owner) {
        return new Caller(owner, "", AccessLevel.OWNER);
    }

    /** Sleeping through the night moves the day clock; a morning routine must still fire that morning, once. */
    @GameTest(maxTicks = 600)
    public void aMorningRoutineFollowsTheDayClockAcrossSleep(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        MinecraftServer server = h.getLevel().getServer();
        ServerRuntime runtime = GameTestSupport.runtime(server);
        var owner = GameTestSupport.onlinePlayer(h, "routine-sleep", 3, 2, 3, true);
        CompanionEntity c = companion(h, owner.id());
        h.setBlock(6, 2, 2, Blocks.OAK_LOG);
        BlockPos log = h.absolutePos(new BlockPos(6, 2, 2));
        var driver = runtime.sessions().routines();
        ToolResult set = driver.set(c, caller(owner.id()), routine("sleepy", "morning", mineAt(log)));
        h.assertFalse(set.error(), set.content());
        Routine created = driver.state(owner.id()).orElseThrow();
        long dueDay = created.nextDueDay();
        h.assertTrue(dueDay > 0, "a phase routine carries the day it is next due: " + created);

        // Sleep: the day clock jumps to the morning while the game clock stays where it was.
        setGameTime(server, created.nextDueTick());
        setDayTime(server, dueDay * Routine.DAY_TICKS);
        driver.tick();
        Routine after = driver.state(owner.id()).orElseThrow();
        h.assertValueEqual(after.nextDueDay(), dueDay + 1, "the next occurrence is the following day: " + after);
        h.assertTrue(after.lastOutcome() == Outcome.RUNNING, "the morning occurrence was not dispatched: " + after);
        int plans = c.executionCoordinator().queue().planCount();

        // The same morning is not dispatched twice.
        driver.tick();
        h.assertValueEqual(
                c.executionCoordinator().queue().planCount(), plans, "the same morning fired twice: " + plans);
        h.succeed();
    }

    private static String mineAt(BlockPos pos) {
        return "{\"tool\":\"mine_block_at\",\"args\":\"{\\\"x\\\":" + pos.getX()
                + ",\\\"y\\\":" + pos.getY() + ",\\\"z\\\":" + pos.getZ()
                + ",\\\"vein\\\":false}\"}";
    }

    private static JsonObject nameArgs(String name) {
        JsonObject args = new JsonObject();
        args.addProperty("name", name);
        return args;
    }

    /** @param trigger "morning" or "interval_min" */
    private static JsonObject routine(String name, String trigger, String... steps) {
        JsonObject args = new JsonObject();
        args.addProperty("name", name);
        if ("interval_min".equals(trigger)) {
            args.addProperty("interval_min", 5);
        } else {
            args.addProperty("phase", trigger);
        }
        JsonArray rows = new JsonArray();
        for (String step : steps) {
            rows.add(JsonParser.parseString(step));
        }
        args.add("steps", rows);
        return args;
    }

    private static void setClock(MinecraftServer server, long ticks) {
        setGameTime(server, ticks);
        setDayTime(server, ticks);
    }

    /** The game clock only: sleep and `/time set` do not move it the way the day clock moves. */
    private static void setGameTime(MinecraftServer server, long ticks) {
        ServerLevel level = server.overworld();
        ((ServerLevelData) level.getLevelData()).setGameTime(ticks);
    }

    /** Puts both clocks on the routine's next due moment: a phase needs its day and window, an interval its tick. */
    private static void setClockTo(MinecraftServer server, Routine routine) {
        setGameTime(server, routine.nextDueTick());
        if (routine.trigger() instanceof Routine.PhaseTrigger phase) {
            setDayTime(
                    server,
                    routine.nextDueDay() * Routine.DAY_TICKS + phase.phase().start());
        } else {
            setDayTime(server, routine.nextDueTick());
        }
    }

    /** The day clock; it can jump while the game clock does not. */
    private static void setDayTime(MinecraftServer server, long ticks) {
        ServerLevel level = server.overworld();
        level.dimensionType()
                .defaultClock()
                .ifPresent(clock -> server.clockManager().setTotalTicks(clock, ticks));
    }

    private static long countReports(GameTestSupport.OnlinePlayer owner) {
        return countKeys(owner, "friendai.routine.done");
    }

    private static long countKeys(GameTestSupport.OnlinePlayer owner, String key) {
        return owner.drainSystemMessages().stream()
                .filter(message -> hasKey(message, key))
                .count();
    }

    private static boolean hasKey(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents translated
                && translated.getKey().equals(key)) {
            return true;
        }
        return component.getSiblings().stream().anyMatch(sibling -> hasKey(sibling, key));
    }
}
