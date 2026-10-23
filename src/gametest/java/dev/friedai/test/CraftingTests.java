package dev.friedai.test;

import com.google.gson.JsonParser;
import dev.friedai.crafting.Ingredient;
import dev.friedai.crafting.Recipe;
import dev.friedai.crafting.Station;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.task.TaskListener;
import dev.friedai.task.TaskRunner;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.builtin.CraftTask;
import dev.friedai.task.crafting.RecipeIndex;
import dev.friedai.task.crafting.Stations;
import dev.friedai.tool.ToolExecutor;
import dev.friedai.tool.ToolResult;
import dev.friedai.tool.builtin.FriedaiTools;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
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
        return craft(c, result, item, count, CraftTask.StationPolicy.AUTO);
    }

    private static TaskRunner craft(
            CompanionEntity c, Result result, String item, int count, CraftTask.StationPolicy policy) {
        TaskRunner runner = new TaskRunner(c, () -> 300, () -> false);
        runner.setListener(result);
        runner.start(new CraftTask(item, count, policy), "craft_item");
        return runner;
    }

    // One height per station test, 24 blocks apart: more than the 16-block vertical reach of the station scan.
    private static final int WOODEN_DECK = 24;
    private static final int BASE_DECK = 48;
    private static final int TAKE_BASE_DECK = 72;
    private static final int KEEP_DECK = 96;
    private static final int EXISTING_DECK = 120;
    private static final int STONECUTTER_DECK = 144;
    private static final int SMITHING_DECK = 168;
    private static final int CAKE_DECK = 192;
    private static final int TAKE_FAIL_DECK = 216;

    /**
     * Station scenes live on separate stone decks well above the shared test grid, each at its own height, so no
     * test's ±16 station scan can see another test's ground-level or deck-level station blocks.
     */
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

    /** True when the deck at {@code y} holds a block of the given type. */
    private static boolean hasBlock(GameTestHelper h, Block block, int size, int y) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int scan = y - 1; scan <= y + 4; scan++) {
                    if (h.getLevel()
                            .getBlockState(h.absolutePos(new BlockPos(x, scan, z)))
                            .is(block)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static ItemStack find(CompanionEntity c, Item item) {
        for (int slot = 0; slot < c.inventory().getContainerSize(); slot++) {
            ItemStack stack = c.inventory().getItem(slot);
            if (stack.is(item)) {
                return stack;
            }
        }
        return c.getMainHandItem().is(item) ? c.getMainHandItem() : ItemStack.EMPTY;
    }

    private static void addLogs(CompanionEntity c, Item log, int count) {
        for (int i = 0; i < count; i++) {
            c.inventory().addItem(new ItemStack(log));
        }
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static List<Recipe> producing(GameTestHelper h, String item) {
        return RecipeIndex.of(h.getLevel()).producing(item);
    }

    private static ToolResult tool(CompanionEntity c, String name, String json) {
        ToolExecutor tools = FriedaiTools.create(() -> 32).bind(c);
        return tools.execute(new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null));
    }

    @GameTest
    public void getRecipeForStick(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 2));
        ToolResult r = tool(c, "get_recipe", "{\"item\":\"minecraft:stick\"}");
        h.assertTrue(!r.error(), "error: " + r.content());
        h.assertTrue(
                r.content().contains("minecraft:stick ×4 in the inventory from 2 × #minecraft:planks"),
                "content was " + r.content());
        h.assertTrue(
                java.util.Arrays.stream(r.content().split("\n"))
                        .anyMatch(line -> line.startsWith("can craft 1 minecraft:stick now")),
                "content was " + r.content());
        h.succeed();
    }

    @GameTest
    public void getRecipeShowsSmelting(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = tool(c, "get_recipe", "{\"item\":\"minecraft:iron_ingot\"}");
        h.assertTrue(!r.error(), "error: " + r.content());
        h.assertTrue(r.content().contains("in a furnace (10 s)"), "content was " + r.content());
        h.assertTrue(r.content().contains("in a blast furnace (10 s)"), "content was " + r.content());
        h.succeed();
    }

    @GameTest
    public void getRecipeForDiamond(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        ToolResult r = tool(c, "get_recipe", "{\"item\":\"minecraft:netherite_scrap\"}");
        h.assertTrue(!r.error(), "error: " + r.content());
        h.assertTrue(
                r.content()
                        .equals("minecraft:netherite_scrap ×1 in a furnace (10 s) from 1 × minecraft:ancient_debris\n"
                                + "minecraft:netherite_scrap ×1 in a blast furnace (10 s) from 1 × minecraft:ancient_debris\n"
                                + "can't craft 1 minecraft:netherite_scrap yet; missing: 1 × minecraft:netherite_scrap"),
                "content was " + r.content());
        h.succeed();
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

    @GameTest(maxTicks = 1200)
    public void woodenPickaxeFromLogsInField(GameTestHelper h) {
        deck(h, 16, WOODEN_DECK);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, WOODEN_DECK + 1, 2);
        addLogs(c, Items.JUNGLE_LOG, 3);
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:wooden_pickaxe", 1);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(1, c.inventory().countItem(Items.WOODEN_PICKAXE), "pickaxes");
            h.assertValueEqual(1, c.inventory().countItem(Items.CRAFTING_TABLE), "crafting tables");
            h.assertFalse(hasBlock(h, Blocks.CRAFTING_TABLE, 16, WOODEN_DECK), "the placed table should be taken back");
            h.assertTrue(result.summary.contains("placed a crafting table at "), "summary was " + result.summary);
            h.assertTrue(result.summary.contains("and took it back"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 1200)
    public void leavesTableAtBase(GameTestHelper h) {
        deck(h, 16, BASE_DECK);
        h.setBlock(6, BASE_DECK + 1, 2, Blocks.CHEST);
        h.setBlock(2, BASE_DECK + 1, 6, Blocks.BED.white());
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, BASE_DECK + 1, 2);
        addLogs(c, Items.OAK_LOG, 3);
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:wooden_pickaxe", 1);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(0, c.inventory().countItem(Items.CRAFTING_TABLE), "crafting tables");
            h.assertTrue(hasBlock(h, Blocks.CRAFTING_TABLE, 16, BASE_DECK), "the table should be left at the base");
            h.assertTrue(
                    result.summary.contains("and left it there (looks like a base)"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 1200)
    public void takePolicyOverridesBase(GameTestHelper h) {
        deck(h, 16, TAKE_BASE_DECK);
        h.setBlock(6, TAKE_BASE_DECK + 1, 2, Blocks.CHEST);
        h.setBlock(2, TAKE_BASE_DECK + 1, 6, Blocks.BED.white());
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, TAKE_BASE_DECK + 1, 2);
        addLogs(c, Items.DARK_OAK_LOG, 3);
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:wooden_pickaxe", 1, CraftTask.StationPolicy.TAKE);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(1, c.inventory().countItem(Items.CRAFTING_TABLE), "crafting tables");
            h.assertFalse(hasBlock(h, Blocks.CRAFTING_TABLE, 16, TAKE_BASE_DECK), "the table should be taken back");
            h.assertTrue(result.summary.contains("and took it back"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 1200)
    public void keepPolicyInField(GameTestHelper h) {
        deck(h, 16, KEEP_DECK);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, KEEP_DECK + 1, 2);
        addLogs(c, Items.ACACIA_LOG, 3);
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:wooden_pickaxe", 1, CraftTask.StationPolicy.KEEP);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(0, c.inventory().countItem(Items.CRAFTING_TABLE), "crafting tables");
            h.assertTrue(hasBlock(h, Blocks.CRAFTING_TABLE, 16, KEEP_DECK), "the table should be left in place");
            h.assertTrue(result.summary.contains("and left it there"), "summary was " + result.summary);
            h.assertFalse(result.summary.contains("looks like a base"), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 1200)
    public void usesExistingTable(GameTestHelper h) {
        deck(h, 16, EXISTING_DECK);
        h.setBlock(8, EXISTING_DECK + 1, 2, Blocks.CRAFTING_TABLE);
        BlockPos table = h.absolutePos(new BlockPos(8, EXISTING_DECK + 1, 2));
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, EXISTING_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.SPRUCE_PLANKS, 3));
        c.inventory().addItem(new ItemStack(Items.STICK, 2));
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:wooden_pickaxe", 1);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(1, c.inventory().countItem(Items.WOODEN_PICKAXE), "pickaxes");
            h.assertValueEqual(0, c.inventory().countItem(Items.CRAFTING_TABLE), "crafting tables");
            h.assertBlockPresent(Blocks.CRAFTING_TABLE, 8, EXISTING_DECK + 1, 2);
            h.assertTrue(
                    result.summary.contains("used the crafting table at " + coords(table)),
                    "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void stonecutterStairs(GameTestHelper h) {
        deck(h, 16, STONECUTTER_DECK);
        h.setBlock(8, STONECUTTER_DECK + 1, 2, Blocks.STONECUTTER);
        h.setBlock(8, STONECUTTER_DECK + 1, 6, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, STONECUTTER_DECK + 1, 2);
        c.inventory().addItem(new ItemStack(Items.ANDESITE, 4));
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:andesite_stairs", 4);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(4, c.inventory().countItem(Items.ANDESITE_STAIRS), "stairs");
            h.assertValueEqual(0, c.inventory().countItem(Items.ANDESITE), "andesite left");
            h.assertTrue(result.summary.contains("used the stonecutter at "), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void smithingKeepsEnchantment(GameTestHelper h) {
        deck(h, 16, SMITHING_DECK);
        h.setBlock(8, SMITHING_DECK + 1, 2, Blocks.SMITHING_TABLE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, SMITHING_DECK + 1, 2);
        Holder<Enchantment> sharpness =
                h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(sharpness, 3);
        c.inventory().addItem(sword);
        c.inventory().addItem(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        c.inventory().addItem(new ItemStack(Items.NETHERITE_INGOT));
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:netherite_sword", 1);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(1, c.inventory().countItem(Items.NETHERITE_SWORD), "netherite swords");
            h.assertValueEqual(0, c.inventory().countItem(Items.DIAMOND_SWORD), "diamond swords left");
            h.assertValueEqual(
                    0, c.inventory().countItem(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), "templates left");
            h.assertValueEqual(0, c.inventory().countItem(Items.NETHERITE_INGOT), "netherite ingots left");
            ItemStack upgraded = find(c, Items.NETHERITE_SWORD);
            h.assertValueEqual(3, upgraded.getEnchantments().getLevel(sharpness), "sharpness on the upgraded sword");
            h.assertTrue(
                    result.summary.contains("used the smithing table at "), "summary was " + result.summary);
        });
    }

    @GameTest(maxTicks = 600)
    public void cakeReturnsBuckets(GameTestHelper h) {
        deck(h, 16, CAKE_DECK);
        h.setBlock(8, CAKE_DECK + 1, 2, Blocks.CRAFTING_TABLE);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, CAKE_DECK + 1, 2);
        for (int i = 0; i < 3; i++) {
            c.inventory().addItem(new ItemStack(Items.MILK_BUCKET));
        }
        c.inventory().addItem(new ItemStack(Items.SUGAR, 2));
        c.inventory().addItem(new ItemStack(Items.EGG));
        c.inventory().addItem(new ItemStack(Items.WHEAT, 3));
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:cake", 1);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertValueEqual(1, c.inventory().countItem(Items.CAKE), "cakes");
            h.assertValueEqual(0, c.inventory().countItem(Items.MILK_BUCKET), "milk buckets left");
            h.assertValueEqual(3, c.inventory().countItem(Items.BUCKET), "returned buckets");
        });
    }

    @GameTest(maxTicks = 1200)
    public void takeBackFailsGracefully(GameTestHelper h) {
        deck(h, 16, TAKE_FAIL_DECK);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, TAKE_FAIL_DECK + 1, 2);
        addLogs(c, Items.ACACIA_LOG, 3);
        Result result = new Result();
        TaskRunner runner = craft(c, result, "minecraft:wooden_pickaxe", 1);
        boolean[] protectedOnce = {false};
        h.succeedWhen(() -> {
            runner.tick();
            if (!protectedOnce[0] && hasBlock(h, Blocks.CRAFTING_TABLE, 16, TAKE_FAIL_DECK)) {
                c.hands().overrideProtection(pos -> true);
                protectedOnce[0] = true;
            }
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(protectedOnce[0], "the table was never placed");
            h.assertTrue(
                    result.summary.contains("but couldn't take it back: "), "summary was " + result.summary);
            h.assertTrue(hasBlock(h, Blocks.CRAFTING_TABLE, 16, TAKE_FAIL_DECK), "the protected table should remain");
        });
    }
}
