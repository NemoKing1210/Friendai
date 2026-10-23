package dev.friedai.test;

import dev.friedai.crafting.Recipe;
import dev.friedai.crafting.Station;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.cooking.FuelPolicy;
import dev.friedai.task.cooking.FurnaceJob;
import dev.friedai.task.cooking.FurnaceSlots;
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
