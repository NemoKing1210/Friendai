package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.BreedTask;
import dev.friendai.task.builtin.FishTask;
import dev.friendai.task.builtin.HoneyTask;
import dev.friendai.task.builtin.LeadTask;
import dev.friendai.task.builtin.MilkTask;
import dev.friendai.task.builtin.ShearTask;
import dev.friendai.task.builtin.TameTask;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AnimalTests {
    private static final class Result implements TaskListener {
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
    }

    private static TaskRunner runner(CompanionEntity c, Result result) {
        TaskRunner runner = new TaskRunner(c, () -> 600, () -> false);
        runner.setListener(result);
        return runner;
    }

    @GameTest(maxTicks = 100)
    public void interactReturnsFilledBucket(GameTestHelper h) {
        GameTestSupport.platformAt(h, 260, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 261, 2);
        Cow cow = h.spawn(EntityTypes.COW, 4, 261, 2);
        InteractionResult result = c.hands().interact(cow, new ItemStack(Items.BUCKET));
        h.assertTrue(result.consumesAction(), "interaction failed: " + result);
        h.assertValueEqual(c.inventory().countItem(Items.MILK_BUCKET), 1, "milk buckets");
        h.assertValueEqual(c.inventory().countItem(Items.BUCKET), 0, "empty buckets");
        Inventory fake = c.hands().player().getInventory();
        int left = 0;
        for (int i = 0; i < fake.getContainerSize(); i++) {
            left += fake.getItem(i).getCount();
        }
        h.assertValueEqual(left, 0, "items left in the fake player's inventory");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void breedsCows(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.spawn(EntityTypes.COW, 5, 2, 4);
        h.spawn(EntityTypes.COW, 7, 2, 4);
        c.inventory().addItem(new ItemStack(Items.WHEAT, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new BreedTask(EntityTypes.COW, 1), "breed_animals");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("fed 1 pairs of minecraft:cow"), "summary was " + result.summary);
            Vec3 at = h.absoluteVec(new Vec3(5, 2, 4));
            boolean baby = h.getLevel().getEntitiesOfClass(Cow.class, new AABB(at, at).inflate(16)).stream()
                    .anyMatch(Cow::isBaby);
            h.assertTrue(baby, "no baby cow");
        });
    }

    @GameTest
    public void breedLimit(GameTestHelper h) {
        GameTestSupport.platformAt(h, 100, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 101, 2);
        for (int i = 0; i < 24; i++) {
            h.spawn(EntityTypes.PIG, 4 + (i % 6), 101, 4 + (i / 6));
        }
        c.inventory().addItem(new ItemStack(Items.CARROT, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new BreedTask(EntityTypes.PIG, 1), "breed_animals");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "there are already 24 minecraft:pig here", "summary");
        h.succeed();
    }

    @GameTest
    public void breedNoFood(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.spawn(EntityTypes.SHEEP, 5, 2, 4);
        h.spawn(EntityTypes.SHEEP, 7, 2, 4);
        c.inventory().addItem(new ItemStack(Items.DIRT, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new BreedTask(EntityTypes.SHEEP, 1), "breed_animals");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "I have no food that minecraft:sheep eats", "summary");
        h.succeed();
    }

    @GameTest
    public void breedNotEnoughAdults(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.spawn(EntityTypes.RABBIT, 5, 2, 4);
        Rabbit baby = h.spawn(EntityTypes.RABBIT, 7, 2, 4);
        baby.setAge(-24000);
        c.inventory().addItem(new ItemStack(Items.DANDELION, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new BreedTask(EntityTypes.RABBIT, 1), "breed_animals");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "not enough adult minecraft:rabbit ready to breed nearby", "summary");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void shearsSheep(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.spawn(EntityTypes.SHEEP, 4, 2, 4);
        h.spawn(EntityTypes.SHEEP, 6, 2, 4);
        c.inventory().addItem(new ItemStack(Items.SHEARS));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new ShearTask(EntityTypes.SHEEP, 2), "shear");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("sheared 2 minecraft:sheep"), "summary was " + result.summary);
            int wool = 0;
            ItemStack shears = ItemStack.EMPTY;
            for (int slot = 0; slot < c.inventory().getContainerSize(); slot++) {
                ItemStack stack = c.inventory().getItem(slot);
                if (stack.is(Items.SHEARS)) {
                    shears = stack;
                }
                if (stack.is(net.minecraft.tags.ItemTags.WOOL)) {
                    wool += stack.getCount();
                }
            }
            h.assertTrue(wool > 0, "no wool collected");
            h.assertValueEqual(shears.getDamageValue(), 2, "shears damage");
        });
    }

    @GameTest
    public void shearWithoutShears(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.spawn(EntityTypes.SHEEP, 4, 2, 4);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new ShearTask(EntityTypes.SHEEP, 1), "shear");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "I have no minecraft:shears", "summary");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void shearTargetGoneMovesOn(GameTestHelper h) {
        GameTestSupport.platformAt(h, 180, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 181, 2);
        Sheep first = h.spawn(EntityTypes.SHEEP, 7, 181, 4);
        h.spawn(EntityTypes.SHEEP, 10, 181, 7);
        c.inventory().addItem(new ItemStack(Items.SHEARS));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new ShearTask(EntityTypes.SHEEP, 2), "shear");
        int[] ticks = {0};
        boolean[] killed = {false};
        h.succeedWhen(() -> {
            ticks[0]++;
            if (!killed[0] && ticks[0] >= 5) {
                killed[0] = true;
                first.kill(h.getLevel());
            }
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("sheared 1 minecraft:sheep"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void milksCow(GameTestHelper h) {
        GameTestSupport.platformAt(h, 220, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 221, 2);
        h.spawn(EntityTypes.COW, 4, 221, 4);
        c.inventory().addItem(new ItemStack(Items.BUCKET, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new MilkTask(2, EntityTypes.COW), "milk");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(result.summary, "filled 2 minecraft:milk_bucket", "summary");
            h.assertValueEqual(c.inventory().countItem(Items.MILK_BUCKET), 2, "milk buckets");
            h.assertValueEqual(c.inventory().countItem(Items.BUCKET), 0, "empty buckets");
        });
    }

    @GameTest
    public void milkWithoutBuckets(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.spawn(EntityTypes.COW, 4, 2, 4);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new MilkTask(1, EntityTypes.COW), "milk");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "I have no minecraft:bucket", "summary");
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void tamesWolfForOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        net.minecraft.server.level.ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        c.setOwner(owner.getUUID());
        Wolf wolf = h.spawn(EntityTypes.WOLF, 5, 2, 4);
        c.inventory().addItem(new ItemStack(Items.BONE, 64));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new TameTask("wolf", 1), "tame_animal");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("tamed 1 minecraft:wolf for "), "summary was " + result.summary);
            h.assertTrue(wolf.isTame(), "wolf not tamed");
            h.assertTrue(wolf.getOwnerReference() != null, "no owner reference");
            java.util.UUID ownerId = wolf.getOwnerReference().getUUID();
            h.assertValueEqual(ownerId, owner.getUUID(), "owner");
            h.assertTrue(!ownerId.equals(c.getUUID()), "the companion must not own the pet");
            h.assertTrue(!ownerId.equals(c.hands().player().getUUID()), "the fake player must not own the pet");
        });
    }

    @GameTest
    public void tameSkipsOthersPets(GameTestHelper h) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(x, 100, z, Blocks.STONE);
                for (int y = 101; y <= 105; y++) {
                    h.setBlock(x, y, z, Blocks.AIR);
                }
            }
        }
        net.minecraft.server.level.ServerPlayer other = GameTestSupport.mockPlayerAt(h, 8, 101, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 101, 2);
        Wolf wolf = h.spawn(EntityTypes.WOLF, 5, 101, 4);
        wolf.setTame(true, true);
        wolf.setOwner(other);
        h.assertTrue(wolf.isTame(), "setup: wolf must be tamed");
        h.assertTrue(
                wolf.getOwnerReference() != null
                        && wolf.getOwnerReference().getUUID().equals(other.getUUID()),
                "setup: wolf owner");
        c.inventory().addItem(new ItemStack(Items.BONE, 4));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new TameTask("wolf", 1), "tame_animal");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "no untamed wolf nearby", "summary");
        h.succeed();
    }

    @GameTest
    public void tameWithoutFood(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        h.spawn(EntityTypes.CAT, 5, 2, 4);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new TameTask("cat", 1), "tame_animal");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "I have no raw cod or salmon to tame a cat", "summary");
        h.succeed();
    }

    @GameTest(maxTicks = 1600)
    public void leadsPigsAndTiesThem(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.taskRunner().setPaused(true);
        Pig first = h.spawn(EntityTypes.PIG, 4, 2, 3);
        Pig second = h.spawn(EntityTypes.PIG, 5, 2, 3);
        h.setBlock(10, 2, 2, Blocks.OAK_FENCE);
        c.inventory().addItem(new ItemStack(Items.LEAD, 2));
        c.inventory().addItem(new ItemStack(Items.BREAD, 4));
        BlockPos destination = h.absolutePos(new BlockPos(10, 2, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new LeadTask(EntityTypes.PIG, 2, destination, true), "lead_animals");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("and tied them to the fence at "), "summary was " + result.summary);
            for (Pig pig : List.of(first, second)) {
                Leashable leashable = (Leashable) pig;
                h.assertTrue(leashable.isLeashed(), "pig not leashed");
                h.assertTrue(
                        leashable.getLeashHolder() instanceof LeashFenceKnotEntity,
                        "holder was " + leashable.getLeashHolder());
            }
            h.assertValueEqual(((Leashable) first).getLeashHolder().blockPosition(), destination, "knot position");
        });
    }

    @GameTest(maxTicks = 1600)
    public void leadLetsGoWithoutTie(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        net.minecraft.world.entity.animal.goat.Goat goat = h.spawn(EntityTypes.GOAT, 4, 2, 4);
        c.inventory().addItem(new ItemStack(Items.LEAD));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new LeadTask(EntityTypes.GOAT, 1, h.absolutePos(new BlockPos(8, 2, 4)), false), "lead_animals");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("and let them go"), "summary was " + result.summary);
            h.assertTrue(!((Leashable) goat).isLeashed(), "goat should be free");
            h.assertValueEqual(c.inventory().countItem(Items.LEAD), 1, "lead returned");
        });
    }

    @GameTest(maxTicks = 1600)
    public void leadLosesAnimal(GameTestHelper h) {
        GameTestSupport.platformAt(h, 180, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 181, 2);
        Pig first = h.spawn(EntityTypes.PIG, 4, 181, 3);
        h.spawn(EntityTypes.PIG, 5, 181, 3);
        h.setBlock(10, 181, 2, Blocks.OAK_FENCE);
        c.inventory().addItem(new ItemStack(Items.LEAD, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new LeadTask(EntityTypes.PIG, 2, h.absolutePos(new BlockPos(10, 181, 2)), true), "lead_animals");
        int[] ticks = {0};
        boolean[] killed = {false};
        h.succeedWhen(() -> {
            ticks[0]++;
            if (!killed[0] && ticks[0] >= 10) {
                killed[0] = true;
                first.kill(h.getLevel());
            }
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("; lost 1 on the way"), "summary was " + result.summary);
        });
    }

    @GameTest
    public void honeyNeedsSmoke(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos hive = new BlockPos(5, 3, 5);
        h.setBlock(hive, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, 5));
        BeehiveBlockEntity blockEntity = (BeehiveBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(hive));
        blockEntity.addOccupant(h.spawn(EntityTypes.BEE, 5, 4, 5));
        c.inventory().addItem(new ItemStack(Items.GLASS_BOTTLE));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new HoneyTask(true, h.absolutePos(hive)), "harvest_honey");
        runner.tick();
        BlockPos at = h.absolutePos(hive);
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(
                result.summary,
                "the hive at " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " isn't smoked; place a campfire under it",
                "summary");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void honeyWithCampfire(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos hive = new BlockPos(5, 3, 5);
        h.setBlock(hive, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, 5));
        BeehiveBlockEntity blockEntity = (BeehiveBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(hive));
        blockEntity.addOccupant(h.spawn(EntityTypes.BEE, 5, 4, 5));
        h.setBlock(5, 1, 5, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        c.inventory().addItem(new ItemStack(Items.GLASS_BOTTLE));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new HoneyTask(true, h.absolutePos(hive)), "harvest_honey");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary.startsWith("got 1 minecraft:honey_bottle from the hive at "),
                    "summary was " + result.summary);
            h.assertValueEqual(c.inventory().countItem(Items.HONEY_BOTTLE), 1, "honey bottle");
            h.assertValueEqual(h.getBlockState(hive).getValue(BeehiveBlock.HONEY_LEVEL), 0, "hive level");
        });
    }

    @GameTest
    public void honeyNotFull(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BlockPos hive = new BlockPos(5, 3, 5);
        h.setBlock(hive, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, 2));
        c.inventory().addItem(new ItemStack(Items.GLASS_BOTTLE));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new HoneyTask(true, h.absolutePos(hive)), "harvest_honey");
        runner.tick();
        BlockPos at = h.absolutePos(hive);
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(
                result.summary,
                "the hive at " + at.getX() + " " + at.getY() + " " + at.getZ() + " isn't full yet",
                "summary");
        h.succeed();
    }

    @GameTest(maxTicks = 600)
    public void fishesTwice(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                h.setBlock(x, 1, z, Blocks.WATER);
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.FISHING_ROD));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new FishTask(2, () -> 20), "fish");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("caught 2: "), "summary was " + result.summary);
            ItemStack rod = ItemStack.EMPTY;
            int total = 0;
            for (int slot = 0; slot < c.inventory().getContainerSize(); slot++) {
                ItemStack stack = c.inventory().getItem(slot);
                total += stack.getCount();
                if (stack.is(Items.FISHING_ROD)) {
                    rod = stack;
                }
            }
            h.assertValueEqual(rod.getDamageValue(), 2, "rod damage");
            h.assertValueEqual(total, 3, "inventory has the rod and two catches");
        });
    }

    @GameTest
    public void fishWithoutRod(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new FishTask(1, () -> 20), "fish");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "I have no minecraft:fishing_rod", "summary");
        h.succeed();
    }

    @GameTest
    public void fishWithoutWater(GameTestHelper h) {
        GameTestSupport.platformAt(h, 140, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 141, 2);
        c.inventory().addItem(new ItemStack(Items.FISHING_ROD));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new FishTask(1, () -> 20), "fish");
        runner.tick();
        h.assertValueEqual(result.status, TaskStatus.FAILED, "status");
        h.assertValueEqual(result.summary, "no open water nearby", "summary");
        h.succeed();
    }

    @GameTest(maxTicks = 900)
    public void breedStopsAfterParentsCoolDown(GameTestHelper h) {
        GameTestSupport.platformAt(h, 240, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 241, 2);
        h.spawn(EntityTypes.COW, 5, 241, 4);
        h.spawn(EntityTypes.COW, 7, 241, 4);
        c.inventory().addItem(new ItemStack(Items.WHEAT, 8));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new BreedTask(EntityTypes.COW, 2), "breed_animals");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("fed 1 pairs of minecraft:cow"), "summary was " + result.summary);
        });
    }
}
