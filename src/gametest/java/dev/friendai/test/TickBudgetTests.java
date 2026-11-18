package dev.friendai.test;

import dev.friendai.agent.perception.PerceptionSnapshot;
import dev.friendai.entity.CompanionManager;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.server.agent.perception.PerceptionBuilder;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.net.DebugStream;
import dev.friendai.server.net.Operators;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

/** The per-tick bounds on menus, perception and passive observation. */
public final class TickBudgetTests {
    /** Several open menus no longer rebuild every snapshot on the same server tick. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 100)
    public void oneMenuSnapshotPerTickAndEveryViewerIsServed(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        ServerPlayer a =
                GameTestSupport.onlinePlayer(h, "budget-a", 2, 2, 2, true).player();
        ServerPlayer b =
                GameTestSupport.onlinePlayer(h, "budget-b", 5, 2, 5, true).player();
        AtomicInteger pushes = new AtomicInteger();
        DebugStream stream = new DebugStream((snapshot, operator, requestId) -> {
            pushes.incrementAndGet();
            return new byte[0];
        });
        try {
            stream.openMenu(runtime, a, 1);
            stream.openMenu(runtime, b, 1);
            h.assertValueEqual(pushes.get(), 2, "opening a menu sends its first snapshot at once");

            pushes.set(0);
            for (int tick = 0; tick < 9; tick++) stream.tick(runtime);
            h.assertValueEqual(pushes.get(), 0, "a viewer is not refreshed before its ten-tick interval");

            stream.tick(runtime);
            h.assertValueEqual(pushes.get(), 1, "one snapshot per server tick, not one per viewer");

            stream.tick(runtime);
            h.assertValueEqual(pushes.get(), 2, "the rotation serves the other viewer on the next tick");

            for (int tick = 0; tick < 10; tick++) stream.tick(runtime);
            h.assertValueEqual(pushes.get(), 4, "both viewers keep their cadence in rotation");
        } finally {
            stream.closeMenu(runtime, a.getUUID());
            stream.closeMenu(runtime, b.getUUID());
        }
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 100)
    public void deferredOpenUsesLatestIntentAndCurrentAuthority(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        ServerPlayer viewer =
                GameTestSupport.onlinePlayer(h, "deferred-menu", 2, 2, 2, true).player();
        var playerList = runtime.server().getPlayerList();
        List<Long> replies = new ArrayList<>();
        DebugStream stream = new DebugStream((snapshot, operator, requestId) -> {
            h.assertFalse(operator, "the snapshot rechecks the downgraded viewer");
            h.assertTrue(snapshot.newLog().isEmpty(), "no operator log is prepared");
            replies.add(requestId);
            return new byte[0];
        });
        try {
            playerList.op(viewer.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
            h.assertTrue(Operators.isOperator(viewer), "fixture starts as operator");
            runtime.sessions().debug().logOf(viewer.getUUID()).add(LogKind.ERROR, "operator-only fixture log");
            stream.openMenu(runtime, viewer, 21, false);
            stream.closeMenu(runtime, viewer.getUUID());
            stream.openMenu(runtime, viewer, 23, false);
            h.assertTrue(replies.isEmpty(), "the deferred open does no snapshot work immediately");

            playerList.deop(viewer.nameAndId());
            h.assertFalse(Operators.isOperator(viewer), "fixture permission was downgraded");
            stream.tick(runtime);
            h.assertValueEqual(replies, List.of(23L), "only the final open receives its correlated snapshot");

            stream.openMenu(runtime, viewer, 24, false);
            stream.onDisconnect(runtime, viewer);
            for (int tick = 0; tick < 11; tick++) stream.tick(runtime);
            h.assertValueEqual(replies, List.of(23L), "the disconnected viewer receives no deferred snapshot");
            h.assertFalse(
                    runtime.sessions().debug().subscribers().contains(viewer.getUUID()), "disconnect closes the menu");
        } finally {
            playerList.deop(viewer.nameAndId());
            stream.closeMenu(runtime, viewer.getUUID());
        }
        h.succeed();
    }

    /** The menu and the perceive tool share one detailed build inside a tick and rebuild on the next. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 100)
    public void detailedPerceptionIsSharedWithinATickAndRebuiltAfterIt(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.onlinePlayer(h, "perception-owner", 2, 2, 2, true)
                .player();
        var companion = CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        PerceptionContext context =
                new PerceptionContext(runtime.resourceCandidates(), runtime.settings(), runtime.freeTime());
        PerceptionSnapshot first = PerceptionBuilder.detailed(companion, context);
        h.assertTrue(
                PerceptionBuilder.detailed(companion, context) == first,
                "two consumers in one tick must share the detailed build");
        h.runAfterDelay(3, () -> {
            h.assertTrue(
                    PerceptionBuilder.detailed(companion, context) != first,
                    "a later tick must rebuild the detailed perception");
            h.succeed();
        });
    }

    /** The observation scheduler still schedules and completes work now that setup counts against its deadline. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void observationStillMakesProgressWithSetupInsideTheBudget(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner =
                GameTestSupport.onlinePlayer(h, "observe-owner", 2, 2, 2, true).player();
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        h.succeedWhen(() -> {
            var metrics = runtime.observations().lastMetrics();
            if (metrics.tick() > 0
                    && metrics.companions() >= 1
                    && metrics.scheduledRays() > 0
                    && metrics.completedRays() > 0
                    && metrics.elapsedNanos() >= metrics.setupNanos()) {
                h.succeed();
            }
        });
    }
}
