package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.CompanionManager;
import dev.friedai.entity.FriedaiEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;

public class CompanionEntityTests {
    private static long companionsOf(ServerLevel level, ServerPlayer owner) {
        return level.getEntities(EntityTypeTest.forClass(CompanionEntity.class), e -> owner.getUUID().equals(e.ownerUuid()))
                .stream()
                .filter(e -> !e.isRemoved())
                .count();
    }

    /** The mock player logs in at the world origin; move it into the (loaded) test area. */
    @SuppressWarnings("removal")
    static ServerPlayer mockPlayerHere(GameTestHelper h) {
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        var pos = h.absoluteVec(new net.minecraft.world.phys.Vec3(2, 2, 2));
        player.teleportTo(pos.x, pos.y, pos.z);
        return player;
    }

    @GameTest
    public void summonCreatesOneCompanionPerOwner(GameTestHelper h) {
        ServerPlayer player = mockPlayerHere(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(player);
        manager.summon(player);
        h.runAfterDelay(2, () -> {
            h.assertTrue(companionsOf(h.getLevel(), player) == 1, "expected exactly one companion");
            h.succeed();
        });
    }

    @GameTest
    public void companionSavesOwnerAndInventory(GameTestHelper h) {
        ServerPlayer player = mockPlayerHere(h);
        CompanionEntity a = h.spawn(FriedaiEntities.COMPANION, 1, 2, 1);
        a.setOwner(player.getUUID());
        a.inventory().addItem(new ItemStack(Items.OAK_LOG, 5));

        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);
        CompoundTag tag = out.buildResult();

        CompanionEntity b = h.spawn(FriedaiEntities.COMPANION, 3, 2, 3);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));

        h.assertTrue(player.getUUID().equals(b.ownerUuid()), "owner not restored");
        h.assertTrue(b.inventory().countItem(Items.OAK_LOG) == 5, "inventory not restored");
        h.succeed();
    }

    @GameTest
    public void collectNearbyItemsMovesStacks(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        var pos = companion.position();
        ItemEntity item = new ItemEntity(h.getLevel(), pos.x + 1, pos.y, pos.z, new ItemStack(Items.COBBLESTONE, 3));
        h.getLevel().addFreshEntity(item);
        int moved = companion.collectNearbyItems(2.0);
        h.assertTrue(moved == 3, "moved " + moved + " items, expected 3");
        h.assertTrue(item.isRemoved(), "item entity should be removed");
        h.assertTrue(companion.inventory().countItem(Items.COBBLESTONE) == 3, "cobblestone not in inventory");
        h.succeed();
    }

    @GameTest
    public void dismissRemovesCompanion(GameTestHelper h) {
        ServerPlayer player = mockPlayerHere(h);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        manager.summon(player);
        h.assertTrue(manager.dismiss(player), "dismiss should report success");
        h.assertTrue(manager.find(player.getUUID()).isEmpty(), "companion should be gone");
        h.assertTrue(!manager.dismiss(player), "second dismiss should report no companion");
        h.succeed();
    }

    @SuppressWarnings("unused")
    private static void unused(EntityType<?> t, Entity e) {}
}
