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
        GameTestSupport.platform(h, KIT_SIZE);
        ServerPlayer owner = GameTestSupport.onlinePlayer(h, "resource-owner", 27.5, 2, 4.5, true)
                .player();
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        companion.setOwner(owner.getUUID());

        List<List<BlockPos>> trees = List.of(birchTree(h, 10, 10), birchTree(h, 16, 10));
        List<BlockPos> stone = new ArrayList<>();
        for (int x = 22; x <= 24; x++) {
            for (int z = 12; z <= 17; z++) {
                BlockPos pos = new BlockPos(x, 2, z);
                h.setBlock(pos, Blocks.STONE);
                stone.add(pos);
            }
        }

        BlockPos stationSpot = new BlockPos(8, 2, 14);
        h.setBlock(stationSpot, Blocks.AIR);
        List<BlockPos> deliveryObstruction = List.of(new BlockPos(21, 2, 4), new BlockPos(21, 3, 4));
        deliveryObstruction.forEach(pos -> h.setBlock(pos, Blocks.COBBLESTONE));

        return new BirchKit(companion, owner, trees, stone, stationSpot, new BlockPos(27, 2, 4), deliveryObstruction);
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

    private static List<BlockPos> birchTree(GameTestHelper h, int x, int z) {
        List<BlockPos> logs = new ArrayList<>();
        for (int y = 2; y <= 6; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            h.setBlock(pos, Blocks.BIRCH_LOG);
            logs.add(pos);
        }
        for (int leafX = x - 2; leafX <= x + 2; leafX++) {
            for (int leafZ = z - 2; leafZ <= z + 2; leafZ++) {
                if (Math.abs(leafX - x) == 2 && Math.abs(leafZ - z) == 2) continue;
                BlockPos pos = new BlockPos(leafX, 7, leafZ);
                if (h.getBlockState(pos).isAir()) {
                    h.setBlock(pos, Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                }
            }
        }
        return logs;
    }
}
