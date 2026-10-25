package dev.friendai.test;

import dev.friendai.core.BrainService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.LogEntry;
import dev.friendai.debug.LogKind;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.pathing.Cell;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.PathingConfig;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.GoToTask;
import dev.friendai.task.builtin.MineBlocksTask;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.BreakProgress;
import dev.friendai.task.nav.LevelCellView;
import dev.friendai.task.nav.MaterialPicker;
import dev.friendai.task.nav.MoveHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class PathingTests {

    /** What a finished task reported, so a test can wait for the outcome instead of a position. */
    private static final class Outcome implements TaskListener {
        TaskStatus status;
        String summary = "";
        /** The last few pathing decisions, so a timed-out test says why it could not walk. */
        final List<String> debug = new ArrayList<>();
        final List<String> notices = new ArrayList<>();

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            this.status = TaskStatus.FAILED;
            this.summary = "cancelled: " + reason;
        }

        @Override
        public void onDebug(String text) {
            debug.add(text);
            if (debug.size() > 10) {
                debug.remove(0);
            }
        }

        @Override
        public void onNotice(String text) {
            notices.add(text);
        }

        String why() {
            return status + " " + summary + " last paths: " + debug;
        }
    }

    /** A companion whose pathing may not leave a 16x16 platform. */
    private static CompanionEntity walker(GameTestHelper h, float x, float y, float z) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, x, y, z);
        c.setPathBounds(new AABB(
                h.absoluteVec(new Vec3(-1, -1, -1)), h.absoluteVec(new Vec3(17, 12, 17))));
        return c;
    }

    private static void walkTo(CompanionEntity c, GameTestHelper h, Outcome out, double x, double y, double z) {
        c.taskRunner().setListener(out);
        c.taskRunner().start(new GoToTask(h.absoluteVec(new Vec3(x, y, z))), "go_to");
    }

    private static void assertBlock(GameTestHelper h, int x, int y, int z, net.minecraft.world.level.block.Block block,
            String what) {
        BlockState state = h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, y, z)));
        h.assertTrue(state.is(block), what + " at " + x + " " + y + " " + z + " is " + state);
    }

    private static Cell cellAt(LevelCellView view, GameTestHelper h, int x, int y, int z) {
        return cellAt(view, h.absolutePos(new BlockPos(x, y, z)));
    }

    private static Cell cellAt(LevelCellView view, BlockPos absolute) {
        return view.at(absolute.getX(), absolute.getY(), absolute.getZ());
    }

    @GameTest
    public void cellViewClassifiesBlocks(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        h.setBlock(new BlockPos(2, 2, 2), Blocks.STONE);
        h.setBlock(new BlockPos(3, 2, 2), Blocks.OAK_PLANKS);
        h.setBlock(new BlockPos(4, 2, 2), Blocks.WATER);
        h.setBlock(new BlockPos(5, 2, 2), Blocks.MAGMA_BLOCK);
        h.setBlock(new BlockPos(6, 2, 2), Blocks.STONE_SLAB);
        h.setBlock(new BlockPos(2, 2, 5), Blocks.OAK_FENCE);
        h.setBlock(new BlockPos(3, 2, 5), Blocks.SCAFFOLDING);
        h.setBlock(new BlockPos(4, 2, 5), Blocks.OAK_LOG);

        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        LevelCellView bare = new LevelCellView(c, PathingConfig.DEFAULTS, CostModel.DEFAULTS);

        h.assertTrue(cellAt(bare, h, 2, 2, 2).kind() == Cell.Kind.SOLID, "stone");
        h.assertFalse(cellAt(bare, h, 3, 2, 2).breakable(), "planks are blacklisted");
        h.assertTrue(cellAt(bare, h, 4, 2, 2).kind() == Cell.Kind.WATER, "water");
        h.assertTrue(cellAt(bare, h, 4, 2, 2).placeable(), "water can be filled");
        h.assertTrue(cellAt(bare, h, 5, 2, 2).kind() == Cell.Kind.HAZARD, "magma");
        h.assertTrue(cellAt(bare, h, 6, 2, 2).kind() == Cell.Kind.PARTIAL, "slab");
        h.assertTrue(cellAt(bare, h, 2, 2, 5).kind() == Cell.Kind.TALL, "fence");
        h.assertTrue(cellAt(bare, h, 3, 2, 5).kind() == Cell.Kind.CLIMBABLE, "scaffolding");

        BlockPos logPos = h.absolutePos(new BlockPos(4, 2, 5));
        BlockState log = h.getLevel().getBlockState(logPos);
        int expected = BreakProgress.ticksToBreak(log.getDestroySpeed(h.getLevel(), logPos), 1f, true)
                + CostModel.DEFAULTS.logPenalty();
        h.assertTrue(cellAt(bare, logPos).breakTicks() == expected,
                "log ticks " + cellAt(bare, logPos).breakTicks() + " != " + expected);

        int bareStone = cellAt(bare, h.absolutePos(new BlockPos(2, 2, 2))).breakTicks();
        c.inventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
        LevelCellView armed = new LevelCellView(c, PathingConfig.DEFAULTS, CostModel.DEFAULTS);
        h.assertTrue(cellAt(armed, h.absolutePos(new BlockPos(2, 2, 2))).breakTicks() < bareStone,
                "a pickaxe breaks stone faster");
        h.succeed();
    }

    @GameTest(maxTicks = 20)
    public void hazardPredicateCoversLavaAndFire(GameTestHelper h) {
        h.assertTrue(LevelCellView.hazardous(Blocks.LAVA.defaultBlockState()), "lava");
        h.assertTrue(LevelCellView.hazardous(Blocks.FIRE.defaultBlockState()), "fire");
        h.assertTrue(LevelCellView.hazardous(Blocks.CACTUS.defaultBlockState()), "cactus");
        h.assertFalse(LevelCellView.hazardous(Blocks.STONE.defaultBlockState()), "stone");
        h.assertFalse(LevelCellView.hazardous(Blocks.WATER.defaultBlockState()), "water");
        h.succeed();
    }

    @GameTest
    public void materialPickerPrefersDirt(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        c.inventory().addItem(new ItemStack(Items.DIRT, 5));
        c.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 3));
        c.inventory().addItem(new ItemStack(Items.SAND, 4));
        c.inventory().addItem(new ItemStack(Items.COBBLESTONE, 2));

        MaterialPicker picker = new MaterialPicker(PathingConfig.DEFAULTS);
        h.assertTrue(picker.count(c.inventory()) == 7, "count " + picker.count(c.inventory()));
        h.assertTrue(picker.cheapestPrice(c.inventory()) == 1.0, "price " + picker.cheapestPrice(c.inventory()));
        Optional<ItemStack> taken = picker.takeCheapest(c.inventory());
        h.assertTrue(taken.isPresent(), "nothing taken");
        h.assertTrue(taken.get().is(Items.DIRT), "took " + taken.get());
        h.assertTrue(c.inventory().countItem(Items.DIRT) == 4, "dirt left " + c.inventory().countItem(Items.DIRT));
        h.succeed();
    }

    @GameTest
    public void protectedWallIsNotDug(GameTestHelper h) {
        GameTestSupport.platform(h, 10);
        int wallX = h.absolutePos(new BlockPos(5, 2, 0)).getX();
        for (int z = 0; z < 8; z++) {
            for (int y = 2; y <= 3; y++) {
                h.setBlock(new BlockPos(5, y, z), Blocks.STONE);
            }
        }
        h.setBlock(new BlockPos(2, 2, 2), Blocks.STONE);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 1, 2, 1);
        c.hands().overrideProtection(p -> p.getX() == wallX);

        LevelCellView view = new LevelCellView(c, PathingConfig.DEFAULTS, CostModel.DEFAULTS);
        for (int z = 0; z < 8; z++) {
            for (int y = 2; y <= 3; y++) {
                h.assertTrue(cellAt(view, h, 5, y, z).breakTicks() == -1, "wall at y=" + y + " z=" + z);
            }
        }
        h.assertTrue(cellAt(view, h, 2, 2, 2).breakTicks() > 0, "stone outside the wall stays breakable");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void taskDebugReachesLog(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        BrainService brains = FriendaiServerState.get(server).brains();
        CompanionEntity c = CompanionManager.get(server).summon(player);
        // The task listener is installed with the owner's brain entry, as it already is for notices.
        brains.currentPrompt(player.getUUID());
        c.taskRunner().debug("path test");
        List<LogEntry> log = brains.debug().logOf(player.getUUID()).after(0, 50);
        h.assertTrue(log.stream().anyMatch(e -> e.kind() == LogKind.TASK && e.text().contains("path test")),
                "debug line must reach the owner's log: " + log);
        h.succeed();
    }

    @GameTest(maxTicks = 800)
    public void goToBridgesPitWithDirt(GameTestHelper h) {
        GameTestSupport.platformAt(h, 1, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 2; y <= 5; y++) {
                    boolean pit = x >= 6 && x <= 8;
                    h.setBlock(x, y, z, pit ? Blocks.AIR : Blocks.STONE);
                }
            }
        }
        CompanionEntity c = walker(h, 2, 6, 8);
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        Outcome out = new Outcome();
        walkTo(c, h, out, 12.5, 6, 8.5);
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.SUCCESS, "go_to did not finish: " + out.why());
            int bridged = 0;
            for (int x = 6; x <= 8; x++) {
                for (int z = 7; z <= 9; z++) {
                    BlockState state = h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, 5, z)));
                    if (state.is(Blocks.DIRT)) {
                        bridged++;
                    }
                }
            }
            // A running jump over the last two cells can save one placement, so two dirt blocks already mean a bridge.
            h.assertTrue(bridged >= 2, "bridged " + bridged + " cells of the pit row with dirt");
        });
    }

    /** A 4-deep pit walled in unbreakable planks with a dirt mound on the near side, the only material around. */
    private static void plankPitWithDirtMound(GameTestHelper h) {
        GameTestSupport.platformAt(h, 1, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 2; y <= 5; y++) {
                    boolean pit = x >= 6 && x <= 8;
                    // Planks are blacklisted, so the only way across the 4-deep pit is to build over it.
                    h.setBlock(x, y, z, pit ? Blocks.AIR : Blocks.OAK_PLANKS);
                }
            }
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                for (int y = 6; y <= 7; y++) {
                    h.setBlock(x, y, z, Blocks.DIRT);
                }
            }
        }
    }

    @GameTest(maxTicks = 800)
    public void goToGathersDirtThenBridges(GameTestHelper h) {
        plankPitWithDirtMound(h);
        CompanionEntity c = walker(h, 1, 6, 8);
        Outcome out = new Outcome();
        walkTo(c, h, out, 12.5, 6, 8.5);
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.SUCCESS, "go_to did not finish: " + out.why());
            int bridged = 0;
            for (int x = 6; x <= 8; x++) {
                for (int z = 7; z <= 9; z++) {
                    BlockState state = h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, 5, z)));
                    if (state.is(Blocks.DIRT)) {
                        bridged++;
                    }
                }
            }
            h.assertTrue(bridged >= 1, "bridged " + bridged + " cells of the pit row with gathered dirt");
            h.assertTrue(out.notices.stream().anyMatch(text -> text.startsWith("gathering ")),
                    "no gathering notice, notices: " + out.notices);
        });
    }

    @GameTest(maxTicks = 800)
    public void minesStoneUnderDirt(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        h.setBlock(new BlockPos(8, 2, 8), Blocks.POLISHED_ANDESITE);
        h.setBlock(new BlockPos(8, 3, 8), Blocks.DIRT);
        h.setBlock(new BlockPos(8, 4, 8), Blocks.DIRT);
        CompanionEntity c = walker(h, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
        MineBlocksTask task = new MineBlocksTask(
                BlockMatcher.parse("minecraft:polished_andesite").orElseThrow(), 1, 32);
        Outcome out = new Outcome();
        c.taskRunner().setListener(out);
        c.taskRunner().start(task, "mine_blocks");
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.SUCCESS, "mine_blocks did not finish: " + out.why());
            BlockState left = h.getLevel().getBlockState(h.absolutePos(new BlockPos(8, 2, 8)));
            h.assertTrue(left.isAir(), "andesite still there: " + left);
            h.assertTrue(c.inventory().countItem(Items.POLISHED_ANDESITE) >= 1,
                    "andesite collected: " + c.inventory().countItem(Items.POLISHED_ANDESITE));
        });
    }

    @GameTest(maxTicks = 1600)
    public void minesWholeLogColumn(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int y = 2; y <= 9; y++) {
            h.setBlock(new BlockPos(8, y, 8), Blocks.CHERRY_LOG);
        }
        CompanionEntity c = walker(h, 2, 2, 2);
        // Dirt to pillar up beside the column: the upper logs are out of reach from the ground.
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        MineBlocksTask task = new MineBlocksTask(BlockMatcher.parse("minecraft:cherry_log").orElseThrow(), 8, 32);
        Outcome out = new Outcome();
        c.taskRunner().setListener(out);
        c.taskRunner().start(task, "mine_blocks");
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.SUCCESS, "mine_blocks did not finish: " + out.why());
            int left = 0;
            for (int y = 2; y <= 9; y++) {
                if (h.getLevel().getBlockState(h.absolutePos(new BlockPos(8, y, 8))).is(Blocks.CHERRY_LOG)) {
                    left++;
                }
            }
            h.assertTrue(left == 0, "logs left: " + left);
            // The drops of a tall column land on the ground below it, where the mining task's sweep at the last
            // broken block collects them; the mined count is what the task promises.
            h.assertTrue(c.inventory().countItem(Items.CHERRY_LOG) >= 1,
                    "logs collected: " + c.inventory().countItem(Items.CHERRY_LOG));
        });
    }

    @GameTest(maxTicks = 800)
    public void gatheringLeavesProtectedBlocks(GameTestHelper h) {
        plankPitWithDirtMound(h);
        CompanionEntity c = walker(h, 1, 6, 8);
        // The mound is protected as a spawn region would be: gathering must leave it alone, so the pit stays uncrossable.
        BlockPos mound = h.absolutePos(new BlockPos(2, 6, 2));
        c.hands().overrideProtection(pos -> pos.getX() >= mound.getX() && pos.getX() <= mound.getX() + 2
                && pos.getZ() >= mound.getZ() && pos.getZ() <= mound.getZ() + 2
                && pos.getY() >= mound.getY() && pos.getY() <= mound.getY() + 1);
        Outcome out = new Outcome();
        walkTo(c, h, out, 12.5, 6, 8.5);
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.FAILED, "go_to should have refused: " + out.why());
            int left = 0;
            for (int x = 2; x <= 4; x++) {
                for (int z = 2; z <= 4; z++) {
                    for (int y = 6; y <= 7; y++) {
                        if (h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, y, z))).is(Blocks.DIRT)) {
                            left++;
                        }
                    }
                }
            }
            h.assertTrue(left == 18, "protected dirt was broken: " + left + " of 18 left");
        });
    }

    @GameTest(maxTicks = 1200)
    public void goToWalksAroundPlankWall(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        for (int z = 1; z <= 12; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(8, y, z, Blocks.OAK_PLANKS);
            }
        }
        CompanionEntity c = walker(h, 2, 2, 8);
        Outcome out = new Outcome();
        walkTo(c, h, out, 13.5, 2, 8.5);
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.SUCCESS, "go_to did not finish: " + out.why());
            for (int z = 1; z <= 12; z++) {
                for (int y = 2; y <= 4; y++) {
                    assertBlock(h, 8, y, z, Blocks.OAK_PLANKS, "wall");
                }
            }
        });
    }

    @GameTest(maxTicks = 1200)
    public void protectedWallIsWalkedAround(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        int wallX = h.absolutePos(new BlockPos(8, 2, 0)).getX();
        for (int z = 1; z <= 12; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(8, y, z, Blocks.STONE);
            }
        }
        CompanionEntity c = walker(h, 2, 2, 8);
        c.hands().overrideProtection(p -> p.getX() == wallX);
        Outcome out = new Outcome();
        walkTo(c, h, out, 13.5, 2, 8.5);
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.SUCCESS, "go_to did not finish: " + out.why());
            for (int z = 1; z <= 12; z++) {
                for (int y = 2; y <= 4; y++) {
                    assertBlock(h, 8, y, z, Blocks.STONE, "protected wall");
                }
            }
        });
    }

    @GameTest(maxTicks = 1200)
    public void tunnelBesideLavaRefused(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        // A stone wall at x=8 with a sealed lava seam right behind it: breaking the wall would open onto lava.
        for (int z = 0; z <= 12; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(8, y, z, Blocks.STONE);
                h.setBlock(10, y, z, Blocks.STONE);
            }
            h.setBlock(9, 3, z, Blocks.STONE);
            h.setBlock(9, 2, z, z == 0 || z == 12 ? Blocks.STONE : Blocks.LAVA);
        }
        CompanionEntity c = walker(h, 2, 2, 8);
        h.onEachTick(() -> h.assertFalse(c.isOnFire(), "the companion caught fire"));
        Outcome out = new Outcome();
        walkTo(c, h, out, 13.5, 2, 8.5);
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.SUCCESS, "go_to did not finish: " + out.why());
            for (int z = 1; z <= 11; z++) {
                assertBlock(h, 9, 2, z, Blocks.LAVA, "sealed lava");
            }
        });
    }

    @GameTest(maxTicks = 1200)
    public void goToFollowsShiftingTarget(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = walker(h, 2, 2, 2);
        MoveHelper mover = new MoveHelper(1.0, 1.5);
        Vec3 finalTarget = h.absoluteVec(new Vec3(14.5, 2, 2.5));
        h.succeedWhen(() -> {
            int step = Math.min(4, (int) (h.getTick() / 20));
            double x = 6 + 2.0 * step;
            mover.tick(c, h.absoluteVec(new Vec3(x + 0.5, 2, 2.5)));
            double distance = c.position().distanceTo(finalTarget);
            h.assertTrue(h.getTick() >= 100 && distance <= 1.75, "still " + distance + " blocks away on tick " + h.getTick());
        });
    }
}
