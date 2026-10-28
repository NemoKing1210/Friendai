package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.LogEntry;
import dev.friendai.debug.LogKind;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.movement.Facing;
import dev.friendai.pathing.Cell;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.GoalPoint;
import dev.friendai.pathing.Move;
import dev.friendai.pathing.Node;
import dev.friendai.pathing.Path;
import dev.friendai.pathing.PathStep;
import dev.friendai.pathing.PathingConfig;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.FollowTask;
import dev.friendai.task.builtin.GoToTask;
import dev.friendai.task.builtin.MineBlocksTask;
import dev.friendai.task.mining.BlockBreaker;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.task.mining.BreakProgress;
import dev.friendai.task.nav.LevelCellView;
import dev.friendai.task.nav.MaterialPicker;
import dev.friendai.task.nav.MoveHelper;
import dev.friendai.task.nav.Navigator;
import dev.friendai.task.nav.PathExecutor;
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

        /** Whether the task logged that it went to gather bridge material; progress is logged, never announced. */
        boolean gathered;

        @Override
        public void onDebug(String text) {
            gathered |= text.startsWith("gathering ");
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
        c.setPathBounds(new AABB(h.absoluteVec(new Vec3(-1, -1, -1)), h.absoluteVec(new Vec3(17, 12, 17))));
        return c;
    }

    private static void walkTo(CompanionEntity c, GameTestHelper h, Outcome out, double x, double y, double z) {
        c.taskRunner().setListener(out);
        c.taskRunner().start(new GoToTask(h.absoluteVec(new Vec3(x, y, z))), "go_to");
    }

    private static void assertBlock(
            GameTestHelper h, int x, int y, int z, net.minecraft.world.level.block.Block block, String what) {
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
        h.setBlock(new BlockPos(6, 2, 5), Blocks.RAIL);

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

        h.assertFalse(cellAt(bare, h, 6, 2, 5).placeable(), "a rail is not replaceable");

        BlockPos logPos = h.absolutePos(new BlockPos(4, 2, 5));
        BlockState log = h.getLevel().getBlockState(logPos);
        int expected = BreakProgress.ticksToBreak(log.getDestroySpeed(h.getLevel(), logPos), 1f, true)
                + CostModel.DEFAULTS.logPenalty();
        h.assertTrue(
                cellAt(bare, logPos).breakTicks() == expected,
                "log ticks " + cellAt(bare, logPos).breakTicks() + " != " + expected);

        int bareStone = cellAt(bare, h.absolutePos(new BlockPos(2, 2, 2))).breakTicks();
        c.inventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
        LevelCellView armed = new LevelCellView(c, PathingConfig.DEFAULTS, CostModel.DEFAULTS);
        h.assertTrue(
                cellAt(armed, h.absolutePos(new BlockPos(2, 2, 2))).breakTicks() < bareStone,
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
        h.assertTrue(
                c.inventory().countItem(Items.DIRT) == 4,
                "dirt left " + c.inventory().countItem(Items.DIRT));
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
        CompanionSessions brains = FriendaiServerState.get(server).sessions();
        CompanionEntity c = CompanionManager.get(server).summon(player);
        // The task listener is installed with the owner's brain entry, as it already is for notices.
        brains.currentPrompt(player.getUUID());
        c.taskRunner().debug("path test");
        List<LogEntry> log = brains.debug().logOf(player.getUUID()).after(0, 50);
        h.assertTrue(
                log.stream().anyMatch(e -> e.kind() == LogKind.TASK && e.text().contains("path test")),
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

    /** A 6-deep pit walled in unbreakable planks with a dirt mound on the near side, the only material around. */
    private static void plankPitWithDirtMound(GameTestHelper h) {
        GameTestSupport.platformAt(h, 1, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 2; y <= 7; y++) {
                    boolean pit = x >= 6 && x <= 10;
                    // Planks are blacklisted, so the only way across the 4-deep pit is to build over it.
                    h.setBlock(x, y, z, pit ? Blocks.AIR : Blocks.OAK_PLANKS);
                }
            }
        }
        for (int x = 6; x <= 10; x++) {
            for (int z = 0; z < 16; z++) {
                h.setBlock(x, 1, z, Blocks.LAVA);
            }
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = 7; z <= 9; z++) {
                for (int y = 8; y <= 9; y++) {
                    h.setBlock(x, y, z, Blocks.DIRT);
                }
            }
        }
    }

    @GameTest(maxTicks = 800)
    public void goToGathersDirtThenBridges(GameTestHelper h) {
        plankPitWithDirtMound(h);
        CompanionEntity c = walker(h, 5, 8, 8);
        c.setPathBounds(new AABB(h.absoluteVec(new Vec3(0, 0, 5)), h.absoluteVec(new Vec3(16, 14, 11))));
        for (int z = 7; z <= 9; z++) {
            Vec3 viewpoint = h.absoluteVec(new Vec3(5.5, 8, z + 0.5));
            c.teleportTo(viewpoint.x, viewpoint.y, viewpoint.z);
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(4, 9, z))),
                    "the dirt mound edge at z=" + z + " must be directly visible before it can be chosen");
        }
        Vec3 start = h.absoluteVec(new Vec3(5.5, 8, 8.5));
        c.teleportTo(start.x, start.y, start.z);
        Outcome out = new Outcome();
        walkTo(c, h, out, 14.5, 8, 8.5);
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.SUCCESS, "go_to did not finish: " + out.why());
            int bridged = 0;
            for (int x = 6; x <= 10; x++) {
                for (int y = 2; y <= 14; y++) {
                    for (int z = 5; z <= 11; z++) {
                        BlockState state = h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, y, z)));
                        if (state.is(Blocks.DIRT)) {
                            bridged++;
                        }
                    }
                }
            }
            h.assertTrue(
                    bridged >= 1,
                    "bridged " + bridged + " cells of the pit row; dirt="
                            + c.inventory().countItem(Items.DIRT) + ", notices=" + out.notices + ", companion="
                            + c.blockPosition() + "; paths: " + out.debug);
            h.assertTrue(out.gathered, "no gathering log line, last: " + out.debug);
            h.assertTrue(
                    out.notices.stream().noneMatch(text -> text.startsWith("gathering ")),
                    "gathering must not wake the brain: " + out.notices);
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
        h.assertTrue(
                GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(8, 2, 8))),
                "andesite should be directly visible beneath the dirt cap");
        MineBlocksTask task = new MineBlocksTask(
                BlockMatcher.parse("minecraft:polished_andesite").orElseThrow(), 1, 32);
        Outcome out = new Outcome();
        c.taskRunner().setListener(out);
        c.taskRunner().start(task, "mine_blocks");
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.SUCCESS, "mine_blocks did not finish: " + out.why());
            BlockState left = h.getLevel().getBlockState(h.absolutePos(new BlockPos(8, 2, 8)));
            h.assertTrue(left.isAir(), "andesite still there: " + left);
            h.assertTrue(
                    c.inventory().countItem(Items.POLISHED_ANDESITE) >= 1,
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
        for (int y = 2; y <= 9; y++) {
            h.assertTrue(
                    GameTestSupport.observeVisible(h, c, h.absolutePos(new BlockPos(8, y, 8))),
                    "log at y=" + y + " should be directly visible");
        }
        // Dirt to pillar up beside the column: the upper logs are out of reach from the ground.
        c.inventory().addItem(new ItemStack(Items.DIRT, 16));
        MineBlocksTask task =
                new MineBlocksTask(BlockMatcher.parse("minecraft:cherry_log").orElseThrow(), 8, 32);
        Outcome out = new Outcome();
        c.taskRunner().setListener(out);
        c.taskRunner().start(task, "mine_blocks");
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.SUCCESS, "mine_blocks did not finish: " + out.why());
            int left = 0;
            for (int y = 2; y <= 9; y++) {
                if (h.getLevel()
                        .getBlockState(h.absolutePos(new BlockPos(8, y, 8)))
                        .is(Blocks.CHERRY_LOG)) {
                    left++;
                }
            }
            h.assertTrue(left == 0, "logs left: " + left);
            // The drops of a tall column land on the ground below it, where the mining task's sweep at the last
            // broken block collects them; the mined count is what the task promises.
            h.assertTrue(
                    c.inventory().countItem(Items.CHERRY_LOG) >= 1,
                    "logs collected: " + c.inventory().countItem(Items.CHERRY_LOG));
        });
    }

    @GameTest(maxTicks = 800)
    public void gatheringLeavesProtectedBlocks(GameTestHelper h) {
        plankPitWithDirtMound(h);
        CompanionEntity c = walker(h, 1, 8, 8);
        // The mound is protected as a spawn region would be: gathering must leave it alone, so the pit stays
        // uncrossable.
        BlockPos mound = h.absolutePos(new BlockPos(2, 8, 7));
        c.hands()
                .overrideProtection(pos -> pos.getX() >= mound.getX()
                        && pos.getX() <= mound.getX() + 2
                        && pos.getZ() >= mound.getZ()
                        && pos.getZ() <= mound.getZ() + 2
                        && pos.getY() >= mound.getY()
                        && pos.getY() <= mound.getY() + 1);
        Outcome out = new Outcome();
        walkTo(c, h, out, 12.5, 8, 8.5);
        h.succeedWhen(() -> {
            h.assertTrue(out.status == TaskStatus.FAILED, "go_to should have refused: " + out.why());
            int left = 0;
            for (int x = 2; x <= 4; x++) {
                for (int z = 7; z <= 9; z++) {
                    for (int y = 8; y <= 9; y++) {
                        if (h.getLevel()
                                .getBlockState(h.absolutePos(new BlockPos(x, y, z)))
                                .is(Blocks.DIRT)) {
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
            h.assertTrue(
                    h.getTick() >= 100 && distance <= 1.75,
                    "still " + distance + " blocks away on tick " + h.getTick());
        });
    }

    @GameTest(maxTicks = 400)
    public void goToArrivesOnACarpet(GameTestHelper h) {
        int floor = 150;
        GameTestSupport.platformAt(h, floor, 12);
        for (int x = 6; x <= 8; x++) {
            h.setBlock(new BlockPos(x, floor + 1, 2), Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.WHITE));
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        Navigator navigator = new Navigator(c);
        Vec3 target = h.absoluteVec(new Vec3(7.5, floor + 2, 2.5));
        navigator.setGoal(new GoalPoint(target.x, target.y, target.z, 0.3, 0.5));
        TaskContext context = new TaskContext(c, h.getLevel());
        Navigator.State[] state = {Navigator.State.SEARCHING};
        h.onEachTick(() -> state[0] = navigator.tick(context));
        h.succeedWhen(() -> {
            h.assertTrue(state[0] == Navigator.State.ARRIVED, "not on carpet: " + state[0] + " at " + c.position());
            h.assertTrue(
                    Math.abs(c.getY() - (target.y - 0.9375)) < 0.1, "feet should rest on the carpet: " + c.position());
        });
    }

    @GameTest(maxTicks = 500)
    public void goToClimbsAClimbableColumn(GameTestHelper h) {
        int floor = 160;
        GameTestSupport.platformAt(h, floor, 12);
        for (int y = floor + 1; y <= floor + 4; y++) {
            h.setBlock(new BlockPos(8, y, 2), Blocks.SCAFFOLDING);
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        Navigator navigator = new Navigator(c);
        Vec3 target = h.absoluteVec(new Vec3(8.5, floor + 5, 2.5));
        navigator.setGoal(new GoalPoint(target.x, target.y, target.z, 0.3, 0.5));
        TaskContext context = new TaskContext(c, h.getLevel());
        Navigator.State[] state = {Navigator.State.SEARCHING};
        h.onEachTick(() -> state[0] = navigator.tick(context));
        h.succeedWhen(() -> {
            h.assertTrue(state[0] == Navigator.State.ARRIVED, "did not climb: " + state[0] + " at " + c.position());
            h.assertTrue(c.getY() >= target.y - 0.5, "did not reach the column top: " + c.position());
        });
    }

    @GameTest(maxTicks = 500)
    public void goToSwimsAcrossPool(GameTestHelper h) {
        int floor = 170;
        GameTestSupport.platformAt(h, floor, 14);
        for (int x = 5; x <= 9; x++) {
            for (int z = 1; z <= 5; z++) {
                h.setBlock(new BlockPos(x, floor + 1, z), Blocks.WATER);
                h.setBlock(new BlockPos(x, floor + 2, z), Blocks.WATER);
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 3);
        Outcome out = new Outcome();
        walkTo(c, h, out, 11.5, floor + 1, 3.5);
        h.succeedWhen(() -> h.assertTrue(
                out.status == TaskStatus.SUCCESS, "did not swim across: " + out.why() + " at " + c.blockPosition()));
    }

    @GameTest(maxTicks = 300)
    public void goToSwimsThroughASubmergedTunnel(GameTestHelper h) {
        int floor = 172;
        GameTestSupport.platformAt(h, floor, 16);
        for (int x = 5; x <= 10; x++) {
            h.setBlock(new BlockPos(x, floor + 1, 3), Blocks.WATER);
            h.setBlock(new BlockPos(x, floor + 2, 3), Blocks.WATER);
            h.setBlock(new BlockPos(x, floor + 3, 3), Blocks.STONE);
            for (int y = floor + 1; y <= floor + 2; y++) {
                h.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                h.setBlock(new BlockPos(x, y, 4), Blocks.STONE);
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 3);
        Outcome out = new Outcome();
        walkTo(c, h, out, 13.5, floor + 1, 3.5);
        h.succeedWhen(() -> h.assertTrue(
                out.status == TaskStatus.SUCCESS, "did not swim through: " + out.why() + " at " + c.position()));
    }

    @GameTest(maxTicks = 900)
    public void followNeverTeleports(GameTestHelper h) {
        int floor = 140;
        GameTestSupport.platformAt(h, floor, 32);
        for (int z = 1; z <= 30; z++) {
            if (z == 28) {
                continue;
            }
            for (int y = floor + 1; y <= floor + 3; y++) {
                h.setBlock(new BlockPos(12, y, z), Blocks.STONE);
            }
        }
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 28.0, floor + 1, 2.5);
        owner.setOnGround(true);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        c.setOwner(owner.getUUID());
        c.taskRunner().start(new FollowTask(true), "follow");
        double[] last = {c.getX(), c.getY(), c.getZ()};
        double[] biggest = {0};
        h.onEachTick(() -> {
            double step = Math.sqrt(Math.pow(c.getX() - last[0], 2)
                    + Math.pow(c.getY() - last[1], 2)
                    + Math.pow(c.getZ() - last[2], 2));
            biggest[0] = Math.max(biggest[0], step);
            last[0] = c.getX();
            last[1] = c.getY();
            last[2] = c.getZ();
            h.assertTrue(biggest[0] < 1.5, "the companion jumped " + biggest[0] + " blocks in one tick");
        });
        h.succeedWhen(() -> h.assertTrue(
                c.position().distanceTo(owner.position()) <= 5.0,
                "still " + c.position().distanceTo(owner.position()) + " blocks away"));
    }

    @GameTest(maxTicks = 600)
    public void followDoesNotPillarToAnAirborneOwner(GameTestHelper h) {
        int floor = 90;
        GameTestSupport.platformAt(h, floor, 20);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 10.5, floor + 20, 10.5);
        owner.setOnGround(false);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, floor + 1, 2);
        c.setOwner(owner.getUUID());
        c.taskRunner().start(new FollowTask(true), "follow");
        double ceiling = h.absolutePos(new BlockPos(0, floor + 4, 0)).getY();
        h.onEachTick(() ->
                h.assertTrue(c.getY() < ceiling, "the companion climbed to " + c.getY() + " toward a flying owner"));
        h.succeedWhen(() -> {
            double dx = c.getX() - owner.getX();
            double dz = c.getZ() - owner.getZ();
            h.assertTrue(Math.sqrt(dx * dx + dz * dz) <= 5.0, "not standing under the owner: " + c.position());
        });
    }

    @GameTest(maxTicks = 600)
    public void blockBreakerWalksToItsViewSpot(GameTestHelper h) {
        int floor = 110;
        GameTestSupport.platformAt(h, floor, 14);
        h.setBlock(new BlockPos(8, floor + 1, 5), Blocks.DIRT);
        for (int y = floor + 1; y <= floor + 3; y++) {
            h.setBlock(new BlockPos(7, y, 5), Blocks.STONE);
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, floor + 1, 5);
        BlockBreaker breaker =
                new BlockBreaker(h.absolutePos(new BlockPos(8, floor + 1, 5)), state -> state.is(Blocks.DIRT), true);
        TaskContext context = new TaskContext(c, h.getLevel());
        double[] last = {c.getX(), c.getY(), c.getZ()};
        double[] biggest = {0};
        boolean[] done = {false};
        h.onEachTick(() -> {
            BlockBreaker.State state = breaker.tick(context);
            double step = Math.sqrt(Math.pow(c.getX() - last[0], 2)
                    + Math.pow(c.getY() - last[1], 2)
                    + Math.pow(c.getZ() - last[2], 2));
            biggest[0] = Math.max(biggest[0], step);
            last[0] = c.getX();
            last[1] = c.getY();
            last[2] = c.getZ();
            h.assertTrue(biggest[0] < 1.5, "the companion jumped " + biggest[0] + " blocks to a view spot");
            h.assertTrue(
                    state == BlockBreaker.State.WORKING
                            || state == BlockBreaker.State.BROKEN
                            || state == BlockBreaker.State.GONE,
                    "the breaker gave up: " + state);
            done[0] = state == BlockBreaker.State.BROKEN || state == BlockBreaker.State.GONE;
        });
        h.succeedWhen(() -> h.assertTrue(done[0], "the breaker has not finished"));
    }

    @GameTest(maxTicks = 400)
    public void goToSwimsUpAWaterColumn(GameTestHelper h) {
        int floor = 175;
        GameTestSupport.platformAt(h, floor, 12);
        for (int y = floor + 1; y <= floor + 5; y++) {
            for (int x = 7; x <= 9; x++) {
                for (int z = 1; z <= 3; z++) {
                    h.setBlock(x, y, z, x == 8 && z == 2 ? Blocks.WATER : Blocks.STONE);
                }
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 8, floor + 1, 2);
        Vec3 min = h.absoluteVec(new Vec3(8, floor, 2));
        Vec3 max = h.absoluteVec(new Vec3(9, floor + 7, 3));
        c.setPathBounds(new AABB(min, max));
        Vec3 target = h.absoluteVec(new Vec3(8.5, floor + 4, 2.5));
        Navigator navigator = new Navigator(c, false);
        navigator.setGoal(new GoalPoint(target.x, target.y, target.z, 0.3, 0.4));
        TaskContext context = new TaskContext(c, h.getLevel());
        Navigator.State[] state = {Navigator.State.SEARCHING};
        h.onEachTick(() -> state[0] = navigator.tick(context));
        h.succeedWhen(() -> {
            h.assertTrue(state[0] == Navigator.State.ARRIVED, "did not swim up: " + state[0] + " at " + c.position());
            h.assertTrue(c.getY() >= target.y - 0.5, "did not reach the upper water cell: " + c.position());
        });
    }

    @GameTest
    public void executorRejectsADestinationBlockedAfterPlanning(GameTestHelper h) {
        GameTestSupport.platformAt(h, 185, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 186, 2);
        BlockPos from = h.absolutePos(new BlockPos(2, 186, 2));
        BlockPos to = from.east();
        PathStep step = new PathStep(
                new Node(from.getX(), from.getY(), from.getZ()),
                new Node(to.getX(), to.getY(), to.getZ()),
                Move.WALK,
                List.of(),
                List.of(),
                4.6,
                0);
        PathExecutor executor = new PathExecutor(
                new Path(List.of(step), step.cost(), 0, 0, false),
                new MaterialPicker(PathingConfig.DEFAULTS),
                PathingConfig.DEFAULTS);
        TaskContext context = new TaskContext(c, h.getLevel());
        executor.tick(context);
        h.setBlock(new BlockPos(3, 186, 2), Blocks.STONE);
        h.assertTrue(
                executor.tick(context) == PathExecutor.State.FAILED,
                "an occupied destination must fail before movement starts");
        h.assertFalse(c.pathing(), "a failed step must clear pathing");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void moveHelperResetStopsTheRoute(GameTestHelper h) {
        GameTestSupport.platformAt(h, 190, 14);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 191, 2);
        MoveHelper mover = new MoveHelper(1.0, 0.3);
        Vec3 target = h.absoluteVec(new Vec3(11.5, 191, 2.5));
        h.succeedWhen(() -> {
            mover.tick(c, target);
            h.assertTrue(c.pathing(), "wait for an executing path");
            mover.reset(new TaskContext(c, h.getLevel()));
            h.assertFalse(c.pathing(), "reset must immediately stop the navigator");
        });
    }

    @GameTest(maxTicks = 200)
    public void stoppingGoToClearsPathingImmediately(GameTestHelper h) {
        GameTestSupport.platformAt(h, 195, 14);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 196, 2);
        c.taskRunner().start(new GoToTask(h.absoluteVec(new Vec3(11.5, 196, 2.5))), "go_to");
        h.succeedWhen(() -> {
            h.assertTrue(c.pathing(), "wait for an executing path");
            c.taskRunner().stop("test stop");
            h.assertFalse(c.pathing(), "stopping the task must immediately stop the navigator");
        });
    }

    @GameTest(maxTicks = 200)
    public void moveHelperArrivesOnlyWithinItsPhysicalRadius(GameTestHelper h) {
        GameTestSupport.platformAt(h, 200, 8);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 201, 2);
        Vec3 target = h.absoluteVec(new Vec3(2.5, 201, 2.5));
        c.setPos(target.x - 0.45, target.y, target.z);
        MoveHelper mover = new MoveHelper(1.0, 0.1);
        h.succeedWhen(() -> {
            MoveHelper.State state = mover.tick(c, target);
            double distance = Math.hypot(c.getX() - target.x, c.getZ() - target.z);
            if (state == MoveHelper.State.ARRIVED) {
                h.assertTrue(distance <= 0.1, "reported arrival " + distance + " blocks from the point");
            }
            h.assertTrue(state == MoveHelper.State.ARRIVED, "still centering: " + state + " distance " + distance);
        });
    }

    /** A walled corridor along z=11: high ground, a one-block drop at x=6, a three-block drop at x=12. */
    private static void descentCorridor(GameTestHelper h, int base) {
        GameTestSupport.platformAt(h, base, 24);
        for (int z = 9; z <= 13; z++) {
            for (int x = 0; x < 24; x++) {
                int top = x <= 5 ? base + 4 : x <= 11 ? base + 3 : base;
                for (int y = base + 1; y <= top; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
                for (int y = top + 1; y <= top + 5; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        for (int x = 0; x < 24; x++) {
            for (int y = base; y <= base + 9; y++) {
                h.setBlock(new BlockPos(x, y, 9), Blocks.STONE);
                h.setBlock(new BlockPos(x, y, 13), Blocks.STONE);
            }
        }
    }

    /**
     * Walks the corridor and fails if the body ever turns more than 30 degrees in one tick. The reported value is
     * the largest turn seen, so a regression says how bad it got.
     */
    private static void walkCorridorAndCheckYaw(GameTestHelper h, int base, double targetX, double targetY) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, base + 5, 11);
        Vec3 target = h.absoluteVec(new Vec3(targetX, targetY, 11.5));
        Navigator navigator = new Navigator(c);
        navigator.setGoal(new GoalPoint(target.x, target.y, target.z, 0.5, 0.5));
        TaskContext context = new TaskContext(c, h.getLevel());
        Navigator.State[] state = {Navigator.State.SEARCHING};
        float[] lastYaw = {c.getYRot()};
        float[] maxStep = {0};
        long[] arrivedAt = {-1};
        h.onEachTick(() -> {
            state[0] = navigator.tick(context);
            float delta = Math.abs(net.minecraft.util.Mth.wrapDegrees(c.getYRot() - lastYaw[0]));
            maxStep[0] = Math.max(maxStep[0], delta);
            lastYaw[0] = c.getYRot();
            if (state[0] == Navigator.State.ARRIVED && arrivedAt[0] < 0) {
                arrivedAt[0] = h.getTick();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(state[0] == Navigator.State.ARRIVED, "not arrived: " + state[0] + " at " + c.position());
            // The policy caps at 30; vanilla's own rotlerp can add a fraction of a degree on top.
            h.assertTrue(maxStep[0] <= 31.0f, "turned " + maxStep[0] + " degrees in one tick");
            // A cap that stalls the walk would show up here: the 18-block corridor takes about 137 ticks.
            h.assertTrue(arrivedAt[0] <= 200, "took " + arrivedAt[0] + " ticks down the corridor");
        });
    }

    @GameTest(maxTicks = 400)
    public void walksDownOneBlockWithoutTurningBack(GameTestHelper h) {
        int base = 210;
        descentCorridor(h, base);
        walkCorridorAndCheckYaw(h, base, 10.5, base + 4);
    }

    @GameTest(maxTicks = 600)
    public void walksDownThreeBlocksWithoutTurningBack(GameTestHelper h) {
        int base = 220;
        descentCorridor(h, base);
        walkCorridorAndCheckYaw(h, base, 20.5, base + 1);
    }

    @GameTest(maxTicks = 600)
    public void walksThirtyTwoBlocksInBudget(GameTestHelper h) {
        int base = 230;
        GameTestSupport.platformAt(h, base, 40);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, base + 1, 20);
        Vec3 target = h.absoluteVec(new Vec3(34.5, base + 1, 20.5));
        Navigator navigator = new Navigator(c);
        navigator.setGoal(new GoalPoint(target.x, target.y, target.z, 0.5, 0.5));
        TaskContext context = new TaskContext(c, h.getLevel());
        Navigator.State[] state = {Navigator.State.SEARCHING};
        long[] arrivedAt = {-1};
        h.onEachTick(() -> {
            state[0] = navigator.tick(context);
            if (state[0] == Navigator.State.ARRIVED && arrivedAt[0] < 0) {
                arrivedAt[0] = h.getTick();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(arrivedAt[0] > 0, "not arrived: " + state[0]);
            // A regression guard on the walking pace: the route is 32 blocks and a plain walk takes about 176
            // ticks. A pathing change that halves the effective speed fails here.
            h.assertTrue(arrivedAt[0] <= 200, "took " + arrivedAt[0] + " ticks for 32 blocks");
            h.assertFalse(navigator.lastSearch().isEmpty(), "the route must have been searched, not assumed");
        });
    }

    @GameTest(maxTicks = 800)
    public void twoTurnWalkKeepsTheBodyOnThePath(GameTestHelper h) {
        int base = 240;
        GameTestSupport.platformAt(h, base, 24);
        // A bedrock wall forces an east -> south -> east route: digging cannot pass it and the walker carries no
        // blocks, so it must walk around the wall's end.
        for (int z = 0; z <= 14; z++) {
            for (int y = base + 1; y <= base + 4; y++) {
                h.setBlock(10, y, z, Blocks.BEDROCK);
            }
        }
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, base + 1, 2);
        c.setYRot(0.0F);
        c.setYHeadRot(0.0F);
        Vec3 target = h.absoluteVec(new Vec3(18.5, base + 1, 2.5));
        Navigator navigator = new Navigator(c, false);
        navigator.setGoal(new GoalPoint(target.x, target.y, target.z, 0.5, 0.5));
        TaskContext context = new TaskContext(c, h.getLevel());
        Navigator.State[] state = {Navigator.State.SEARCHING};
        double[] lastX = {c.getX()};
        double[] lastZ = {c.getZ()};
        float[] lastTravel = {Float.NaN};
        int[] stable = {0};
        float[] maxError = {0.0F};
        float[] maxHeadError = {0.0F};
        float[] maxStep = {0.0F};
        int[] samples = {0};
        h.onEachTick(() -> {
            float before = c.getYRot();
            state[0] = navigator.tick(context);
            maxStep[0] = Math.max(maxStep[0], Math.abs(net.minecraft.util.Mth.wrapDegrees(c.getYRot() - before)));
            double dx = c.getX() - lastX[0];
            double dz = c.getZ() - lastZ[0];
            lastX[0] = c.getX();
            lastZ[0] = c.getZ();
            if (Math.hypot(dx, dz) > 0.02) {
                float travel = Facing.yawToward(dx, dz);
                if (!Float.isNaN(lastTravel[0])
                        && Math.abs(net.minecraft.util.Mth.wrapDegrees(travel - lastTravel[0])) < 10.0F) {
                    stable[0]++;
                } else {
                    stable[0] = 1;
                }
                lastTravel[0] = travel;
                if (stable[0] >= 8) {
                    maxError[0] =
                            Math.max(maxError[0], Math.abs(net.minecraft.util.Mth.wrapDegrees(c.getYRot() - travel)));
                    maxHeadError[0] = Math.max(
                            maxHeadError[0], Math.abs(net.minecraft.util.Mth.wrapDegrees(c.getYHeadRot() - travel)));
                    samples[0]++;
                }
            } else {
                stable[0] = 0;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(state[0] == Navigator.State.ARRIVED, "not arrived: " + state[0] + " at " + c.position());
            h.assertTrue(maxStep[0] <= 31.0F, "turned " + maxStep[0] + " degrees in one tick");
            h.assertTrue(samples[0] > 0, "the walk never held a steady direction");
            h.assertTrue(maxError[0] <= 45.0F, "the body trailed the path by " + maxError[0] + " degrees");
            h.assertTrue(maxHeadError[0] <= 45.0F, "the head trailed the path by " + maxHeadError[0] + " degrees");
        });
    }
}
