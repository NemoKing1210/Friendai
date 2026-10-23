package dev.friedai.task.cooking;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;

/** The three slots of a furnace, seen as a container, with the safety rules for loading one. */
public final class FurnaceSlots {
    /** Result-slot capacity when the slot is empty; cooking results are ordinary 64-stacks. */
    private static final int EMPTY_RESULT_ROOM = 64;

    private FurnaceSlots() {}

    /**
     * True when the furnace can take this job: its input is empty or the same item, its fuel is empty or a fuel,
     * and its result is empty or exactly the output we would add. Otherwise it is someone else's furnace.
     */
    public static boolean isFreeFor(AbstractFurnaceBlockEntity furnace, Item input, Item expectedResult) {
        ItemStack inputSlot = furnace.getItem(0);
        if (!inputSlot.isEmpty() && !inputSlot.is(input)) {
            return false;
        }
        ItemStack fuelSlot = furnace.getItem(1);
        if (!fuelSlot.isEmpty() && !FuelPolicy.isFuel(fuelSlot)) {
            return false;
        }
        ItemStack resultSlot = furnace.getItem(2);
        return resultSlot.isEmpty() || resultSlot.is(expectedResult);
    }

    /**
     * Moves input and fuel into the furnace. The input cannot exceed the room left in the result slot for a
     * stack of our output, so a job never wastes input that would have nowhere to go. Returns the number of
     * input items loaded; whatever did not fit stays with the companion.
     */
    public static int load(AbstractFurnaceBlockEntity furnace, ItemStack input, ItemStack fuel) {
        if (input.isEmpty()) {
            return 0;
        }
        ItemStack inputSlot = furnace.getItem(0);
        if (!inputSlot.isEmpty() && !ItemStack.isSameItemSameComponents(inputSlot, input)) {
            return 0;
        }
        int inputMax = input.getMaxStackSize();
        int inputRoom = inputSlot.isEmpty() ? inputMax : inputMax - inputSlot.getCount();
        int resultRoom = resultRoom(furnace);
        int count = Math.min(Math.min(input.getCount(), inputRoom), resultRoom);
        if (count <= 0) {
            return 0;
        }
        if (inputSlot.isEmpty()) {
            furnace.setItem(0, input.copyWithCount(count));
        } else {
            inputSlot.grow(count);
            furnace.setChanged();
        }
        loadFuel(furnace, fuel);
        return count;
    }

    private static int resultRoom(AbstractFurnaceBlockEntity furnace) {
        ItemStack result = furnace.getItem(2);
        if (result.isEmpty()) {
            return EMPTY_RESULT_ROOM;
        }
        return Math.max(0, result.getMaxStackSize() - result.getCount());
    }

    private static void loadFuel(AbstractFurnaceBlockEntity furnace, ItemStack fuel) {
        if (fuel.isEmpty() || !furnace.canPlaceItem(1, fuel)) {
            return;
        }
        ItemStack fuelSlot = furnace.getItem(1);
        if (fuelSlot.isEmpty()) {
            furnace.setItem(1, fuel.copy());
            return;
        }
        if (!ItemStack.isSameItemSameComponents(fuelSlot, fuel)) {
            return;
        }
        int room = fuelSlot.getMaxStackSize() - fuelSlot.getCount();
        if (room <= 0) {
            return;
        }
        fuelSlot.grow(Math.min(room, fuel.getCount()));
        furnace.setChanged();
    }

    /** Takes everything in the result slot. */
    public static ItemStack takeResult(AbstractFurnaceBlockEntity furnace) {
        ItemStack result = furnace.getItem(2);
        if (result.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return furnace.removeItem(2, result.getCount());
    }

    /**
     * Takes back what this companion put into the fuel slot: the leftover fuel and, when a lava bucket left one,
     * the empty bucket. A fuel of a different item stays where somebody else put it.
     */
    public static List<ItemStack> takeFuel(AbstractFurnaceBlockEntity furnace, Item fuelItem) {
        List<ItemStack> taken = new ArrayList<>(2);
        ItemStack slot = furnace.getItem(1);
        if (!slot.isEmpty() && slot.is(fuelItem)) {
            taken.add(furnace.removeItem(1, slot.getCount()));
        } else if (!slot.isEmpty() && slot.is(Items.BUCKET)) {
            taken.add(furnace.removeItem(1, slot.getCount()));
        }
        return taken;
    }

    /** Pops the experience of everything cooked here at the furnace, for whoever picks it up. */
    public static void popExperience(ServerLevel level, AbstractFurnaceBlockEntity furnace, Vec3 at) {
        furnace.getRecipesToAwardAndPopExperience(level, at);
    }

    public static BlockPos position(AbstractFurnaceBlockEntity furnace) {
        return furnace.getBlockPos();
    }
}
