package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AnimalTests {
    @GameTest(maxTicks = 100)
    public void interactReturnsFilledBucket(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Cow cow = h.spawn(EntityTypes.COW, 4, 2, 2);
        InteractionResult result = c.hands().interact(cow, new ItemStack(Items.BUCKET));
        h.assertTrue(result.consumesAction(), "interaction failed: " + result);
        h.assertValueEqual(1, c.inventory().countItem(Items.MILK_BUCKET), "milk buckets");
        h.assertValueEqual(0, c.inventory().countItem(Items.BUCKET), "empty buckets");
        Inventory fake = c.hands().player().getInventory();
        int left = 0;
        for (int i = 0; i < fake.getContainerSize(); i++) {
            left += fake.getItem(i).getCount();
        }
        h.assertValueEqual(0, left, "items left in the fake player's inventory");
        h.succeed();
    }
}
