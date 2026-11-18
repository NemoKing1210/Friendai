package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.agent.coordination.HandoffRequest;
import dev.friendai.agent.coordination.HelpRequest;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanFingerprint;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import dev.friendai.server.agent.coordination.ServerCoordination;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
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

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
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
    public void aPlanPatchCanTargetARepeatBody(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var owner =
                GameTestSupport.onlinePlayer(h, "repeat-owner", 2, 2, 2, true).player();
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(owner);
        var companion = manager.find(owner.getUUID()).orElseThrow();
        var one = new QueuedStep(
                "wait", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        var repeat = new PlanNode.Repeat(List.of(new PlanNode.Action(one)), new Guard.OwnerOnline(), 2);
        long planId = companion
                .executionCoordinator()
                .queue()
                .addStructuredPlan("repeat plan", new PlanDefinition(List.of(repeat)), PlanOptions.DEFAULT);
        JsonObject args = new JsonObject();
        args.addProperty("plan", planId);
        args.addProperty(
                "expected", PlanFingerprint.of(companion.executionCoordinator().queue(), planId));
        args.addProperty("route", "0,3");
        args.addProperty("index", 0);
        args.addProperty("remove", 1);
        com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
        JsonObject replacement = new JsonObject();
        replacement.addProperty("tool", "wait");
        replacement.addProperty("args", "{\"seconds\":3}");
        steps.add(replacement);
        args.add("steps", steps);
        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        new dev.friendai.agent.llm.model.ToolCall("test", "plan_patch", args, args.toString(), null),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertFalse(result.error(), result.content());
        var patched = (PlanNode.Repeat) companion
                .executionCoordinator()
                .queue()
                .plan(planId)
                .orElseThrow()
                .definition()
                .nodes()
                .getFirst();
        h.assertValueEqual(
                ((PlanNode.Action) patched.body().getFirst())
                        .step()
                        .args()
                        .get("seconds")
                        .getAsInt(),
                3,
                "the repeat body was patched");
        h.assertValueEqual(patched.maxIterations(), 2, "the repeat cap is preserved");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aPlanPatchCanTargetAConditionalBranch(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var owner =
                GameTestSupport.onlinePlayer(h, "branch-owner", 2, 2, 2, true).player();
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(owner);
        var companion = manager.find(owner.getUUID()).orElseThrow();
        var one = new QueuedStep(
                "wait", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        var two = new QueuedStep(
                "wait", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        var conditional = new PlanNode.Conditional(
                new Guard.OwnerOnline(), List.of(new PlanNode.Action(one)), List.of(new PlanNode.Action(two)));
        long planId = companion
                .executionCoordinator()
                .queue()
                .addStructuredPlan("branch plan", new PlanDefinition(List.of(conditional)), PlanOptions.DEFAULT);
        JsonObject args = new JsonObject();
        args.addProperty("plan", planId);
        args.addProperty(
                "expected", PlanFingerprint.of(companion.executionCoordinator().queue(), planId));
        args.addProperty("path", "step 1 → then #1");
        args.addProperty("remove", 1);
        com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
        JsonObject replacement = new JsonObject();
        replacement.addProperty("tool", "wait");
        replacement.addProperty("args", "{\"seconds\":2}");
        steps.add(replacement);
        args.add("steps", steps);
        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        new dev.friendai.agent.llm.model.ToolCall("test", "plan_patch", args, args.toString(), null),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertFalse(result.error(), result.content());
        var patched = (PlanNode.Conditional) companion
                .executionCoordinator()
                .queue()
                .plan(planId)
                .orElseThrow()
                .definition()
                .nodes()
                .getFirst();
        h.assertValueEqual(
                ((PlanNode.Action) patched.thenNodes().getFirst())
                        .step()
                        .args()
                        .get("seconds")
                        .getAsInt(),
                2,
                "the then branch was patched");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aPlanPatchNamesAnUnstartedStructuredPlan(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var owner =
                GameTestSupport.onlinePlayer(h, "patch-owner", 2, 2, 2, true).player();
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(owner);
        var companion = manager.find(owner.getUUID()).orElseThrow();
        var step = new QueuedStep(
                "wait", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long planId = companion
                .executionCoordinator()
                .queue()
                .addStructuredPlan(
                        "patch plan", new PlanDefinition(List.of(new PlanNode.Action(step))), PlanOptions.DEFAULT);
        JsonObject args = new JsonObject();
        args.addProperty("plan", planId);
        args.addProperty(
                "expected", PlanFingerprint.of(companion.executionCoordinator().queue(), planId));
        args.addProperty("route", "");
        args.addProperty("index", 0);
        args.addProperty("remove", 1);
        com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
        JsonObject replacement = new JsonObject();
        replacement.addProperty("tool", "wait");
        replacement.addProperty("args", "{\"seconds\":1}");
        steps.add(replacement);
        args.add("steps", steps);
        var result = GameTestToolRegistry.create(companion, () -> 16, MemoryAccess.DISABLED)
                .bind(companion)
                .execute(
                        new dev.friendai.agent.llm.model.ToolCall("test", "plan_patch", args, args.toString(), null),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
        h.assertFalse(result.error(), result.content());
        h.assertTrue(
                companion
                                .executionCoordinator()
                                .queue()
                                .plan(planId)
                                .orElseThrow()
                                .definition()
                        != null,
                "the structured plan keeps its definition");
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

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 80)
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
        h.assertValueEqual(
                runtime.coordination()
                        .openHandoffsFor(b.getUUID(), runtime.coordination().clock())
                        .size(),
                1,
                "one open handoff");

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
        h.assertTrue(
                runtime.coordination()
                        .openHandoffsFor(b.getUUID(), runtime.coordination().clock())
                        .isEmpty(),
                "the offer is consumed");
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

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 60)
    public void handoffFailuresKeepBothInventoriesAndTheOffer(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var runtime = GameTestSupport.runtime(h);
        var a = GameTestSupport.onlinePlayer(h, "transfer-from", 2, 2, 2, true);
        var b = GameTestSupport.onlinePlayer(h, "transfer-to", 5, 2, 2, true);
        var source = runtime.companions().summon(a.player());
        var target = runtime.companions().summon(b.player());
        long now = runtime.coordination().clock();
        var pos = source.blockPosition();
        var logs = ItemMatcher.parse("minecraft:oak_log").orElseThrow();
        var offer = new HandoffRequest(
                a.id(),
                b.id(),
                "minecraft:oak_log",
                6,
                h.getLevel().dimension().identifier().toString(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                now + 100);
        h.assertTrue(runtime.coordination().offerHandoff(offer), "offer is open");

        var accept = new JsonObject();
        accept.addProperty("owner", a.player().getName().getString());
        source.inventory().setItem(0, new ItemStack(Items.OAK_LOG, 4));
        h.assertTrue(call(target, b.player(), "accept_handoff", accept).error(), "short stock refuses");
        h.assertTrue(
                runtime.coordination().openHandoffsFor(b.id(), now).contains(offer), "short stock retains the offer");
        h.assertValueEqual(InventoryOps.count(source.inventory(), logs), 4, "short stock moves nothing");
        h.assertValueEqual(InventoryOps.count(target.inventory(), logs), 0, "target gets nothing on short stock");

        source.inventory().setItem(0, new ItemStack(Items.OAK_LOG, 10));
        for (int slot = 0; slot < target.inventory().getContainerSize(); slot++) {
            target.inventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        h.assertTrue(call(target, b.player(), "accept_handoff", accept).error(), "full inventory refuses");
        h.assertValueEqual(InventoryOps.count(source.inventory(), logs), 10, "full inventory leaves the source alone");
        h.assertValueEqual(InventoryOps.count(target.inventory(), logs), 0, "full inventory gets nothing");

        ItemStack named = new ItemStack(Items.OAK_LOG, 62);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Kept separate"));
        target.inventory().setItem(0, named);
        target.inventory().setItem(1, new ItemStack(Items.OAK_LOG, 61));
        h.assertTrue(
                call(target, b.player(), "accept_handoff", accept).error(),
                "three compatible slots cannot hold six logs");
        h.assertValueEqual(InventoryOps.count(source.inventory(), logs), 10, "partial room moves nothing");
        h.assertValueEqual(InventoryOps.count(target.inventory(), logs), 123, "neither destination stack changes");
        h.assertTrue(
                runtime.coordination().openHandoffsFor(b.id(), now).contains(offer),
                "capacity refusals retain the offer");

        target.inventory().setItem(1, new ItemStack(Items.OAK_LOG, 57));
        h.assertFalse(
                call(target, b.player(), "accept_handoff", accept).error(),
                "freeing enough compatible space allows retry");
        h.assertValueEqual(source.inventory().getItem(0).getCount(), 4, "source paid exactly six");
        h.assertValueEqual(target.inventory().getItem(1).getCount(), 63, "target got exactly six");
        h.assertTrue(ItemStack.matches(target.inventory().getItem(0), named), "named stack stays separate");
        h.assertTrue(call(target, b.player(), "accept_handoff", accept).error(), "successful transfer cannot replay");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 60)
    public void handoffInAnotherDimensionRemainsOpen(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var runtime = GameTestSupport.runtime(h);
        var a = GameTestSupport.onlinePlayer(h, "wrong-from", 2, 2, 2, true);
        var b = GameTestSupport.onlinePlayer(h, "wrong-to", 5, 2, 2, true);
        var source = runtime.companions().summon(a.player());
        var target = runtime.companions().summon(b.player());
        long now = runtime.coordination().clock();
        var pos = source.blockPosition();
        HandoffRequest offer = new HandoffRequest(
                a.id(),
                b.id(),
                "minecraft:oak_log",
                6,
                "minecraft:the_nether",
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                now + 100);
        source.inventory().setItem(0, new ItemStack(Items.OAK_LOG, 10));
        h.assertTrue(runtime.coordination().offerHandoff(offer), "offer is open");
        var accept = new JsonObject();
        accept.addProperty("owner", a.player().getName().getString());
        h.assertTrue(call(target, b.player(), "accept_handoff", accept).error(), "wrong dimension refuses");
        h.assertTrue(
                runtime.coordination().openHandoffsFor(b.id(), now).contains(offer),
                "dimension refusal retains the offer");
        h.assertValueEqual(target.inventory().getItem(0).getCount(), 0, "wrong world transfers nothing");
        h.assertValueEqual(source.inventory().getItem(0).getCount(), 10, "wrong world consumes nothing");
        runtime.coordination().declineHandoff(a.id(), b.id());
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 60)
    public void onlyTheRecipientCanActOnTheExactVisibleRequest(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var runtime = GameTestSupport.runtime(h);
        var a = GameTestSupport.onlinePlayer(h, "notice-a", 2, 2, 2, true);
        var b = GameTestSupport.onlinePlayer(h, "notice-b", 5, 2, 2, true);
        var unrelated = GameTestSupport.onlinePlayer(h, "notice-c", 8, 2, 2, true);
        long now = runtime.coordination().clock();
        HelpRequest first = new HelpRequest(a.id(), b.id(), "bring four logs", now + 100);
        h.assertTrue(runtime.coordination().requestHelp(first), "one open help request");
        var notice = notice(b, "friendai.coordination.help");
        h.assertTrue(notice != null, "the recipient gets a help notice");
        h.assertTrue(notice(a, "friendai.coordination.help") == null, "the notice is not addressed to the sender");
        h.assertTrue(notice(unrelated, "friendai.coordination.help") == null, "the notice is private");
        String accept = action(notice, "accept");
        String decline = action(notice, "decline");
        command(unrelated.player(), accept);
        h.assertValueEqual(
                runtime.coordination().openFor(b.id(), now), List.of(first), "unrelated owners cannot accept");
        command(b.player(), "friendai coordination");
        h.assertTrue(notice(b, "friendai.coordination.help") != null, "listing shows the live request again");
        command(b.player(), decline);
        h.assertTrue(runtime.coordination().openFor(b.id(), now).isEmpty(), "recipient declines the request");
        HelpRequest second = new HelpRequest(a.id(), b.id(), "bring two planks", now + 100);
        h.assertTrue(runtime.coordination().requestHelp(second), "replacement opens");
        var replacement = notice(b, "friendai.coordination.help");
        h.assertTrue(replacement != null, "replacement has its own notice");
        command(b.player(), accept);
        h.assertValueEqual(
                runtime.coordination().openFor(b.id(), now),
                List.of(second),
                "old actions cannot accept a replacement");
        command(b.player(), action(replacement, "accept"));
        h.assertTrue(runtime.coordination().openFor(b.id(), now).isEmpty(), "recipient accepts the new request");
        h.assertTrue(notice(a, "friendai.coordination.help_accepted") != null, "source sees the decision");
        h.assertTrue(
                runtime.coordination().requestHelp(new HelpRequest(b.id(), a.id(), "bring string", now + 100)),
                "the other owner can ask in turn");
        var reverse = notice(a, "friendai.coordination.help");
        h.assertTrue(reverse != null, "either owner can discover a request");
        command(a.player(), action(reverse, "accept"));
        h.assertTrue(runtime.coordination().openFor(a.id(), now).isEmpty(), "the other owner can accept as recipient");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 60)
    public void liveHandoffNoticesAllowAnExplicitDecisionButNeverReplay(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var runtime = GameTestSupport.runtime(h);
        var a = GameTestSupport.onlinePlayer(h, "decide-from", 2, 2, 2, true);
        var b = GameTestSupport.onlinePlayer(h, "decide-to", 5, 2, 2, true);
        var source = runtime.companions().summon(a.player());
        var target = runtime.companions().summon(b.player());
        source.inventory().setItem(0, new ItemStack(Items.OAK_LOG, 10));
        long now = runtime.coordination().clock();
        var expiring = offer(a, b, source, now + 1);
        h.assertTrue(runtime.coordination().offerHandoff(expiring), "one short handoff");
        var expiredNotice = notice(b, "friendai.coordination.handoff");
        h.assertTrue(expiredNotice != null, "recipient discovers the offer and meeting");
        h.assertTrue(notice(a, "friendai.coordination.handoff") == null, "creation notice is for the recipient");

        h.runAfterDelay(2, () -> {
            command(b.player(), action(expiredNotice, "accept"));
            h.assertValueEqual(source.inventory().getItem(0).getCount(), 10, "expired offer cannot take the logs");
            h.assertTrue(target.inventory().isEmpty(), "expired offer cannot deliver logs");

            long currentTick = runtime.coordination().clock();
            var declined = offer(a, b, source, currentTick + 100);
            h.assertTrue(runtime.coordination().offerHandoff(declined), "expired offer leaves room for a new one");
            var declineNotice = notice(b, "friendai.coordination.handoff");
            h.assertTrue(declineNotice != null, "new offer arrives");
            command(b.player(), action(declineNotice, "decline"));
            h.assertTrue(
                    runtime.coordination().openHandoffsFor(b.id(), currentTick).isEmpty(), "decline consumes it");
            h.assertTrue(target.inventory().isEmpty(), "decline moves nothing");

            var live = offer(a, b, source, currentTick + 100);
            h.assertTrue(runtime.coordination().offerHandoff(live), "a later offer can be accepted");
            var liveNotice = notice(b, "friendai.coordination.handoff");
            h.assertTrue(liveNotice != null, "recipient discovers the latest offer");
            var details = ((TranslatableContents) liveNotice.getContents()).getArgs();
            h.assertValueEqual(details[1], 6, "notice shows the offered count");
            h.assertValueEqual(details[2], "minecraft:oak_log", "notice shows the offered item");
            h.assertValueEqual(details[3], live.dimension(), "notice shows the meeting dimension");
            h.assertValueEqual(details[7], 5L, "notice shows seconds before expiry");
            String accept = action(liveNotice, "accept");
            command(b.player(), accept);
            h.assertValueEqual(target.inventory().getItem(0).getCount(), 6, "explicit acceptance transfers the offer");
            h.assertValueEqual(source.inventory().getItem(0).getCount(), 4, "the sender retains the balance");
            command(b.player(), accept);
            var reloaded = new ServerCoordination(runtime);
            h.assertFalse(
                    reloaded.answer(b.player(), live.id(), true), "a fresh coordination book rejects a live stale id");
            h.assertValueEqual(target.inventory().getItem(0).getCount(), 6, "replay and restart move nothing");
            h.succeed();
        });
    }

    private static HandoffRequest offer(
            GameTestSupport.OnlinePlayer from,
            GameTestSupport.OnlinePlayer to,
            CompanionEntity source,
            long expiresAt) {
        var pos = source.blockPosition();
        return new HandoffRequest(
                from.id(),
                to.id(),
                "minecraft:oak_log",
                6,
                source.level().dimension().identifier().toString(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                expiresAt);
    }

    private static Component notice(GameTestSupport.OnlinePlayer player, String key) {
        // Minecraft batches writes during its world tick. Expose that tick's packets before inspecting this channel.
        player.channel().flushOutbound();
        return player.drainSystemMessages().stream()
                .filter(message -> message.getContents() instanceof TranslatableContents text
                        && text.getKey().equals(key))
                .findFirst()
                .orElse(null);
    }

    private static String action(Component notice, String action) {
        return notice.getSiblings().stream()
                .map(child -> child.getStyle().getClickEvent())
                .filter(ClickEvent.RunCommand.class::isInstance)
                .map(ClickEvent.RunCommand.class::cast)
                .map(ClickEvent.RunCommand::command)
                .filter(command -> command.startsWith("/friendai coordination " + action + " "))
                .findFirst()
                .orElseThrow();
    }

    private static void command(ServerPlayer player, String command) {
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static ToolResult call(CompanionEntity companion, ServerPlayer owner, String tool, JsonObject args) {
        return GameTestToolRegistry.create(companion, () -> 16)
                .bind(companion)
                .execute(
                        new ToolCall("coordination", tool, args, args.toString(), null),
                        new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER));
    }
}
