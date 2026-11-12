package dev.friendai.test;

import static dev.friendai.test.LiveCompanionTests.Mode;
import static dev.friendai.test.LiveCompanionTests.Verdict;
import static dev.friendai.test.LiveCompanionTests.outcrop;
import static dev.friendai.test.LiveCompanionTests.round;
import static dev.friendai.test.LiveCompanionTests.scenario;
import static dev.friendai.test.LiveCompanionTests.tree;

import dev.friendai.task.Task;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A second live battery: requests no single tool answers. Each one needs the companion to work out what the player
 * means and how to get there in this game — a tool chain from nothing, the right material, a prerequisite it has to
 * notice, a stop in the middle of work, an honest answer about itself. Like {@link LiveCompanionTests}, it runs only
 * when {@code FRIENDAI_LIVE_CONFIG} names a server config and prints one {@code [RESULT]} line per scenario.
 */
public class LiveUnderstandingTests {
    private static final int SIZE = 24;
    private static final int MAX_TICKS = 900_000;

    // ---- tool chains -------------------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u01")
    public void stonePickaxeFromNothing(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U01 make a stone pickaxe from nothing (tree and rock nearby)",
                420,
                Mode.EARLY,
                0,
                live -> {
                    tree(h, 9, 14);
                    tree(h, 13, 5);
                    outcrop(h);
                },
                "Сделай себе каменную кирку.",
                null,
                live -> new Verdict(live.carried(Items.STONE_PICKAXE) >= 1, live.inventory()));
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u02")
    public void torchesFromCoalAndPlanks(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U02 make torches (has coal and planks, no sticks)",
                150,
                Mode.EARLY,
                0,
                live -> {
                    live.give(Items.COAL, 2);
                    live.give(Items.OAK_PLANKS, 2);
                },
                "Сделай несколько факелов.",
                null,
                live -> new Verdict(live.carried(Items.TORCH) >= 4, live.inventory()));
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u03")
    public void bestSword(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U03 make the best sword you can (iron, stone and wood on hand)",
                180,
                Mode.SETTLE,
                10,
                live -> {
                    live.give(Items.IRON_INGOT, 2);
                    live.give(Items.STICK, 1);
                    live.give(Items.OAK_PLANKS, 4);
                    live.give(Items.COBBLESTONE, 2);
                },
                "Сделай мне лучший меч, какой сможешь.",
                null,
                live -> {
                    int iron = live.carried(Items.IRON_SWORD) + owned(live, Items.IRON_SWORD);
                    int lesser = live.carried(Items.STONE_SWORD)
                            + live.carried(Items.WOODEN_SWORD)
                            + owned(live, Items.STONE_SWORD)
                            + owned(live, Items.WOODEN_SWORD);
                    return new Verdict(iron >= 1, "iron swords " + iron + ", lesser swords " + lesser);
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u04")
    public void furnace(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U04 I need a furnace (cobblestone and logs, no table)",
                180,
                Mode.EARLY,
                0,
                live -> {
                    live.give(Items.COBBLESTONE, 8);
                    live.give(Items.OAK_LOG, 2);
                },
                "Мне нужна печка.",
                null,
                live -> {
                    int made = live.carried(Items.FURNACE)
                            + owned(live, Items.FURNACE)
                            + live.blocks(Blocks.FURNACE, 2, 8);
                    return new Verdict(made >= 1, "furnaces " + made + "; " + live.inventory());
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u05")
    public void cookMeat(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U05 cook the meat (raw beef, cobblestone, coal, logs)",
                360,
                Mode.EARLY,
                0,
                live -> {
                    live.give(Items.BEEF, 3);
                    live.give(Items.COBBLESTONE, 8);
                    live.give(Items.COAL, 2);
                    live.give(Items.OAK_LOG, 2);
                },
                "Пожарь мясо.",
                null,
                live -> new Verdict(
                        live.carried(Items.COOKED_BEEF) + owned(live, Items.COOKED_BEEF) >= 1, live.inventory()));
    }

    // ---- food and animals --------------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u06")
    public void findFood(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U06 find something to eat (two cows, empty hands)",
                240,
                Mode.EARLY,
                0,
                live -> {
                    pen(h);
                    live.companion.hunger().load(10, 0.0f, 0.0f);
                    h.spawn(EntityTypes.COW, 12, 2, 12);
                    h.spawn(EntityTypes.COW, 14, 2, 10);
                },
                "Найди себе что-нибудь поесть.",
                null,
                live -> {
                    int food = live.carried(Items.BEEF) + live.carried(Items.COOKED_BEEF);
                    return new Verdict(
                            food >= 1 || live.companion.hunger().food() > 10,
                            "beef " + food + ", food " + live.companion.hunger().food() + "; " + live.inventory());
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u07")
    public void feedCows(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U07 feed the cows (has wheat)",
                150,
                Mode.EARLY,
                0,
                live -> {
                    pen(h);
                    live.give(Items.WHEAT, 4);
                    h.spawn(EntityTypes.COW, 11, 2, 11);
                    h.spawn(EntityTypes.COW, 13, 2, 12);
                },
                "Покорми коров.",
                null,
                live -> {
                    var box = new AABB(h.absoluteVec(Vec3.ZERO), h.absoluteVec(new Vec3(SIZE, 8, SIZE)));
                    List<Animal> cows =
                            h.getLevel().getEntitiesOfClass(Animal.class, box, a -> a.getType() == EntityTypes.COW);
                    long fed = cows.stream()
                            .filter(a -> a.isInLove() || a.isBaby())
                            .count();
                    return new Verdict(
                            fed >= 1 || live.carried(Items.WHEAT) <= 2,
                            "cows in love or babies " + fed + ", wheat left " + live.carried(Items.WHEAT));
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u08")
    public void plantWheat(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U08 plant wheat (hoe, seeds, grass and water)",
                240,
                Mode.EARLY,
                0,
                live -> {
                    for (int x = 7; x <= 15; x++) {
                        for (int z = 7; z <= 15; z++) h.setBlock(x, 1, z, Blocks.GRASS_BLOCK);
                    }
                    h.setBlock(11, 1, 11, Blocks.WATER);
                    live.give(Items.WOODEN_HOE, 1);
                    live.give(Items.WHEAT_SEEDS, 6);
                },
                "Посади пшеницу.",
                null,
                live -> {
                    int crops = live.blocks(Blocks.WHEAT, 2, 2);
                    return new Verdict(crops >= 2, "wheat planted " + crops + "; " + live.inventory());
                });
    }

    // ---- giving and storing ------------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u09")
    public void bringPlanks(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U09 bring me 8 planks (has two logs)",
                150,
                Mode.EARLY,
                0,
                live -> live.give(Items.OAK_LOG, 2),
                "Принеси мне восемь досок.",
                null,
                live -> {
                    int got = owned(live, Items.OAK_PLANKS);
                    return new Verdict(got >= 8, "owner has or was dropped " + got + "; " + live.inventory());
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u10")
    public void storeAllButTheSword(GameTestHelper h) throws Exception {
        BlockPos chest = new BlockPos(9, 2, 4);
        scenario(
                h,
                "U10 put everything in the chest except the sword",
                150,
                Mode.SETTLE,
                10,
                live -> {
                    h.setBlock(chest, Blocks.CHEST);
                    live.give(Items.IRON_SWORD, 1);
                    live.give(Items.COBBLESTONE, 10);
                    live.give(Items.DIRT, 6);
                    live.give(Items.BREAD, 3);
                },
                "Положи всё в сундук, кроме меча.",
                null,
                live -> {
                    int stored = 0;
                    int swordsStored = 0;
                    if (h.getLevel().getBlockEntity(h.absolutePos(chest)) instanceof Container container) {
                        for (int slot = 0; slot < container.getContainerSize(); slot++) {
                            ItemStack stack = container.getItem(slot);
                            if (stack.is(Items.IRON_SWORD)) swordsStored++;
                            else if (stack.is(Items.COBBLESTONE) || stack.is(Items.DIRT) || stack.is(Items.BREAD))
                                stored += stack.getCount();
                        }
                    }
                    int kept = live.carried(Items.IRON_SWORD);
                    return new Verdict(
                            stored >= 15 && swordsStored == 0 && kept == 1,
                            "stored " + stored + " of 19, sword stored " + swordsStored + ", sword kept " + kept);
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u11")
    public void giveEverything(GameTestHelper h) throws Exception {
        Item[] kinds = {Items.COBBLESTONE, Items.BREAD, Items.TORCH, Items.OAK_LOG};
        scenario(
                h,
                "U11 give me everything you have",
                150,
                Mode.SETTLE,
                10,
                live -> {
                    live.give(Items.COBBLESTONE, 5);
                    live.give(Items.BREAD, 2);
                    live.give(Items.TORCH, 4);
                    live.give(Items.OAK_LOG, 3);
                },
                "Отдай мне всё, что у тебя есть.",
                null,
                live -> {
                    int handed = 0;
                    int left = 0;
                    for (Item kind : kinds) {
                        if (owned(live, kind) > 0) handed++;
                        if (live.carried(kind) > 0) left++;
                    }
                    return new Verdict(
                            handed >= 3 && left <= 1, "kinds handed " + handed + " of 4, kinds kept " + left);
                });
    }

    // ---- reading the situation ---------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u12")
    public void diamondWithAWoodenPickaxe(GameTestHelper h) throws Exception {
        BlockPos ore = new BlockPos(14, 3, 10);
        scenario(
                h,
                "U12 mine that diamond (holds only a wooden pickaxe)",
                150,
                Mode.SETTLE,
                20,
                live -> {
                    outcrop(h);
                    h.setBlock(ore, Blocks.DIAMOND_ORE);
                    live.hold(Items.WOODEN_PICKAXE);
                },
                "Добудь вон тот алмаз.",
                null,
                live -> {
                    boolean intact = h.getBlockState(ore).is(Blocks.DIAMOND_ORE);
                    int diamonds = live.carried(Items.DIAMOND);
                    String said = live.spoken().toLowerCase(Locale.ROOT);
                    // Leaving the ore whole and going for a better pickaxe is the right call; breaking it with wood
                    // would destroy the diamond.
                    boolean explains = said.contains("желез")
                            || said.contains("кирк")
                            || said.contains("инструмент")
                            || said.contains("iron")
                            || said.contains("pickaxe");
                    return new Verdict(
                            (intact && explains) || diamonds >= 1,
                            "ore intact " + intact + ", diamonds " + diamonds + ", names a better tool " + explains);
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u13")
    public void clearTheSand(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U13 clear this sand away",
                180,
                Mode.EARLY,
                0,
                live -> {
                    for (int x = 7; x <= 9; x++) {
                        for (int z = 7; z <= 9; z++) h.setBlock(x, 2, z, Blocks.SAND);
                    }
                },
                "Убери отсюда этот песок.",
                null,
                live -> {
                    int left = live.blocks(Blocks.SAND, 2, 2);
                    return new Verdict(left <= 3, "sand left " + left + " of 9");
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u14")
    public void goWhereILook(GameTestHelper h) throws Exception {
        BlockPos mark = new BlockPos(16, 2, 4);
        scenario(
                h,
                "U14 go where I am looking",
                120,
                Mode.EARLY,
                0,
                live -> {
                    h.setBlock(mark, Blocks.PUMPKIN);
                    // The owner stands at (4, 2, 4) and looks east at the pumpkin twelve blocks away.
                    live.owner.setYRot(-90.0f);
                    live.owner.setYHeadRot(-90.0f);
                    live.owner.setXRot(5.0f);
                },
                "Иди туда, куда я смотрю.",
                null,
                live -> {
                    Vec3 target = h.absoluteVec(Vec3.atCenterOf(mark));
                    double distance = live.companion.position().distanceTo(target);
                    return new Verdict(distance <= 3.5, "distance to the looked-at block " + round(distance));
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u15")
    public void stopInTheMiddle(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U15 stop in the middle of a long job",
                120,
                Mode.SETTLE,
                20,
                live -> {
                    tree(h, 10, 12);
                    tree(h, 15, 7);
                    tree(h, 17, 15);
                },
                "Наруби брёвен, штук двадцать.",
                live -> TestMenuRequests.voice(live.owner, "Стоп, хватит, ничего не делай."),
                live -> {
                    String task = live.companion
                            .executionCoordinator()
                            .current()
                            .map(Task::type)
                            .orElse("none");
                    int queued = live.companion.executionCoordinator().queue().jobCount();
                    boolean idle = (task.equals("none") || task.equals("follow") || task.equals("stay")) && queued == 0;
                    return new Verdict(
                            idle, "task " + task + ", queued jobs " + queued + ", logs " + live.carried(Items.OAK_LOG));
                });
    }

    // ---- talking ------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u16")
    public void firstNightAdvice(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U16 how do I survive the first night (advice)",
                90,
                Mode.SETTLE,
                8,
                live -> {},
                "Как мне пережить первую ночь?",
                null,
                live -> {
                    String said = live.spoken().toLowerCase(Locale.ROOT);
                    String[] ideas = {
                        "укрыт", "убежищ", "дом", "факел", "свет", "кроват", "меч", "оруж", "поесть", "еда"
                    };
                    int named = 0;
                    for (String idea : ideas) if (said.contains(idea)) named++;
                    return new Verdict(named >= 2, "survival ideas named " + named);
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_u17")
    public void honestAboutHealth(GameTestHelper h) throws Exception {
        scenario(
                h,
                "U17 how do you feel (health 6 of 20)",
                90,
                Mode.SETTLE,
                8,
                live -> live.companion.setHealth(6.0f),
                "Как ты себя чувствуешь?",
                null,
                live -> {
                    String said = live.spoken().toLowerCase(Locale.ROOT);
                    boolean hurt = said.contains("ранен")
                            || said.contains("здоровь")
                            || said.contains("хп")
                            || said.contains("сердц")
                            || said.contains("плохо")
                            || said.contains("слаб")
                            || said.contains("6");
                    return new Verdict(hurt, "admits being hurt: " + hurt);
                });
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** Items of one kind the owner holds or that lie within four blocks of the owner. */
    private static int owned(LiveCompanionTests.Live live, Item item) {
        int count = live.owner.getInventory().countItem(item);
        for (ItemEntity drop : live.h
                .getLevel()
                .getEntitiesOfClass(
                        ItemEntity.class,
                        live.owner.getBoundingBox().inflate(4),
                        e -> e.getItem().is(item))) {
            count += drop.getItem().getCount();
        }
        return count;
    }

    /** A fence around the platform, so animals stay on the loaded ground. */
    private static void pen(GameTestHelper h) {
        for (int i = 0; i < SIZE; i++) {
            h.setBlock(i, 2, 0, Blocks.OAK_FENCE);
            h.setBlock(i, 2, SIZE - 1, Blocks.OAK_FENCE);
            h.setBlock(0, 2, i, Blocks.OAK_FENCE);
            h.setBlock(SIZE - 1, 2, i, Blocks.OAK_FENCE);
        }
    }
}
