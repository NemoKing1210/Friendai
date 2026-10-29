package dev.friendai.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.inventory.minecraft.task.DropItemsTask;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskState;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.plan.PlanEntry;
import dev.friendai.task.plan.PlanSave;
import dev.friendai.task.plan.SequenceTask;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class PlanTests {
    private static final class Result implements TaskEventSink {
        TaskStatus status;
        String summary;
        int cancelled;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            cancelled++;
        }

        @Override
        public void onPlanFinished(long planId, String goal, boolean success, String summary) {
            status = success ? TaskStatus.SUCCESS : TaskStatus.FAILED;
            this.summary = summary;
        }
    }

    private static ExecutionCoordinator runner(CompanionEntity c, Result result) {
        ExecutionCoordinator r = new ExecutionCoordinator(c, () -> 300, () -> false);
        r.setEventSink(result);
        return r;
    }

    private static ToolExecutor ownerTools(CompanionEntity companion) {
        companion.setOwner(UUID.randomUUID());
        return GameTestToolRegistry.create(companion, () -> 32).bind(companion);
    }

    private static ToolResult addPlan(ToolExecutor tools, CompanionEntity companion, ToolCall call) {
        return tools.execute(call, new Caller(companion.ownerUuid(), "", AccessLevel.OWNER));
    }

    private static PlanEntry drop(String item, int count) {
        return new PlanEntry(
                "drop_items", c -> new DropItemsTask(ItemMatcher.parse(item).orElseThrow(), count));
    }

    /** A step that never finishes, so the plan's stop and timeout paths can be checked. */
    private static final class NeverEnding implements Task {
        private final boolean hasTimeout;
        boolean stopped;

        NeverEnding(boolean hasTimeout) {
            this.hasTimeout = hasTimeout;
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            return TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext c) {
            stopped = true;
        }

        @Override
        public String describe() {
            return "waiting";
        }

        @Override
        public String resultSummary() {
            return "";
        }

        @Override
        public boolean hasTimeout() {
            return hasTimeout;
        }
    }

    /** A step that crashes while ticking. */
    private static final class Throwing implements Task {
        @Override
        public String type() {
            return "fake";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void stop(TaskContext c) {}

        @Override
        public String describe() {
            return "throwing";
        }

        @Override
        public String resultSummary() {
            return "";
        }
    }

    @GameTest
    public void twoInstantStepsSucceedInOrder(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.TUFF, 3));
        c.inventory().addItem(new ItemStack(Items.CALCITE, 2));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(
                new SequenceTask(
                        "clean up", List.of(drop("minecraft:tuff", 3), drop("minecraft:calcite", 2)), () -> 300),
                "run_plan");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.equals(
                            "plan \"clean up\" done: 1) dropped 3 minecraft:tuff; 2) dropped 2 minecraft:calcite"),
                    "summary was " + result.summary);
        });
    }

    @GameTest
    public void failingStepStopsPlan(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.BASALT, 1));
        c.inventory().addItem(new ItemStack(Items.BLACKSTONE, 4));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(
                new SequenceTask(
                        null,
                        List.of(
                                drop("minecraft:basalt", 1),
                                drop("minecraft:smooth_basalt", 1),
                                drop("minecraft:blackstone", 4)),
                        () -> 300),
                "run_plan");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed yet: " + result.status);
            h.assertTrue(
                    result.summary.equals(
                            "plan stopped at step 2/3 (drop_items): I don't have minecraft:smooth_basalt; done before: 1) dropped 1 minecraft:basalt"),
                    "summary was " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.BLACKSTONE) == 4, "step 3 must not run");
        });
    }

    @GameTest
    public void factoryErrorFailsStep(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        PlanEntry broken = new PlanEntry("drop_items", ctx -> {
            throw new IllegalArgumentException("unknown item: x:y");
        });
        runner.start(new SequenceTask(null, List.of(broken), () -> 300), "run_plan");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail");
        h.assertTrue(
                result.summary.equals("plan stopped at step 1/1 (drop_items): unknown item: x:y; done before: nothing"),
                "summary was " + result.summary);
        h.succeed();
    }

    @GameTest
    public void crashingStepIsIsolated(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.ANDESITE, 1));
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        runner.start(
                new SequenceTask(null, List.of(new PlanEntry("fake", ctx -> new Throwing())), () -> 300), "run_plan");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail");
        h.assertTrue(
                result.summary.endsWith("(fake): task crashed: IllegalStateException; done before: nothing"),
                "summary was " + result.summary);
        runner.start(new DropItemsTask(ItemMatcher.parse("minecraft:andesite").orElseThrow(), 1), "drop_items");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.SUCCESS, "the runner must keep working, got " + result.summary);
        h.succeed();
    }

    @GameTest
    public void stopStopsActiveStep(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        NeverEnding fake = new NeverEnding(true);
        runner.start(new SequenceTask(null, List.of(new PlanEntry("fake", ctx -> fake)), () -> 300), "run_plan");
        runner.tick();
        runner.tick();
        runner.stop("test");
        h.assertTrue(fake.stopped, "the active step must be stopped");
        h.assertTrue(result.cancelled == 1, "one cancel event, got " + result.cancelled);
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void stepTimeout(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        NeverEnding fake = new NeverEnding(true);
        runner.start(new SequenceTask("t", List.of(new PlanEntry("fake", ctx -> fake)), () -> 20), "run_plan");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not yet: " + result.status);
            h.assertTrue(
                    result.summary.equals("plan \"t\" stopped at step 1/1 (fake): timed out; done before: nothing"),
                    "summary was " + result.summary);
            h.assertTrue(fake.stopped, "the timed-out step must be stopped");
        });
    }

    @GameTest
    public void describeShowsCurrentStep(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        ExecutionCoordinator runner = runner(c, result);
        SequenceTask plan = new SequenceTask(
                "g",
                List.of(
                        new PlanEntry("fake", ctx -> new NeverEnding(true)),
                        new PlanEntry("fake", ctx -> new NeverEnding(true))),
                () -> 300);
        runner.start(plan, "run_plan");
        runner.tick();
        h.assertTrue(plan.describe().equals("plan \"g\" step 1/2: waiting"), "describe was " + plan.describe());
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void runPlanEndToEnd(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.MUD_BRICKS, 2));
        Result result = new Result();
        c.executionCoordinator().setEventSink(result);
        ToolExecutor tools = ownerTools(c);
        String json =
                "{\"goal\":\"drop and look\",\"steps\":[{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:mud_bricks\\\",\\\"count\\\":2}\"},"
                        + "{\"tool\":\"pickup_items\",\"args\":\"{\\\"radius\\\":3}\"}]}";
        ToolResult started = addPlan(
                tools,
                c,
                new ToolCall("1", "queue_add", JsonParser.parseString(json).getAsJsonObject(), json, null));
        h.assertTrue(!started.error(), "start failed: " + started.content());
        h.succeedWhen(() -> {
            c.executionCoordinator().tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void planSurvivesReload(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        c.setOwner(owner);
        c.inventory().addItem(new ItemStack(Items.MUD_BRICKS, 2));
        Result result = new Result();
        c.executionCoordinator().setEventSink(result);
        ToolExecutor tools = GameTestToolRegistry.create(c, () -> 32).bind(c);
        String json = "{\"goal\":\"drop twice\",\"steps\":["
                + "{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:mud_bricks\\\",\\\"count\\\":1}\"},"
                + "{\"tool\":\"drop_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:mud_bricks\\\",\\\"count\\\":1}\"}]}";
        JsonObject dropArgs = JsonParser.parseString("{\"item\":\"minecraft:mud_bricks\",\"count\":1}")
                .getAsJsonObject();
        c.executionCoordinator()
                .start(
                        new SequenceTask(
                                "drop twice",
                                List.of(
                                        new PlanEntry(
                                                "drop_items",
                                                dropArgs,
                                                owner,
                                                ctx -> new DropItemsTask(
                                                        ItemMatcher.parse("minecraft:mud_bricks")
                                                                .orElseThrow(),
                                                        1)),
                                        new PlanEntry(
                                                "drop_items",
                                                dropArgs,
                                                owner,
                                                ctx -> new DropItemsTask(
                                                        ItemMatcher.parse("minecraft:mud_bricks")
                                                                .orElseThrow(),
                                                        1))),
                                () -> 300),
                        "run_plan");
        String[] saved = {null};
        h.succeedWhen(() -> {
            c.executionCoordinator().tick();
            if (saved[0] == null && c.inventory().countItem(Items.MUD_BRICKS) == 1) {
                // The first step is done: take the plan's state as the reload would.
                saved[0] = c.executionCoordinator().current().orElseThrow().saveData();
                h.assertTrue(saved[0] != null && saved[0].startsWith("{"), "saved was " + saved[0]);
                Task restored = GameTestSupport.runtime(h.getLevel().getServer())
                        .sessions()
                        .taskRestoreRegistry()
                        .restore(new TaskState("run_plan", saved[0]), c)
                        .orElseThrow();
                h.assertTrue(saved[0].equals(restored.saveData()), "the plan must survive the round trip");
                c.executionCoordinator().start(restored, "run_plan");
            }
            h.assertTrue(saved[0] != null, "the first step never finished");
            h.assertTrue(
                    result.status == TaskStatus.SUCCESS,
                    "restored plan did not finish: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.MUD_BRICKS), 0, "mud bricks left");
        });
    }

    @GameTest
    public void restoredPlanRequiresItsSavedActor(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(UUID.randomUUID());
        String saved = PlanSave.encode(
                "missing actor", 0, List.of(), null, List.of(new PlanSave.Step("drop_items", new JsonObject())));

        boolean restored = GameTestSupport.runtime(h.getLevel().getServer())
                .sessions()
                .taskRestoreRegistry()
                .restore(new TaskState("run_plan", saved), c)
                .isPresent();
        h.assertTrue(!restored, "a plan without its saved actor must not resume");
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void planSmeltThenCraft(GameTestHelper h) {
        // A blast furnace on this deck keeps the scene invisible to the furnace tests that share the height.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(x, 240, z, Blocks.STONE);
                for (int y = 241; y <= 245; y++) {
                    h.setBlock(x, y, z, Blocks.AIR);
                }
            }
        }
        h.setBlock(4, 241, 2, Blocks.BLAST_FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 241, 2);
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(4, 241, 2))),
                "blast furnace should be directly visible");
        c.inventory().addItem(new ItemStack(Items.RAW_IRON));
        c.inventory().addItem(new ItemStack(Items.COAL));
        Result result = new Result();
        c.executionCoordinator().setEventSink(result);
        ToolExecutor tools = ownerTools(c);
        String json = "{\"goal\":\"smelt and craft\",\"steps\":["
                + "{\"tool\":\"smelt_items\",\"args\":\"{\\\"item\\\":\\\"minecraft:raw_iron\\\","
                + "\\\"count\\\":1,\\\"station\\\":\\\"blast_furnace\\\",\\\"wait\\\":true}\"},"
                + "{\"tool\":\"craft_item\",\"args\":\"{\\\"item\\\":\\\"minecraft:iron_nugget\\\","
                + "\\\"count\\\":9}\"}]}";
        ToolResult started = addPlan(
                tools,
                c,
                new ToolCall("1", "queue_add", JsonParser.parseString(json).getAsJsonObject(), json, null));
        h.assertTrue(!started.error(), "start failed: " + started.content());
        h.succeedWhen(() -> {
            c.executionCoordinator().tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.IRON_NUGGET), 9, "iron nuggets");
        });
    }

    @GameTest(maxTicks = 800)
    public void planTakeThenPlace(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(8, 2, 2, Blocks.CHEST);
        Container chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(8, 2, 2)));
        chest.setItem(0, new ItemStack(Items.CRAFTING_TABLE, 1));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        c.executionCoordinator().setEventSink(result);
        ToolExecutor tools = ownerTools(c);
        BlockPos chestPos = h.absolutePos(new BlockPos(8, 2, 2));

        JsonObject takeArgs = new JsonObject();
        takeArgs.addProperty("item", "minecraft:crafting_table");
        takeArgs.addProperty("count", 1);
        takeArgs.addProperty("x", chestPos.getX());
        takeArgs.addProperty("y", chestPos.getY());
        takeArgs.addProperty("z", chestPos.getZ());
        JsonObject take = new JsonObject();
        take.addProperty("tool", "take_items");
        take.addProperty("args", takeArgs.toString());

        JsonObject placeArgs = new JsonObject();
        placeArgs.addProperty("block", "minecraft:crafting_table");
        JsonObject place = new JsonObject();
        place.addProperty("tool", "place_block");
        place.addProperty("args", placeArgs.toString());

        JsonArray steps = new JsonArray();
        steps.add(take);
        steps.add(place);
        JsonObject json = new JsonObject();
        json.addProperty("goal", "set up a table");
        json.add("steps", steps);
        ToolResult started = addPlan(tools, c, new ToolCall("1", "queue_add", json, json.toString(), null));
        h.assertTrue(!started.error(), "start failed: " + started.content());
        h.succeedWhen(() -> {
            c.executionCoordinator().tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            boolean found = false;
            for (int dx = -4; dx <= 4 && !found; dx++) {
                for (int dz = -4; dz <= 4 && !found; dz++) {
                    for (int dy = -1; dy <= 1 && !found; dy++) {
                        if (h.getLevel()
                                .getBlockState(c.blockPosition().offset(dx, dy, dz))
                                .is(Blocks.CRAFTING_TABLE)) {
                            found = true;
                        }
                    }
                }
            }
            h.assertTrue(found, "no crafting table near " + c.blockPosition());
        });
    }

    @GameTest(maxTicks = 800)
    public void planTakeLogsThenCraft(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(11, 2, 11, Blocks.CHEST);
        Container chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(11, 2, 11)));
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 2));
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        c.executionCoordinator().setEventSink(result);
        ToolExecutor tools = ownerTools(c);
        BlockPos chestPos = h.absolutePos(new BlockPos(11, 2, 11));

        JsonObject takeArgs = new JsonObject();
        takeArgs.addProperty("item", "minecraft:oak_log");
        takeArgs.addProperty("count", 2);
        takeArgs.addProperty("x", chestPos.getX());
        takeArgs.addProperty("y", chestPos.getY());
        takeArgs.addProperty("z", chestPos.getZ());
        JsonObject take = new JsonObject();
        take.addProperty("tool", "take_items");
        take.addProperty("args", takeArgs.toString());

        JsonObject craftArgs = new JsonObject();
        craftArgs.addProperty("item", "minecraft:oak_planks");
        craftArgs.addProperty("count", 8);
        JsonObject craft = new JsonObject();
        craft.addProperty("tool", "craft_item");
        craft.addProperty("args", craftArgs.toString());

        JsonArray steps = new JsonArray();
        steps.add(take);
        steps.add(craft);
        JsonObject json = new JsonObject();
        json.addProperty("goal", "turn logs into planks");
        json.add("steps", steps);
        ToolResult started = addPlan(tools, c, new ToolCall("1", "queue_add", json, json.toString(), null));
        h.assertTrue(!started.error(), "start failed: " + started.content());
        h.succeedWhen(() -> {
            c.executionCoordinator().tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(0, c.inventory().countItem(Items.OAK_LOG), "logs left");
            h.assertValueEqual(8, c.inventory().countItem(Items.OAK_PLANKS), "planks");
        });
    }

    @GameTest(maxTicks = 1600)
    public void planShearThenStore(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.spawn(EntityTypes.SHEEP, 4, 2, 4);
        h.setBlock(10, 2, 10, Blocks.CHEST);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.SHEARS));
        Container chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(10, 2, 10)));
        Result result = new Result();
        c.executionCoordinator().setEventSink(result);
        ToolExecutor tools = ownerTools(c);
        BlockPos chestPos = h.absolutePos(new BlockPos(10, 2, 10));

        JsonObject shearArgs = new JsonObject();
        shearArgs.addProperty("target", "minecraft:sheep");
        shearArgs.addProperty("count", 1);
        JsonObject shear = new JsonObject();
        shear.addProperty("tool", "shear");
        shear.addProperty("args", shearArgs.toString());

        JsonObject storeArgs = new JsonObject();
        storeArgs.addProperty("item", "#minecraft:wool");
        storeArgs.addProperty("count", 64);
        storeArgs.addProperty("x", chestPos.getX());
        storeArgs.addProperty("y", chestPos.getY());
        storeArgs.addProperty("z", chestPos.getZ());
        JsonObject store = new JsonObject();
        store.addProperty("tool", "store_items");
        store.addProperty("args", storeArgs.toString());

        JsonArray steps = new JsonArray();
        steps.add(shear);
        steps.add(store);
        JsonObject json = new JsonObject();
        json.addProperty("goal", "shear and store");
        json.add("steps", steps);
        ToolResult started = addPlan(tools, c, new ToolCall("1", "queue_add", json, json.toString(), null));
        h.assertTrue(!started.error(), "start failed: " + started.content());
        h.succeedWhen(() -> {
            c.executionCoordinator().tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            int wool = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (chest.getItem(slot).is(ItemTags.WOOL)) {
                    wool += chest.getItem(slot).getCount();
                }
            }
            h.assertTrue(wool > 0, "wool not stored in the chest");
        });
    }
}
