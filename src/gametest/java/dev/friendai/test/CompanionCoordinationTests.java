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
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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

    @GameTest(maxTicks = 40)
    public void aReplanToolNamesAnExistingPlan(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var owner =
                GameTestSupport.onlinePlayer(h, "replan-owner", 2, 2, 2, true).player();
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(owner);
        var companion = manager.find(owner.getUUID()).orElseThrow();
        long planId = companion
                .executionCoordinator()
                .queue()
                .addPlan(
                        "stalled plan",
                        List.of(new QueuedStep(
                                "wait",
                                new JsonObject(),
                                0,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.ASK,
                                1,
                                List.of())),
                        PlanOptions.DEFAULT)
                .planId();
        JsonObject args = new JsonObject();
        args.addProperty("plan", planId);
        args.addProperty("reason", "missing pickaxe");
        ToolResult result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        new dev.friendai.agent.llm.model.ToolCall("test", "replan_plan", args, args.toString(), null),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertFalse(result.error(), result.content());
        h.assertTrue(result.content().contains("plan #" + planId), result.content());
        h.succeed();
    }

    @GameTest(maxTicks = 80)
    public void aConsentedHandoffMovesTheExactQuantityOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerRuntime runtime = GameTestSupport.runtime(h.getLevel().getServer());
        ServerPlayer a =
                GameTestSupport.onlinePlayer(h, "handoff-a", 2, 2, 2, true).player();
        ServerPlayer b =
                GameTestSupport.onlinePlayer(h, "handoff-b", 5, 2, 2, true).player();
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(a);
        manager.summon(b);
        var source = manager.find(a.getUUID()).orElseThrow();
        var target = manager.find(b.getUUID()).orElseThrow();
        source.inventory().addItem(new ItemStack(Items.OAK_LOG, 10));
        ItemMatcher logs = ItemMatcher.parse("minecraft:oak_log").orElseThrow();

        com.google.gson.JsonObject offer = new com.google.gson.JsonObject();
        offer.addProperty("owner", b.getName().getString());
        offer.addProperty("item", "minecraft:oak_log");
        offer.addProperty("count", 6);
        offer.addProperty("x", source.blockPosition().getX());
        offer.addProperty("y", source.blockPosition().getY());
        offer.addProperty("z", source.blockPosition().getZ());
        ToolResult offered = GameTestToolRegistry.create(source, () -> 16, MemoryAccess.DISABLED)
                .bind(source)
                .execute(
                        new dev.friendai.agent.llm.model.ToolCall(
                                "test", "offer_handoff", offer, offer.toString(), null),
                        new Caller(a.getUUID(), a.getName().getString(), AccessLevel.OWNER));
        h.assertFalse(offered.error(), offered.content());
        h.assertValueEqual(runtime.coordination().openHandoffCount(), 1, "one open handoff");

        com.google.gson.JsonObject accept = new com.google.gson.JsonObject();
        accept.addProperty("owner", a.getName().getString());
        ToolResult accepted = GameTestToolRegistry.create(target, () -> 16, MemoryAccess.DISABLED)
                .bind(target)
                .execute(
                        new dev.friendai.agent.llm.model.ToolCall(
                                "test", "accept_handoff", accept, accept.toString(), null),
                        new Caller(b.getUUID(), b.getName().getString(), AccessLevel.OWNER));
        h.assertFalse(accepted.error(), accepted.content());
        h.assertValueEqual(InventoryOps.count(source.inventory(), logs), 4, "source keeps the rest");
        h.assertValueEqual(InventoryOps.count(target.inventory(), logs), 6, "target receives exactly six");
        h.assertValueEqual(runtime.coordination().openHandoffCount(), 0, "the offer is consumed");
        ToolResult retried = GameTestToolRegistry.create(target, () -> 16, MemoryAccess.DISABLED)
                .bind(target)
                .execute(
                        new dev.friendai.agent.llm.model.ToolCall(
                                "test", "accept_handoff", accept, accept.toString(), null),
                        new Caller(b.getUUID(), b.getName().getString(), AccessLevel.OWNER));
        h.assertTrue(retried.error(), "a consumed handoff cannot be replayed");
        h.assertValueEqual(InventoryOps.count(source.inventory(), logs), 4, "a retry does not move more");
        h.assertValueEqual(InventoryOps.count(target.inventory(), logs), 6, "a retry does not move more");
        h.succeed();
    }
}
