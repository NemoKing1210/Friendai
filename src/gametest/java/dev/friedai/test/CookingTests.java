package dev.friedai.test;

import dev.friedai.crafting.Recipe;
import dev.friedai.crafting.Station;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.cooking.FuelPolicy;
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
