package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.agent.coordination.HelpRequest;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Claims are derived from the other companion's live queue; help requests are bounded and decline cleanly. */
public class CompanionCoordinationTests {
    private static Job candidate(String tool, String item) {
        JsonObject args = new JsonObject();
        if (item != null) args.addProperty("item", item);
        return new Job(
                9_001,
                0,
                tool,
                args,
                0,
                false,
                9_001,
                JobState.PENDING,
                List.of(),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                0,
                List.of(),
                "",
                0);
    }

    @GameTest(maxTicks = 200)
    public void aLiveJobIsClaimedAcrossCompanionsAndHelpCanBeDeclined(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        ServerPlayer a =
                GameTestSupport.onlinePlayer(h, "coord-a", 2, 2, 2, true).player();
        ServerPlayer b =
                GameTestSupport.onlinePlayer(h, "coord-b", 5, 2, 5, true).player();
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(a);
        manager.summon(b);

        var queue =
                manager.find(a.getUUID()).orElseThrow().executionCoordinator().queue();
        queue.addPlan(
                "wait",
                List.of(new QueuedStep(
                        "wait", new JsonObject(), 0, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of())),
                PlanOptions.DEFAULT);

        var duplicate = runtime.coordination().duplicate(b.getUUID(), candidate("wait", null));
        h.assertTrue(duplicate.isPresent(), "the other companion's live wait claim must be found");
        h.assertValueEqual(duplicate.get().owner(), a.getUUID(), "the claim names its owner");
        h.assertTrue(
                runtime.coordination()
                        .duplicate(b.getUUID(), candidate("mine_blocks", "minecraft:stone"))
                        .isEmpty(),
                "a different tool is not a duplicate");

        long now = runtime.server().getTickCount();
        HelpRequest request = new HelpRequest(b.getUUID(), a.getUUID(), "bring 4 oak logs", now + 100);
        h.assertTrue(runtime.coordination().requestHelp(request), "one request is accepted");
        h.assertFalse(
                runtime.coordination().requestHelp(new HelpRequest(b.getUUID(), a.getUUID(), "again", now + 100)),
                "one open request per pair");
        h.assertValueEqual(runtime.coordination().openFor(a.getUUID(), now).size(), 1, "A sees one request");
        h.assertTrue(runtime.coordination().declineHelp(b.getUUID(), a.getUUID()), "decline removes it");
        h.assertTrue(runtime.coordination().openFor(a.getUUID(), now).isEmpty(), "no action after decline");
        h.succeed();
    }
}
