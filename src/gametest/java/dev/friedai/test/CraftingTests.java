package dev.friedai.test;

import dev.friedai.crafting.Ingredient;
import dev.friedai.crafting.Recipe;
import dev.friedai.crafting.Station;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.TaskListener;
import dev.friedai.task.TaskRunner;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.builtin.CraftTask;
import dev.friedai.task.crafting.RecipeIndex;
import dev.friedai.task.crafting.Stations;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class CraftingTests {
    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}
    }

    private static TaskRunner craft(CompanionEntity c, Result result, String item, int count) {
        TaskRunner runner = new TaskRunner(c, () -> 300, () -> false);
        runner.setListener(result);
        runner.start(new CraftTask(item, count, CraftTask.StationPolicy.AUTO), "craft_item");
        return runner;
    }

    private static List<Recipe> producing(GameTestHelper h, String item) {
        return RecipeIndex.of(h.getLevel()).producing(item);
    }

    private static Recipe firstAt(GameTestHelper h, String item, Station station) {
        return producing(h, item).stream()
                .filter(recipe -> recipe.station() == station)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + station + " recipe for " + item));
    }

    @GameTest
    public void indexHasInventoryAndTableRecipes(GameTestHelper h) {
        Recipe planks = firstAt(h, "minecraft:oak_planks", Station.INVENTORY);
        h.assertValueEqual("minecraft:oak_planks", planks.output(), "oak planks output");
        h.assertValueEqual(4, planks.outputCount(), "oak planks output count");
        h.assertTrue(
                planks.ingredients().stream()
                        .anyMatch(ingredient -> ingredient.items().contains("minecraft:oak_log")),
                "oak planks ingredient should accept oak logs: " + planks.ingredients());

        h.assertTrue(
                producing(h, "minecraft:iron_pickaxe").stream()
                        .anyMatch(recipe -> recipe.station() == Station.CRAFTING_TABLE),
                "iron pickaxe needs a crafting table recipe");

        firstAt(h, "minecraft:crafting_table", Station.INVENTORY);
        h.succeed();
    }

    @GameTest
    public void indexLabelsTags(GameTestHelper h) {
        List<Ingredient> ingredients = firstAt(h, "minecraft:stick", Station.INVENTORY).ingredients();
        h.assertTrue(
                ingredients.stream().anyMatch(ingredient -> "#minecraft:planks".equals(ingredient.label())),
                "stick recipe should be labelled with #minecraft:planks: " + ingredients);
        h.succeed();
    }

    @GameTest
    public void indexHasStonecutterAndSmithing(GameTestHelper h) {
        List<Recipe> stairs = producing(h, "minecraft:andesite_stairs");
        h.assertTrue(
                stairs.stream().filter(recipe -> recipe.station() == Station.CRAFTING_TABLE).count() == 1,
                "andesite stairs crafting recipes: " + stairs);
        h.assertTrue(
                stairs.stream().filter(recipe -> recipe.station() == Station.STONECUTTER).count() == 1,
                "andesite stairs stonecutter recipes: " + stairs);

        Recipe sword = firstAt(h, "minecraft:netherite_sword", Station.SMITHING_TABLE);
        h.assertValueEqual(3, sword.ingredients().size(), "netherite sword smithing ingredients: " + sword);
        h.succeed();
    }

    @GameTest
    public void indexSkipsSpecialAndTrims(GameTestHelper h) {
        List<Recipe> rockets = producing(h, "minecraft:firework_rocket");
        h.assertTrue(
                rockets.stream().noneMatch(recipe -> "minecraft:firework_rocket".equals(recipe.id())),
                "the special firework rocket recipe must be skipped: " + rockets);
        h.assertTrue(
                producing(h, "minecraft:firework_star").isEmpty(),
                "special firework star recipes must be skipped: " + producing(h, "minecraft:firework_star"));
        h.assertTrue(
                producing(h, "minecraft:diamond_chestplate").stream()
                        .noneMatch(recipe -> recipe.station() == Station.SMITHING_TABLE),
                "smithing trims must be skipped: " + producing(h, "minecraft:diamond_chestplate"));
        h.assertTrue(
                RecipeIndex.of(h.getLevel())
                        .holder("minecraft:sentry_armor_trim_smithing_template_smithing_trim")
                        .isEmpty(),
                "the sentry armor trim recipe must not be indexed");
        h.assertTrue(
                producing(h, "minecraft:leather_boots").stream()
                        .noneMatch(recipe -> recipe.station() == Station.SMITHING_TABLE),
                "a smithing trim must not be filed under the item its display resolves to: "
                        + producing(h, "minecraft:leather_boots"));
        h.succeed();
    }

    @GameTest
    public void indexIsCached(GameTestHelper h) {
        h.assertTrue(
                RecipeIndex.of(h.getLevel()) == RecipeIndex.of(h.getLevel()),
                "RecipeIndex should be cached per RecipeManager");
        h.succeed();
    }

    @GameTest
    public void stationsFindsNearbyTable(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos origin = h.absolutePos(new BlockPos(2, 2, 2));
        h.setBlock(8, 2, 2, Blocks.CRAFTING_TABLE);
        BlockPos table = h.absolutePos(new BlockPos(8, 2, 2));

        h.assertValueEqual(
                Optional.of(table),
                Stations.nearby(h.getLevel(), origin, Station.CRAFTING_TABLE),
                "the crafting table 6 blocks away");
        h.assertTrue(
                Stations.nearby(h.getLevel(), origin, Station.STONECUTTER).isEmpty(),
                "no stonecutter is nearby");

        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Set<Station> available = Stations.available(h.getLevel(), c);
        h.assertTrue(available.contains(Station.INVENTORY), "the inventory is always available: " + available);
        h.assertTrue(available.contains(Station.CRAFTING_TABLE), "available should find the crafting table: " + available);
        h.assertTrue(!available.contains(Station.STONECUTTER), "no stonecutter is available: " + available);
        h.succeed();
    }

    @GameTest
    public void baseNeedsTwoKinds(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        BlockPos pos = h.absolutePos(new BlockPos(8, 2, 8));
        h.setBlock(10, 2, 8, Blocks.CHEST);
        h.assertTrue(!Stations.looksLikeBase(h.getLevel(), pos), "one chest is not a base");

        h.setBlock(6, 2, 8, Blocks.BED.red());
        h.assertTrue(Stations.looksLikeBase(h.getLevel(), pos), "a bed and a chest look like a base");

        h.setBlock(6, 2, 8, Blocks.AIR);
        h.setBlock(12, 2, 8, Blocks.CHEST);
        h.assertTrue(!Stations.looksLikeBase(h.getLevel(), pos), "two chests are still one kind");

        h.setBlock(10, 2, 8, Blocks.AIR);
        h.setBlock(12, 2, 8, Blocks.AIR);
        h.setBlock(8, 2, 8, Blocks.CRAFTING_TABLE);
        h.setBlock(10, 2, 8, Blocks.CHEST);
        h.assertTrue(
                !Stations.looksLikeBase(h.getLevel(), pos),
                "the station at the position itself does not count as a second kind");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void craftsPlanksInInventory(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.CHERRY_LOG));
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:cherry_planks", 4);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual("crafted 4 minecraft:cherry_planks", result.summary, "summary");
            h.assertValueEqual(0, c.inventory().countItem(Items.CHERRY_LOG), "logs left");
            h.assertValueEqual(4, c.inventory().countItem(Items.CHERRY_PLANKS), "planks");
        });
    }

    @GameTest(maxTicks = 200)
    public void craftsSticksThroughPlanks(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.MANGROVE_LOG));
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:stick", 4);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(
                    "crafted 4 minecraft:stick (also crafted 4 minecraft:mangrove_planks)",
                    result.summary,
                    "summary");
            h.assertValueEqual(0, c.inventory().countItem(Items.MANGROVE_LOG), "logs left");
            h.assertValueEqual(4, c.inventory().countItem(Items.STICK), "sticks");
            h.assertValueEqual(2, c.inventory().countItem(Items.MANGROVE_PLANKS), "planks left over");
        });
    }

    @GameTest(maxTicks = 200)
    public void craftingTableFromLog(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.ACACIA_LOG));
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:crafting_table", 1);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(
                    "crafted 1 minecraft:crafting_table (also crafted 4 minecraft:acacia_planks)",
                    result.summary,
                    "summary");
            h.assertValueEqual(0, c.inventory().countItem(Items.ACACIA_PLANKS), "planks left");
            h.assertValueEqual(1, c.inventory().countItem(Items.CRAFTING_TABLE), "crafting tables");
        });
    }

    @GameTest
    public void missingMaterialsFails(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:stick", 1);
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
        h.assertValueEqual(
                "can't craft 1 minecraft:stick; missing: 2 × #minecraft:planks", result.summary, "summary");
        h.succeed();
    }

    @GameTest
    public void noRecipeFails(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        // 26.3 makes diamond craftable from diamond blocks; netherite scrap is smelting-only.
        TaskRunner runner = craft(c, result, "minecraft:netherite_scrap", 1);
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
        h.assertValueEqual(
                "minecraft:netherite_scrap has no crafting, stonecutter or smithing recipe",
                result.summary,
                "summary");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void mainHandItemIsUsed(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DARK_OAK_LOG));
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:dark_oak_planks", 4);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual("crafted 4 minecraft:dark_oak_planks", result.summary, "summary");
            h.assertTrue(c.getMainHandItem().isEmpty(), "the main hand must be empty, was " + c.getMainHandItem());
            h.assertValueEqual(4, c.inventory().countItem(Items.DARK_OAK_PLANKS), "planks");
        });
    }

    @GameTest(maxTicks = 200)
    public void inventoryChangedMidTask(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.PALE_OAK_LOG));
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:stick", 4);
        boolean[] cleared = {false};
        h.succeedWhen(() -> {
            runner.tick();
            if (!cleared[0] && c.inventory().countItem(Items.PALE_OAK_PLANKS) > 0) {
                c.inventory().clearContent();
                cleared[0] = true;
            }
            h.assertTrue(cleared[0], "waiting for the planks to appear");
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed: " + result.status + " " + result.summary);
            h.assertTrue(
                    result.summary != null && result.summary.startsWith("the inventory changed while crafting"),
                    "summary was " + result.summary);
        });
    }
}
