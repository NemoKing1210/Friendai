package dev.friedai.test;

import dev.friedai.crafting.Recipe;
import dev.friedai.crafting.Station;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.cooking.FuelPolicy;
import dev.friedai.task.cooking.FurnaceJob;
import dev.friedai.task.cooking.FurnaceSlots;
import dev.friedai.task.TaskRunner;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.builtin.CraftTask;
import dev.friedai.task.builtin.SmeltTask;
import dev.friedai.task.crafting.RecipeIndex;
import dev.friedai.task.crafting.Stations;
import dev.friedai.task.inventory.ItemMatcher;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class CookingTests {
    /** Records the notices and task outcomes a companion's runner reports. */
    private static final class Result implements dev.friedai.task.TaskListener {
        final List<String> notices = new java.util.ArrayList<>();
        dev.friedai.task.TaskStatus status;
        String summary;

        @Override
        public void onFinished(int id, String toolName, dev.friedai.task.TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}

        @Override
        public void onNotice(String text) {
            notices.add(text);
        }
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnaceAt(
            GameTestHelper h, int x, int y, int z) {
        h.setBlock(x, y, z, Blocks.FURNACE);
        return (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)
                h.getLevel().getBlockEntity(pos(h, x, y, z));
    }

    @GameTest
    public void busyFurnaceIsNotFree(GameTestHelper h) {
        net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace = furnaceAt(h, 2, 2, 2);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON));
        h.assertFalse(
                FurnaceSlots.isFreeFor(furnace, Items.RAW_COPPER, Items.COPPER_INGOT),
                "a furnace with someone's raw iron is busy");

        furnace.setItem(0, ItemStack.EMPTY);
        furnace.setItem(2, new ItemStack(Items.COPPER_INGOT));
        h.assertTrue(
                FurnaceSlots.isFreeFor(furnace, Items.RAW_COPPER, Items.COPPER_INGOT),
                "our own output in the result slot is fine");

        furnace.setItem(2, new ItemStack(Items.STONE));
        h.assertFalse(
                FurnaceSlots.isFreeFor(furnace, Items.RAW_COPPER, Items.COPPER_INGOT),
                "someone else's output makes the furnace busy");
        h.succeed();
    }

    @GameTest
    public void resultSlotAlmostFull(GameTestHelper h) {
        net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace = furnaceAt(h, 2, 2, 2);
        furnace.setItem(2, new ItemStack(Items.COPPER_INGOT, 62));
        int loaded = FurnaceSlots.load(
                furnace, new ItemStack(Items.RAW_COPPER, 5), new ItemStack(Items.COAL));
        h.assertValueEqual(loaded, 2, "only the room left in the result slot");
        h.assertValueEqual(furnace.getItem(0).getCount(), 2, "input loaded");
        h.assertTrue(furnace.getItem(1).is(Items.COAL), "fuel loaded");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void watcherReportsFinished(GameTestHelper h) {
        net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace = furnaceAt(h, 2, 2, 2);
        BlockPos pos = pos(h, 2, 2, 2);
        furnace.setItem(0, new ItemStack(Items.RAW_GOLD));
        furnace.setItem(1, new ItemStack(Items.COAL));
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 4);
        Result result = new Result();
        c.taskRunner().setListener(result);
        c.furnaceJobs().add(new FurnaceJob(pos, Station.FURNACE, "minecraft:raw_gold", "minecraft:gold_ingot", 1, false));
        String expected = "the furnace at " + coords(pos) + " finished: 1 minecraft:gold_ingot ready (use collect_smelted)";
        h.succeedWhen(() -> h.assertTrue(result.notices.contains(expected), "notices: " + result.notices));
    }

    @GameTest(maxTicks = 600)
    public void watcherReportsOutOfFuel(GameTestHelper h) {
        net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace = furnaceAt(h, 2, 2, 2);
        BlockPos pos = pos(h, 2, 2, 2);
        furnace.setItem(0, new ItemStack(Items.KELP, 2));
        furnace.setItem(1, new ItemStack(Items.OAK_PLANKS));
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 4);
        Result result = new Result();
        c.taskRunner().setListener(result);
        c.furnaceJobs().add(new FurnaceJob(pos, Station.FURNACE, "minecraft:kelp", "minecraft:dried_kelp", 2, false));
        String expected = "the furnace at " + coords(pos) + " stopped: out of fuel, 1 minecraft:kelp left";
        h.succeedWhen(() -> h.assertTrue(result.notices.contains(expected), "notices: " + result.notices));
    }

    @GameTest(maxTicks = 100)
    public void watcherReportsGoneFurnace(GameTestHelper h) {
        furnaceAt(h, 2, 2, 2);
        BlockPos pos = pos(h, 2, 2, 2);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 4);
        Result result = new Result();
        c.taskRunner().setListener(result);
        c.furnaceJobs().add(new FurnaceJob(pos, Station.FURNACE, "minecraft:raw_gold", "minecraft:gold_ingot", 1, false));
        h.setBlock(2, 2, 2, Blocks.AIR);
        String expected = "the furnace at " + coords(pos) + " is gone; the job for minecraft:gold_ingot was dropped";
        h.succeedWhen(() -> {
            h.assertTrue(result.notices.contains(expected), "notices: " + result.notices);
            h.assertTrue(c.furnaceJobs().all().isEmpty(), "the job should be dropped: " + c.furnaceJobs().all());
        });
    }

    // Task 5: load-and-leave scenes live on their own decks, 24 blocks apart, so no test sees another's stations.
    private static final int LOAD_DECK = 240;
    private static final int BLAST_DECK = 264;
    private static final int SMOKER_DECK = 288;
    private static final int BUSY_DECK = 312;
    private static final int NOFUEL_DECK = 240;
    private static final int CRAFT_DECK = 264;
    private static final int CAMPFIRE_DECK = 288;
    private static final int WAIT_DECK = 240;
    private static final int TAG_DECK = 264;
    private static final int BROKEN_DECK = 288;
    private static final int COLLECT_DECK = 312;

    private static void deck(GameTestHelper h, int size, int y) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                h.setBlock(x, y, z, Blocks.STONE);
                for (int above = y + 1; above <= y + 5; above++) {
                    h.setBlock(x, above, z, Blocks.AIR);
                }
            }
        }
    }

    private static boolean hasBlock(GameTestHelper h, net.minecraft.world.level.block.Block block, int size, int y) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int scan = y; scan <= y + 3; scan++) {
                    if (h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, scan, z))).is(block)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static TaskRunner smelt(
            CompanionEntity c,
            Result result,
            String item,
            int count,
            Station forced,
            String fuel,
            boolean forceWait,
            int waitLimitTicks) {
        TaskRunner runner = new TaskRunner(c, () -> 300, () -> false);
        runner.setListener(result);
        ItemMatcher input = ItemMatcher.parse(item).orElseThrow();
        ItemMatcher fuelMatcher = fuel == null ? null : ItemMatcher.parse(fuel).orElseThrow();
        runner.start(
                new SmeltTask(input, count, forced, fuelMatcher, forceWait, CraftTask.StationPolicy.AUTO, waitLimitTicks),
                "smelt_items");
        return runner;
    }

    @GameTest(maxTicks = 400)
    public void loadsTwoFurnaces(GameTestHelper h) {
        deck(h, 16, LOAD_DECK);
        h.setBlock(4, LOAD_DECK + 1, 2, Blocks.FURNACE);
        h.setBlock(8, LOAD_DECK + 1, 2, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, LOAD_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.RAW_GOLD, 2));
        c.inventory().addItem(new ItemStack(Items.COAL, 2));
        Result result = new Result();
        TaskRunner runner = smelt(c, result, "minecraft:raw_gold", 2, null, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.startsWith("loaded 2 furnaces with 2 minecraft:raw_gold, ready in about 10 s"),
                    "summary was " + result.summary);
            int loaded = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    var be = h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(x, LOAD_DECK + 1, z)));
                    if (be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace
                            && furnace.getItem(0).is(Items.RAW_GOLD)) {
                        loaded += furnace.getItem(0).getCount();
                    }
                }
            }
            h.assertValueEqual(loaded, 2, "raw gold in the furnaces");
            h.assertValueEqual(c.furnaceJobs().all().size(), 2, "jobs");
        });
    }

    @GameTest(maxTicks = 400)
    public void blastFurnacePreferredForOre(GameTestHelper h) {
        deck(h, 16, BLAST_DECK);
        h.setBlock(4, BLAST_DECK + 1, 2, Blocks.FURNACE);
        h.setBlock(8, BLAST_DECK + 1, 2, Blocks.BLAST_FURNACE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, BLAST_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.RAW_GOLD, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result result = new Result();
        TaskRunner runner = smelt(c, result, "minecraft:raw_gold", 1, null, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("blast furnace"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void smokerPreferredForFood(GameTestHelper h) {
        deck(h, 16, SMOKER_DECK);
        h.setBlock(4, SMOKER_DECK + 1, 2, Blocks.FURNACE);
        h.setBlock(8, SMOKER_DECK + 1, 2, Blocks.SMOKER);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, SMOKER_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.COD, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result result = new Result();
        TaskRunner runner = smelt(c, result, "minecraft:cod", 1, null, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.contains("smoker"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 400)
    public void busyFurnaceSkipped(GameTestHelper h) {
        deck(h, 16, BUSY_DECK);
        h.setBlock(4, BUSY_DECK + 1, 2, Blocks.FURNACE);
        h.setBlock(8, BUSY_DECK + 1, 2, Blocks.FURNACE);
        var busy = (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)
                h.getLevel().getBlockEntity(pos(h, 4, BUSY_DECK + 1, 2));
        busy.setItem(0, new ItemStack(Items.RAW_IRON));
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, BUSY_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        Result result = new Result();
        TaskRunner runner = smelt(c, result, "minecraft:raw_copper", 1, null, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(busy.getItem(0).is(Items.RAW_IRON), "the busy furnace must be untouched");
            h.assertValueEqual(c.furnaceJobs().all().size(), 1, "one job");
        });
    }

    @GameTest
    public void nothingToSmeltFails(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = smelt(c, result, "minecraft:raw_copper", 1, null, null, false, 20);
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
        h.assertValueEqual(result.summary, "I don't have minecraft:raw_copper", "summary");
        h.succeed();
    }

    @GameTest
    public void notSmeltableFails(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.DIRT));
        Result result = new Result();
        TaskRunner runner = smelt(c, result, "minecraft:dirt", 1, null, null, false, 20);
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
        h.assertValueEqual(result.summary, "minecraft:dirt can't be smelted or cooked", "summary");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void noAllowedFuelFails(GameTestHelper h) {
        deck(h, 16, NOFUEL_DECK);
        h.setBlock(4, NOFUEL_DECK + 1, 2, Blocks.FURNACE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, NOFUEL_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 1));
        c.inventory().addItem(new ItemStack(Items.OAK_BOAT));
        Result result = new Result();
        TaskRunner runner = smelt(c, result, "minecraft:raw_copper", 1, null, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertValueEqual(result.summary, "I have no fuel I'm allowed to burn", "summary");
        });
    }

    @GameTest(maxTicks = 1600)
    public void craftsFurnaceWhenNoneAround(GameTestHelper h) {
        deck(h, 16, CRAFT_DECK);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, CRAFT_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.RAW_COPPER, 1));
        c.inventory().addItem(new ItemStack(Items.COAL, 1));
        c.inventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
        c.inventory().addItem(new ItemStack(Items.OAK_LOG, 3));
        Result result = new Result();
        TaskRunner runner = smelt(c, result, "minecraft:raw_copper", 1, null, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(hasBlock(h, Blocks.FURNACE, 16, CRAFT_DECK), "a furnace should have been placed");
            boolean loaded = false;
            for (int x = 0; x < 16 && !loaded; x++) {
                for (int z = 0; z < 16 && !loaded; z++) {
                    var be = h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(x, CRAFT_DECK + 1, z)));
                    loaded = be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace
                            && furnace.getItem(0).is(Items.RAW_COPPER);
                }
            }
            h.assertTrue(loaded, "the raw copper should be in the placed furnace");
        });
    }

    @GameTest(maxTicks = 400)
    public void longCampfireJobRefused(GameTestHelper h) {
        deck(h, 16, CAMPFIRE_DECK);
        h.setBlock(6, CAMPFIRE_DECK + 1, 2, Blocks.CAMPFIRE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, CAMPFIRE_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.PORKCHOP, 5));
        Result result = new Result();
        TaskRunner runner = smelt(c, result, "minecraft:porkchop", 5, Station.CAMPFIRE, null, false, 20);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertValueEqual(result.summary,
                    "that would take too long on a campfire; ask me to wait or give me a furnace", "summary");
        });
    }

    private static List<Recipe> cookingFrom(GameTestHelper h, String item) {
        return RecipeIndex.of(h.getLevel()).cookingFrom(item);
    }

    private static Recipe station(GameTestHelper h, String input, Station station) {
        return cookingFrom(h, input).stream()
                .filter(recipe -> recipe.station() == station)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + station + " recipe for " + input + ": "
                        + cookingFrom(h, input)));
    }

    private static BlockPos pos(GameTestHelper h, int x, int y, int z) {
        return h.absolutePos(new BlockPos(x, y, z));
    }

    @GameTest
    public void indexHasCookingRecipes(GameTestHelper h) {
        Recipe furnace = station(h, "minecraft:raw_iron", Station.FURNACE);
        h.assertValueEqual(furnace.output(), "minecraft:iron_ingot", "furnace output");
        h.assertValueEqual(furnace.cookTicks(), 200, "furnace cook time");
        h.assertValueEqual(furnace.ingredients().size(), 1, "furnace ingredient count");

        Recipe blasting = station(h, "minecraft:raw_iron", Station.BLAST_FURNACE);
        h.assertValueEqual(blasting.output(), "minecraft:iron_ingot", "blast furnace output");
        h.assertValueEqual(blasting.cookTicks(), 200, "blast furnace cook time");

        station(h, "minecraft:beef", Station.SMOKER);
        station(h, "minecraft:beef", Station.CAMPFIRE);
        h.succeed();
    }

    @GameTest
    public void burnTicksFromComponent(GameTestHelper h) {
        BlockPos furnace = pos(h, 2, 2, 2);
        h.assertValueEqual(FuelPolicy.burnTicks(h.getLevel(), furnace, new ItemStack(Items.COAL)), 1600, "coal");
        h.assertValueEqual(FuelPolicy.burnTicks(h.getLevel(), furnace, new ItemStack(Items.STICK)), 100, "stick");
        h.assertValueEqual(
                FuelPolicy.burnTicks(h.getLevel(), furnace, new ItemStack(Items.OAK_PLANKS)), 300, "oak planks");
        h.assertFalse(FuelPolicy.isFuel(new ItemStack(Items.DIRT)), "dirt is not a fuel");
        h.succeed();
    }

    @GameTest
    public void fuelPickPrefersCheapOrder(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 4));
        c.inventory().addItem(new ItemStack(Items.COAL));
        c.inventory().addItem(new ItemStack(Items.IRON_PICKAXE));
        BlockPos furnace = pos(h, 4, 2, 4);

        FuelPolicy.FuelPick coal = FuelPolicy.pick(c, h.getLevel(), furnace, 400, null);
        h.assertValueEqual(coal.stacks().size(), 1, "one pick");
        h.assertTrue(coal.stacks().get(0).is(Items.COAL), "coal is preferred: " + coal.stacks());
        h.assertValueEqual(coal.stacks().get(0).getCount(), 1, "one coal covers 400 ticks");
        h.assertTrue(coal.ticksCovered() >= 400, "ticks covered: " + coal.ticksCovered());

        c.inventory().clearContent();
        c.inventory().addItem(new ItemStack(Items.STICK, 4));
        c.inventory().addItem(new ItemStack(Items.IRON_PICKAXE));
        FuelPolicy.FuelPick sticks = FuelPolicy.pick(c, h.getLevel(), furnace, 400, null);
        h.assertValueEqual(sticks.stacks().size(), 1, "one pick");
        h.assertTrue(sticks.stacks().get(0).is(Items.STICK), "sticks: " + sticks.stacks());
        h.assertValueEqual(sticks.stacks().get(0).getCount(), 4, "four sticks");
        h.assertTrue(sticks.stacks().stream().noneMatch(stack -> stack.is(Items.IRON_PICKAXE)),
                "a tool is never picked: " + sticks.stacks());
        h.succeed();
    }

    @GameTest
    public void fuelPickExplicit(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.OAK_BOAT));
        BlockPos furnace = pos(h, 4, 2, 4);

        FuelPolicy.FuelPick none = FuelPolicy.pick(c, h.getLevel(), furnace, 400, null);
        h.assertTrue(none.stacks().isEmpty(), "a boat is not a cheap fuel: " + none.stacks());

        ItemMatcher boat = ItemMatcher.parse("minecraft:oak_boat").orElseThrow();
        FuelPolicy.FuelPick explicit = FuelPolicy.pick(c, h.getLevel(), furnace, 400, boat);
        h.assertValueEqual(explicit.stacks().size(), 1, "one pick");
        h.assertTrue(explicit.stacks().get(0).is(Items.OAK_BOAT), "the explicit boat: " + explicit.stacks());
        h.succeed();
    }

    @GameTest
    public void campfireStationMatchesLitCampfireOnly(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos origin = pos(h, 2, 2, 2);
        h.setBlock(6, 2, 2, Blocks.CAMPFIRE);
        BlockPos campfire = pos(h, 6, 2, 2);
        h.assertValueEqual(
                Stations.nearby(h.getLevel(), origin, Station.CAMPFIRE),
                Optional.of(campfire),
                "the lit campfire");
        h.setBlock(6, 2, 2, Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false));
        h.assertValueEqual(
                Stations.nearby(h.getLevel(), origin, Station.CAMPFIRE),
                Optional.empty(),
                "an unlit campfire is not usable");
        h.succeed();
    }
}
