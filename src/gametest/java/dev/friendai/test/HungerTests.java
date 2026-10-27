package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.perception.PerceptionBuilder;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.TaskListener;
import dev.friendai.task.builtin.GetFoodTask;
import dev.friendai.task.builtin.StayTask;
import dev.friendai.task.survival.HungerWatcher;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.QueuedStep;
import dev.friendai.task.interact.ContainerAccess;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.survival.HungerModel;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Hunger on the entity: exhaustion sources, eating, owner throws, starvation, no passive regen, texts and save data. */
public class HungerTests {
    @GameTest(maxTicks = 500)
    public void queueFreezesForFoodAndResumes(GameTestHelper h) {
        GameTestSupport.platformAt(h, 260, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 261, 2);
        c.hunger().load(3, 0.0f, 0.0f);
        c.hungerWatcher().setAutoDelayTicks(0);
        boolean[] foodInterrupted = {false};
        c.taskRunner().setListener(new TaskListener() {
            @Override public void onFinished(int id, String tool, TaskStatus status, String summary) {
                if (tool.equals(HungerWatcher.GET_FOOD_TOOL)) foodInterrupted[0] = true;
            }
            @Override public void onCancelled(int id, String tool, String reason) {}
        });
        c.taskRunner().enqueue(new QueuedStep("stay", new com.google.gson.JsonObject(), null, List.of(),
                GuardAction.WAIT, FailurePolicy.ASK, 1, List.of()), ctx -> new StayTask());
        h.succeedWhen(() -> {
            h.assertTrue(foodInterrupted[0], "the starvation task did not interrupt the queued job");
            h.assertTrue(c.taskRunner().current().map(task -> task.type().equals("stay")).orElse(false),
                    "the queued task did not resume: " + c.taskRunner().current().map(dev.friendai.task.Task::type).orElse("none"));
            h.assertTrue(c.taskRunner().queue().jobCount() == 1, "the queued job was dropped");
            h.assertTrue(c.taskRunner().queue().jobs().getFirst().state() == dev.friendai.queue.JobState.RUNNING,
                    "the queued job did not return to RUNNING");
        });
    }

    private static void assertClose(GameTestHelper h, float actual, float expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-4f, what + " was " + actual + ", expected " + expected);
    }

    /** An item entity thrown by {@code thrower}, as a player drop would be. */
    private static ItemEntity dropOwned(GameTestHelper h, ServerPlayer thrower,
            double x, double y, double z, ItemStack stack) {
        Vec3 p = h.absoluteVec(new Vec3(x, y, z));
        ItemEntity entity = new ItemEntity(h.getLevel(), p.x, p.y, p.z, stack);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setThrower(thrower);
        entity.setNoPickUpDelay();
        h.getLevel().addFreshEntity(entity);
        return entity;
    }

    @GameTest
    public void miningAddsExhaustion(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        TaskContext context = new TaskContext(c, h.getLevel());
        int broken = 0;
        for (int x = 0; x <= 4; x++) {
            for (int z = 1; z <= 3; z += 2) {
                BlockPos target = h.absolutePos(new BlockPos(x, 2, z));
                h.setBlock(x, 2, z, Blocks.SHORT_GRASS);
                BlockBreaker breaker = new BlockBreaker(target, state -> state.is(Blocks.SHORT_GRASS));
                BlockBreaker.State state;
                int guard = 0;
                do {
                    state = breaker.tick(context);
                    h.assertTrue(++guard < 200, "block " + target + " not broken, state " + state);
                } while (state != BlockBreaker.State.BROKEN);
                broken++;
            }
        }
        h.assertTrue(broken == 10, "broke " + broken + " blocks, expected 10");
        assertClose(h, c.hunger().exhaustion(), 0.05f, "exhaustion after 10 blocks");
        h.succeed();
    }

    @GameTest
    public void hitTakenAddsExhaustion(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 1.0f);
        assertClose(h, c.hunger().exhaustion(), 0.1f, "exhaustion after taking a hit");
        h.succeed();
    }

    @GameTest(maxTicks = 240)
    public void starvationFollowsDifficulty(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hunger().load(0, 0.0f, 0.0f);
        c.setHealth(5.0f);
        Difficulty difficulty = h.getLevel().getDifficulty();
        float expected = switch (difficulty) {
            case PEACEFUL, EASY -> 5.0f;
            case NORMAL, HARD -> 3.0f;
        };
        h.runAfterDelay(170, () -> {
            assertClose(h, c.getHealth(), expected, "health after 160 ticks on " + difficulty);
            h.succeed();
        });
    }

    @GameTest(maxTicks = 500)
    public void noPassiveRegenAnyMore(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setHealth(10.0f);
        c.hunger().load(10, 0.0f, 0.0f);
        Difficulty difficulty = h.getLevel().getDifficulty();
        h.runAfterDelay(400, () -> {
            if (difficulty == Difficulty.PEACEFUL) {
                // Peaceful refills the bar and then regenerates like vanilla, so the removed
                // phase-5 timer cannot be told apart from the hunger model here.
                h.succeed();
                return;
            }
            h.assertTrue(c.getHealth() == 10.0f,
                    "health healed without food on " + difficulty + ": " + c.getHealth());
            h.succeed();
        });
    }

    @GameTest
    public void hungerInPerception(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hunger().load(14, 0.0f, 0.0f);
        String text = PerceptionBuilder.brief(c).toPromptText();
        h.assertTrue(text.contains("Health: 20/20\nHunger: 14/20\n"), text);
        // Only the dedicated line: hunger must not leak into the equipment field as well. The Mode line names
        // hunger on purpose (what survival applies), so check that field, not the whole snapshot.
        String equipment = text.lines().filter(line -> line.startsWith("Wearing:")).findFirst().orElseThrow();
        h.assertFalse(equipment.toLowerCase(java.util.Locale.ROOT).contains("hunger"),
                "hunger leaked into the equipment field:\n" + text);
        h.succeed();
    }

    @GameTest
    public void hungerSurvivesSave(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        a.hunger().load(7, 2.5f, 1.5f);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertTrue(b.hunger().food() == 7, "food was " + b.hunger().food());
        assertClose(h, b.hunger().saturation(), 2.5f, "saturation");
        assertClose(h, b.hunger().exhaustion(), 1.5f, "exhaustion");
        h.succeed();
    }

    @GameTest
    public void oldSaveLoadsFull(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        a.hunger().load(3, 0.0f, 0.0f);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);
        CompoundTag tag = out.buildResult();
        h.assertTrue(a.hunger().food() == 3, "the saved companion should be hungry");
        tag.remove("Food");
        tag.remove("Saturation");
        tag.remove("Exhaustion");

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        h.assertTrue(b.hunger().food() == HungerModel.MAX_FOOD, "food was " + b.hunger().food());
        assertClose(h, b.hunger().saturation(), 5.0f, "saturation");
        assertClose(h, b.hunger().exhaustion(), 0.0f, "exhaustion");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void eatingRestoresFoodNotHealth(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hunger().load(10, 0.0f, 0.0f);
        c.inventory().addItem(new ItemStack(Items.BREAD));
        h.runAfterDelay(120, () -> {
            h.assertValueEqual(c.inventory().countItem(Items.BREAD), 0, "bread was not eaten");
            h.assertValueEqual(c.hunger().food(), 15, "food after bread");
            h.assertTrue(c.getHealth() == 20.0f, "eating changed health to " + c.getHealth());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void eatsAtFood14(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hunger().load(14, 0.0f, 0.0f);
        c.inventory().addItem(new ItemStack(Items.BREAD));
        h.succeedWhen(() -> h.assertTrue(c.inventory().countItem(Items.BREAD) == 0,
                "bread not eaten at food 14: food " + c.hunger().food()
                        + ", state " + c.combatController().state()));
    }

    @GameTest(maxTicks = 150)
    public void doesNotEatWhenFull(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hunger().load(18, 0.0f, 0.0f);
        c.inventory().addItem(new ItemStack(Items.BREAD));
        h.runAfterDelay(120, () -> {
            h.assertValueEqual(c.inventory().countItem(Items.BREAD), 1,
                    "bread eaten at food 18 with full health");
            h.assertValueEqual(c.hunger().food(), 18, "food");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void choosesFoodThatFits(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.hunger().load(16, 0.0f, 0.0f);
        // Hurt so the food 16-17 reflex starts eating; only 4 food points fit in the bar.
        c.setHealth(15.0f);
        c.inventory().addItem(new ItemStack(Items.COOKED_BEEF));
        c.inventory().addItem(new ItemStack(Items.CARROT));
        h.runAfterDelay(120, () -> {
            h.assertValueEqual(c.inventory().countItem(Items.CARROT), 0, "the carrot was not eaten");
            h.assertValueEqual(c.inventory().countItem(Items.COOKED_BEEF), 1, "the steak was eaten");
            h.assertValueEqual(c.hunger().food(), 19, "food after the carrot");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void ownerThrownAppleCollected(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 6, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        ItemEntity apple = dropOwned(h, owner, 4.5, 2, 2.5, new ItemStack(Items.APPLE, 2));
        h.succeedWhen(() -> {
            h.assertTrue(c.inventory().countItem(Items.APPLE) == 2,
                    "the owner's apple was not collected: inventory " + c.inventory().countItem(Items.APPLE)
                            + ", on the ground " + apple.getItem() + ", removed " + apple.isRemoved());
            h.assertTrue(apple.isRemoved(), "the collected item entity is still there");
        });
    }

    @GameTest(maxTicks = 100)
    public void strangerThrownItemIgnored(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 6, 2, 2);
        ServerPlayer stranger = GameTestSupport.mockPlayerAt(h, 8, 2, 6);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        ItemEntity apple = dropOwned(h, stranger, 4.5, 2, 2.5, new ItemStack(Items.APPLE, 2));
        h.runAfterDelay(60, () -> {
            h.assertValueEqual(c.inventory().countItem(Items.APPLE), 0, "a stranger's item was collected");
            h.assertTrue(!apple.isRemoved(), "a stranger's item was removed");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 120)
    public void ownerThrowIntoFullInventory(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 6, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.setOwner(owner.getUUID());
        for (int i = 0; i < c.inventory().getContainerSize(); i++) {
            c.inventory().setItem(i, new ItemStack(Items.STONE, 64));
        }
        ItemEntity apples = dropOwned(h, owner, 4.5, 2, 2.5, new ItemStack(Items.APPLE, 64));
        h.runAfterDelay(60, () -> {
            h.assertValueEqual(c.inventory().countItem(Items.APPLE), 0, "a full inventory took apples");
            h.assertTrue(!apples.isRemoved(), "the leftover item entity was removed");
            h.assertValueEqual(apples.getItem().getCount(), 64, "apples left on the ground");
            int total = h.getLevel()
                    .getEntitiesOfClass(ItemEntity.class, new AABB(c.blockPosition()).inflate(6)).stream()
                    .filter(e -> e.getItem().is(Items.APPLE))
                    .mapToInt(e -> e.getItem().getCount()).sum();
            h.assertValueEqual(total, 64, "total apples in the world");
            h.succeed();
        });
    }

    // Getting food: on request (GetFoodTask) and by the hunger watcher when starving.

    /** Records the notices and the outcomes of the get_food runs for the food tests. */
    private static final class FoodListener implements TaskListener {
        private final CompanionEntity companion;
        final List<String> notices = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile TaskStatus status;
        volatile String summary = "";
        volatile int getFoodRuns;
        volatile int getFoodSuccesses;
        volatile int breadAtFinish = -1;

        FoodListener(CompanionEntity companion) {
            this.companion = companion;
        }

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
            events.add(toolName + ":" + status);
            if (toolName.equals("get_food")) {
                getFoodRuns++;
                if (status == TaskStatus.SUCCESS) {
                    getFoodSuccesses++;
                    // The reflex eats at once; the bread is only in the inventory at the moment the task ends.
                    breadAtFinish = companion.inventory().countItem(Items.BREAD);
                }
            }
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            events.add(toolName + ":cancelled:" + reason);
        }

        @Override
        public void onNotice(String text) {
            notices.add(text);
        }
    }

    private static Container containerAt(GameTestHelper h, int x, int y, int z) {
        return ContainerAccess.at(h.getLevel(), h.absolutePos(new BlockPos(x, y, z))).orElseThrow();
    }

    private static int countIn(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @GameTest(maxTicks = 2400)
    public void getFoodCooksFirst(GameTestHelper h) {
        GameTestSupport.platformAt(h, 200, 16);
        h.setBlock(8, 200, 4, Blocks.FURNACE);
        h.setBlock(12, 200, 4, Blocks.CHEST);
        Container chest = containerAt(h, 12, 200, 4);
        chest.setItem(0, new ItemStack(Items.BREAD, 2));
        chest.setChanged();
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 201, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(8, 200, 4))),
                "furnace should be directly visible");
        c.inventory().addItem(new ItemStack(Items.BEEF, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        FoodListener n = new FoodListener(c);
        c.taskRunner().setListener(n);
        c.taskRunner().start(new GetFoodTask(1), "get_food");
        h.succeedWhen(() -> {
            h.assertTrue(n.status == TaskStatus.SUCCESS,
                    "not finished: " + n.status + " " + n.summary + " " + n.events);
            h.assertValueEqual(c.inventory().countItem(Items.COOKED_BEEF), 1, "cooked beef");
            h.assertValueEqual(countIn(chest, Items.BREAD), 2, "the bread must stay in the chest");
        });
    }

    @GameTest(maxTicks = 1200)
    public void getFoodFromChest(GameTestHelper h) {
        GameTestSupport.platformAt(h, 152, 16);
        h.setBlock(8, 152, 8, Blocks.CHEST);
        Container chest = containerAt(h, 8, 152, 8);
        chest.setItem(0, new ItemStack(Items.BREAD, 5));
        chest.setChanged();
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 153, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(8, 152, 8))),
                "bread chest should be directly visible");
        FoodListener n = new FoodListener(c);
        c.taskRunner().setListener(n);
        c.taskRunner().start(new GetFoodTask(2), "get_food");
        h.succeedWhen(() -> {
            h.assertTrue(n.status == TaskStatus.SUCCESS,
                    "not finished: " + n.status + " " + n.summary + " " + n.events);
            h.assertValueEqual(c.inventory().countItem(Items.BREAD), 2, "bread taken");
            h.assertValueEqual(countIn(chest, Items.BREAD), 3, "bread left in the chest");
        });
    }

    @GameTest(maxTicks = 2400)
    public void getFoodBakesBread(GameTestHelper h) {
        GameTestSupport.platformAt(h, 56, 16);
        h.setBlock(7, 56, 4, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 57, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(7, 56, 4))),
                "crafting table should be directly visible");
        c.inventory().addItem(new ItemStack(Items.WHEAT, 3));
        FoodListener n = new FoodListener(c);
        c.taskRunner().setListener(n);
        c.taskRunner().start(new GetFoodTask(1), "get_food");
        h.succeedWhen(() -> {
            h.assertTrue(n.status == TaskStatus.SUCCESS,
                    "not finished: " + n.status + " " + n.summary + " " + n.events);
            h.assertValueEqual(c.inventory().countItem(Items.BREAD), 1, "baked bread");
            h.assertValueEqual(c.inventory().countItem(Items.WHEAT), 0, "wheat used up");
        });
    }

    @GameTest(maxTicks = 3600)
    public void getFoodHuntsAndCooks(GameTestHelper h) {
        GameTestSupport.platformAt(h, 80, 16);
        // Walled: a hurt cow panics and would otherwise run off the platform and die in the fall.
        GameTestSupport.enclose(h, 80, 16, 4);
        h.setBlock(8, 80, 3, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 81, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(8, 80, 3))),
                "furnace should be directly visible");
        h.spawn(EntityTypes.COW, 6, 81, 6);
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        FoodListener n = new FoodListener(c);
        c.taskRunner().setListener(n);
        c.taskRunner().start(new GetFoodTask(1), "get_food");
        h.succeedWhen(() -> {
            h.assertTrue(n.status == TaskStatus.SUCCESS,
                    "not finished: " + n.status + " " + n.summary + " " + n.events);
            h.assertValueEqual(c.inventory().countItem(Items.COOKED_BEEF), 1, "cooked beef from the cow");
        });
    }

    @GameTest(maxTicks = 200)
    public void getFoodNothing(GameTestHelper h) {
        GameTestSupport.platformAt(h, 26, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 27, 2);
        FoodListener n = new FoodListener(c);
        c.taskRunner().setListener(n);
        c.taskRunner().start(new GetFoodTask(1), "get_food");
        h.succeedWhen(() -> {
            h.assertTrue(n.status == TaskStatus.FAILED, "not failed: " + n.status + " " + n.summary);
            h.assertValueEqual(n.summary,
                    "I can't find any food here: no food in chests, no crops, no animals and no way to fish",
                    "summary");
        });
    }

    @GameTest(maxTicks = 200)
    public void hungryNoticeNearOwner(GameTestHelper h) {
        GameTestSupport.platformAt(h, 176, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 7, 177, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 177, 2);
        c.setOwner(owner.getUUID());
        c.hunger().load(6, 0.0f, 0.0f);
        FoodListener n = new FoodListener(c);
        c.taskRunner().setListener(n);
        c.hungerWatcher().setAutoDelayTicks(40);
        h.runAfterDelay(100, () -> {
            long near = n.notices.stream()
                    .filter(text -> text.startsWith("I'm hungry (6/20) and have no food; ") && text.endsWith("blocks away"))
                    .count();
            h.assertValueEqual(near, 1L, "notices " + n.notices);
            h.assertValueEqual(n.notices.size(), 1, "the stage notice must come once: " + n.notices);
            h.succeed();
        });
    }

    @GameTest(maxTicks = 600)
    public void autoQuestWhenStarving(GameTestHelper h) {
        GameTestSupport.platformAt(h, 128, 16);
        h.setBlock(8, 128, 8, Blocks.CHEST);
        Container chest = containerAt(h, 8, 128, 8);
        chest.setItem(0, new ItemStack(Items.BREAD, 4));
        chest.setChanged();
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 129, 2);
        h.assertTrue(GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(8, 128, 8))),
                "bread chest should be directly visible");
        c.hunger().load(3, 0.0f, 0.0f);
        FoodListener n = new FoodListener(c);
        c.taskRunner().setListener(n);
        c.hungerWatcher().setAutoDelayTicks(40);
        h.succeedWhen(() -> {
            h.assertTrue(n.notices.contains("starving: going to get food myself"), "notices " + n.notices);
            h.assertTrue(n.notices.stream().anyMatch(text -> text.startsWith("I'm hungry (3/20) and have no food; ")),
                    "no starving notice: " + n.notices);
            h.assertTrue(n.getFoodSuccesses == 1,
                    "the quest did not succeed: " + n.status + " " + n.summary + " " + n.events);
            h.assertValueEqual(n.breadAtFinish, 2, "bread in the inventory when the quest finished");
        });
    }

    @GameTest(maxTicks = 800)
    public void autoQuestCoolDown(GameTestHelper h) {
        GameTestSupport.platformAt(h, 44, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 45, 2);
        c.hunger().load(3, 0.0f, 0.0f);
        FoodListener n = new FoodListener(c);
        c.taskRunner().setListener(n);
        c.hungerWatcher().setAutoDelayTicks(40);
        c.hungerWatcher().setCoolDownTicks(400);
        int[] failedAt = {-1};
        h.succeedWhen(() -> {
            if (failedAt[0] < 0) {
                if (n.getFoodRuns >= 1 && n.status == TaskStatus.FAILED) {
                    failedAt[0] = c.tickCount;
                }
                h.assertTrue(failedAt[0] >= 0, "waiting for the auto quest to fail: " + n.status + " " + n.summary
                        + " " + n.events + " " + n.notices);
            }
            h.assertTrue(c.tickCount - failedAt[0] >= 200, "waiting 200 ticks after the failure");
            h.assertValueEqual(n.getFoodRuns, 1, "get_food runs after the cool-down started: " + n.events);
            h.assertTrue(c.taskRunner().current().map(task -> !task.type().equals("get_food")).orElse(true),
                    "a new get_food quest is running inside the cool-down");
        });
    }

    @GameTest(maxTicks = 400)
    public void autoQuestNeedsStarvingStage(GameTestHelper h) {
        GameTestSupport.platformAt(h, 230, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 231, 2);
        c.hunger().load(3, 0.0f, 0.0f);
        FoodListener n = new FoodListener(c);
        c.taskRunner().setListener(n);
        c.hungerWatcher().setAutoDelayTicks(40);
        int[] fedAt = {-1};
        h.succeedWhen(() -> {
            if (fedAt[0] < 0) {
                if (n.notices.stream().anyMatch(text -> text.startsWith("I'm hungry (3/20) and have no food; "))) {
                    // Food arrived and was eaten between two watcher samples, so no item is left to be seen.
                    c.hunger().load(5, 0.0f, 0.0f);
                    fedAt[0] = c.tickCount;
                }
                h.assertTrue(fedAt[0] >= 0, "waiting for the starving notice: " + n.notices);
            }
            h.assertTrue(c.tickCount - fedAt[0] >= 120, "waiting out the auto delay and a margin");
            h.assertValueEqual(n.getFoodRuns, 0, "get_food finished after food rose to stage 2: " + n.events);
            h.assertTrue(n.events.stream().noneMatch(event -> event.startsWith("get_food:")),
                    "a get_food task started at food 5: " + n.events);
            h.assertTrue(c.taskRunner().current().map(task -> !task.type().equals("get_food")).orElse(true),
                    "a get_food task is running at food 5");
            h.assertTrue(!n.notices.contains("starving: going to get food myself"),
                    "the auto quest notice came at food 5: " + n.notices);
        });
    }
}
