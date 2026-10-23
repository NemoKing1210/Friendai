package dev.friedai.test;

import dev.friedai.crafting.obtain.Source;
import dev.friedai.crafting.obtain.Tier;
import dev.friedai.crafting.obtain.ToolKind;
import dev.friedai.crafting.obtain.ToolNeed;
import dev.friedai.crafting.obtain.WorldFacts;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.obtain.DropIndex;
import dev.friedai.task.obtain.WorldScan;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

public class ObtainTests {
    private static Optional<Source.Mine> mineOf(DropIndex index, String item, String block) {
        List<Source> sources = index.sourcesOf(item);
        return sources.stream()
                .filter(source -> source instanceof Source.Mine mine && mine.block().equals(block))
                .map(source -> (Source.Mine) source)
                .findFirst();
    }

    @GameTest
    public void indexKnowsIronOre(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        Source.Mine mine = mineOf(index, "minecraft:raw_iron", "minecraft:iron_ore").orElse(null);
        h.assertTrue(mine != null, "no iron_ore source for raw_iron: " + index.sourcesOf("minecraft:raw_iron"));
        h.assertTrue(mine.tool().equals(new ToolNeed(ToolKind.PICKAXE, Tier.STONE)),
                "tool was " + mine.tool());
        h.assertTrue(mine.perBlock() >= 1, "yield was " + mine.perBlock());
        h.assertFalse(mine.surface(), "iron ore is not a surface block");
        h.assertTrue(mine.depthY() == 16, "depth was " + mine.depthY());
        h.succeed();
    }

    @GameTest
    public void indexKnowsStone(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        Source.Mine mine = mineOf(index, "minecraft:cobblestone", "minecraft:stone").orElse(null);
        h.assertTrue(mine != null, "no stone source for cobblestone: " + index.sourcesOf("minecraft:cobblestone"));
        h.assertTrue(mine.tool().equals(new ToolNeed(ToolKind.PICKAXE, Tier.WOOD)),
                "tool was " + mine.tool());
        h.assertTrue(mine.surface(), "stone is a surface block");
        h.succeed();
    }

    @GameTest
    public void indexKnowsLogsByHand(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        Source.Mine mine = mineOf(index, "minecraft:oak_log", "minecraft:oak_log").orElse(null);
        h.assertTrue(mine != null, "no oak_log source: " + index.sourcesOf("minecraft:oak_log"));
        h.assertTrue(mine.tool().equals(ToolNeed.HAND), "tool was " + mine.tool());
        h.assertTrue(mine.surface(), "logs are a surface resource");
        h.assertTrue(Math.abs(mine.perBlock() - 1.0) < 0.0001, "yield was " + mine.perBlock());
        h.succeed();
    }

    @GameTest
    public void indexKnowsLeather(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        Source.Hunt hunt = index.sourcesOf("minecraft:leather").stream()
                .filter(source -> source instanceof Source.Hunt)
                .map(source -> (Source.Hunt) source)
                .filter(source -> source.mob().equals("minecraft:cow"))
                .findFirst().orElse(null);
        h.assertTrue(hunt != null, "no cow hunt source for leather: " + index.sourcesOf("minecraft:leather"));
        h.assertTrue(hunt.perKill() > 0, "yield was " + hunt.perKill());
        h.succeed();
    }

    @GameTest
    public void indexBuiltOnce(GameTestHelper h) {
        h.assertTrue(DropIndex.of(h.getLevel()) == DropIndex.of(h.getLevel()), "index was rebuilt");
        h.succeed();
    }

    @GameTest
    public void scanCountsBlocksMobsChests(GameTestHelper h) {
        GameTestSupport.platformAt(h, 300, 32);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 301, 2);
        h.setBlock(4, 301, 2, Blocks.IRON_ORE);
        h.setBlock(5, 301, 2, Blocks.IRON_ORE);
        h.setBlock(6, 301, 2, Blocks.IRON_ORE);
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, 8, 301, 8);
        h.setBlock(3, 301, 8, Blocks.CHEST);
        h.setBlock(6, 300, 6, Blocks.FARMLAND);
        h.setBlock(7, 300, 6, Blocks.FARMLAND);
        h.setBlock(6, 301, 6, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        h.setBlock(7, 301, 6, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));

        h.succeedWhen(() -> {
            Container chest = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(3, 301, 8)));
            h.assertTrue(chest != null, "chest block entity is missing");
            chest.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
            chest.setChanged();
            WorldFacts world = WorldScan.scan(h.getLevel(), c, 32, true, false);
            h.assertValueEqual(world.blocksNearby().getOrDefault("minecraft:iron_ore", 0), 3, "iron ore blocks");
            h.assertValueEqual(world.mobsNearby().getOrDefault("minecraft:cow", 0), 1, "cows");
            h.assertValueEqual(world.containerItems().getOrDefault("minecraft:iron_ingot", 0), 2, "chest ingots");
            h.assertValueEqual(world.matureCrops().getOrDefault("minecraft:wheat", 0), 2, "mature wheat");
        });
    }

    @GameTest
    public void scanWithoutContainers(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        h.setBlock(3, 2, 8, Blocks.CHEST);

        WorldFacts world = WorldScan.scan(h.getLevel(), c, 32, false, false);
        h.assertTrue(world.containerItems().isEmpty(), "containers were read: " + world.containerItems());
        h.succeed();
    }

    @GameTest
    public void scanOverworldFlag(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        WorldFacts world = WorldScan.scan(h.getLevel(), c, 32, false, false);
        h.assertTrue(world.dimensionHasOre(), "the overworld has ores");
        h.succeed();
    }

    @GameTest
    public void toolMapping(GameTestHelper h) {
        DropIndex index = DropIndex.of(h.getLevel());
        h.assertTrue(index.toolItem(new ToolNeed(ToolKind.PICKAXE, Tier.STONE)).orElse("").equals("minecraft:stone_pickaxe"),
                "stone pickaxe item was " + index.toolItem(new ToolNeed(ToolKind.PICKAXE, Tier.STONE)));
        h.assertTrue(index.toolOf("minecraft:iron_pickaxe").equals(new ToolNeed(ToolKind.PICKAXE, Tier.IRON)),
                "iron pickaxe was " + index.toolOf("minecraft:iron_pickaxe"));
        h.succeed();
    }
}
