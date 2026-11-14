package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

/** Shared world shapes for the resource-order GameTests. */
final class ResourceWorkFixture {
    private static final int KIT_SIZE = 32;

    private ResourceWorkFixture() {}

    record BirchKit(
            CompanionEntity companion,
            ServerPlayer owner,
            List<List<BlockPos>> trees,
            List<BlockPos> stoneSource,
            BlockPos stationSpot,
            BlockPos ownerPickup,
            List<BlockPos> deliveryObstruction) {
        BirchKit {
            trees = trees.stream().map(List::copyOf).toList();
            stoneSource = List.copyOf(stoneSource);
            deliveryObstruction = List.copyOf(deliveryObstruction);
        }

        List<BlockPos> allTreeLogs() {
            return trees.stream().flatMap(List::stream).toList();
        }

        List<BlockPos> allTreeLeaves() {
            List<BlockPos> leaves = new ArrayList<>();
            for (List<BlockPos> tree : trees) {
                BlockPos base = tree.getFirst();
                int canopyY = tree.getLast().getY() + 1;
                for (int x = base.getX() - 2; x <= base.getX() + 2; x++) {
                    for (int z = base.getZ() - 2; z <= base.getZ() + 2; z++) {
                        if (Math.abs(x - base.getX()) == 2 && Math.abs(z - base.getZ()) == 2) continue;
                        leaves.add(new BlockPos(x, canopyY, z));
                    }
                }
            }
            return List.copyOf(leaves);
        }
    }

    record UndergroundReturn(
            BlockPos anchor,
            BlockPos stoneTarget,
            List<BlockPos> routeCells,
            List<BlockPos> routeSupports,
            BlockPos obstruction,
            int surfaceY) {
        UndergroundReturn {
            routeCells = List.copyOf(routeCells);
            routeSupports = List.copyOf(routeSupports);
        }
    }

    static BirchKit birchKit(GameTestHelper h) {
        return birchKit(h, 1);
    }

    static BirchKit birchKit(GameTestHelper h, int surfaceY) {
        return birchKit(h, surfaceY, 5);
    }

    static BirchKit birchKit(GameTestHelper h, int surfaceY, int treeHeight) {
        GameTestSupport.platformAt(h, surfaceY, KIT_SIZE);
        for (int x = 0; x < KIT_SIZE; x++) {
            for (int z = 0; z < KIT_SIZE; z++) {
                h.setBlock(x, surfaceY, z, Blocks.STONE_BRICKS);
            }
        }
        int feetY = surfaceY + 1;
        ServerPlayer owner = GameTestSupport.onlinePlayer(h, "resource-owner", 27.5, feetY, 4.5, true)
                .player();
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, feetY, 3);
        companion.setOwner(owner.getUUID());

        List<List<BlockPos>> trees =
                List.of(birchTree(h, 10, feetY, 10, treeHeight), birchTree(h, 16, feetY, 10, treeHeight));
        List<BlockPos> stone = new ArrayList<>();
        for (int x : new int[] {4, 7, 13, 16}) {
            for (int z : new int[] {2, 5, 8, 11, 14}) {
                if (((x == 13 || x == 16) && z == 11) || (x == 13 && z == 14)) continue;
                BlockPos pos = new BlockPos(x, feetY, z);
                h.setBlock(pos, Blocks.STONE);
                stone.add(pos);
            }
        }
        BlockPos openStone = new BlockPos(4, feetY, 17);
        h.setBlock(openStone, Blocks.STONE);
        stone.add(openStone);

        BlockPos stationSpot = new BlockPos(2, feetY, 14);
        h.setBlock(stationSpot, Blocks.AIR);
        List<BlockPos> deliveryObstruction = List.of(new BlockPos(21, feetY, 4), new BlockPos(21, feetY + 1, 4));
        deliveryObstruction.forEach(pos -> h.setBlock(pos, Blocks.COBBLESTONE));

        return new BirchKit(
                companion, owner, trees, stone, stationSpot, new BlockPos(27, feetY, 4), deliveryObstruction);
    }

    static UndergroundReturn undergroundReturn(GameTestHelper h) {
        int surfaceY = 200;
        GameTestSupport.platformAt(h, surfaceY, 24);
        for (int x = 4; x <= 12; x++) {
            for (int z = 4; z <= 6; z++) {
                for (int y = surfaceY - 8; y <= surfaceY + 4; y++) {
                    h.setBlock(x, y, z, Blocks.STONE);
                }
            }
        }

        List<BlockPos> routeCells = new ArrayList<>();
        List<BlockPos> routeSupports = new ArrayList<>();
        int length = 7;
        for (int step = 0; step < length; step++) {
            for (int dy = 0; dy < 2; dy++) {
                BlockPos cell = new BlockPos(4 + step, surfaceY + 1 - step + dy, 5);
                h.setBlock(cell, Blocks.AIR);
                routeCells.add(cell);
            }
            routeSupports.add(new BlockPos(4 + step, surfaceY - step, 5));
        }

        BlockPos stoneTarget = new BlockPos(11, surfaceY + 1 - length, 4);
        h.setBlock(stoneTarget, Blocks.IRON_ORE);
        return new UndergroundReturn(
                new BlockPos(4, surfaceY + 1, 5),
                stoneTarget,
                routeCells,
                routeSupports,
                new BlockPos(8, surfaceY - 1, 5),
                surfaceY);
    }

    static void removeDeliveryObstruction(GameTestHelper h, BirchKit fixture) {
        fixture.deliveryObstruction().forEach(pos -> h.setBlock(pos, Blocks.AIR));
    }

    private static List<BlockPos> birchTree(GameTestHelper h, int x, int baseY, int z, int height) {
        List<BlockPos> logs = new ArrayList<>();
        for (int y = baseY; y < baseY + height; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            h.setBlock(pos, Blocks.BIRCH_LOG);
            logs.add(pos);
        }
        for (int leafX = x - 2; leafX <= x + 2; leafX++) {
            for (int leafZ = z - 2; leafZ <= z + 2; leafZ++) {
                if (Math.abs(leafX - x) == 2 && Math.abs(leafZ - z) == 2) continue;
                BlockPos pos = new BlockPos(leafX, baseY + height, leafZ);
                if (h.getBlockState(pos).isAir()) {
                    h.setBlock(pos, Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                }
            }
        }
        return logs;
    }
}
