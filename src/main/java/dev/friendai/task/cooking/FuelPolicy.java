package dev.friendai.task.cooking;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** What may be burned, how long it burns, and which items of an inventory cover a job without wasting valuables. */
public final class FuelPolicy {
    /** Stacks chosen for a job: copies with the counts to take, and the ticks they cover together. */
    public record FuelPick(List<ItemStack> stacks, int ticksCovered) {
        public FuelPick {
            stacks = List.copyOf(stacks);
        }

        public static FuelPick empty() {
            return new FuelPick(List.of(), 0);
        }
    }

    /**
     * Fuels the companion may burn unasked, cheapest first. Valuable items (tools, boats, blocks of metal) are
     * never on this list; only an explicit {@code fuel} argument reaches them.
     */
    private static final List<Supplier<Optional<ItemMatcher>>> CHEAP = List.of(
            () -> ItemMatcher.parse("minecraft:coal"),
            () -> ItemMatcher.parse("minecraft:charcoal"),
            () -> ItemMatcher.parse("minecraft:coal_block"),
            () -> ItemMatcher.parse("minecraft:dried_kelp_block"),
            () -> ItemMatcher.parse("#minecraft:planks"),
            () -> ItemMatcher.parse("minecraft:stick"),
            () -> ItemMatcher.parse("#minecraft:logs"),
            () -> ItemMatcher.parse("minecraft:bamboo"),
            () -> ItemMatcher.parse("#minecraft:wooden_slabs"));

    private FuelPolicy() {}

    public static boolean isFuel(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.COOKING_FUEL);
    }

    /** How many ticks one item of this stack burns; 0 when it is not a fuel. */
    public static int burnTicks(ServerLevel level, BlockPos furnacePos, ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        return ResolvableInt.getFromItem(
                stack, DataComponents.COOKING_FUEL, CookingFuel::burnTime, lootContext(level, furnacePos), 0);
    }

    /**
     * Picks fuel for {@code ticksNeeded}: with an explicit matcher only that item, otherwise the cheap list in
     * priority order. The main hand is never touched. The caller removes the returned stacks from the inventory.
     */
    public static FuelPick pick(
            CompanionEntity companion,
            ServerLevel level,
            BlockPos furnacePos,
            int ticksNeeded,
            @Nullable ItemMatcher explicit) {
        if (explicit != null) {
            return take(companion, level, furnacePos, explicit, Math.max(0, ticksNeeded));
        }
        for (Supplier<Optional<ItemMatcher>> candidate : CHEAP) {
            Optional<ItemMatcher> matcher = candidate.get();
            if (matcher.isEmpty()) {
                continue;
            }
            FuelPick pick = take(companion, level, furnacePos, matcher.get(), Math.max(0, ticksNeeded));
            if (!pick.stacks().isEmpty()) {
                return pick;
            }
        }
        return FuelPick.empty();
    }

    private static FuelPick take(
            CompanionEntity companion, ServerLevel level, BlockPos furnacePos, ItemMatcher matcher, int ticksNeeded) {
        SimpleContainer inventory = companion.inventory();
        ItemStack prototype = null;
        List<ItemStack> picked = new ArrayList<>();
        int covered = 0;
        for (int slot = 0; slot < inventory.getContainerSize() && covered < ticksNeeded; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            if (prototype == null) {
                prototype = stack.copyWithCount(1);
            } else if (!ItemStack.isSameItemSameComponents(prototype, stack)) {
                // A furnace fuel slot holds one item type; mixing would make the extra stacks unusable.
                continue;
            }
            int perItem = burnTicks(level, furnacePos, stack);
            if (perItem <= 0) {
                continue;
            }
            int missing = ticksNeeded - covered;
            int count = Math.min(stack.getCount(), (missing + perItem - 1) / perItem);
            if (count <= 0) {
                continue;
            }
            picked.add(stack.copyWithCount(count));
            covered += count * perItem;
        }
        return new FuelPick(picked, covered);
    }

    /**
     * The context vanilla builds for a block entity at this position; a missing block entity uses the plain
     * block context, which is enough for every vanilla fuel.
     */
    private static LootContext lootContext(ServerLevel level, BlockPos pos) {
        LootParams.Builder params = new LootParams.Builder(level)
                .withParameter(LootContextParams.BLOCK_STATE, level.getBlockState(pos))
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos));
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof Container container) {
            params.withParameter(LootContextParams.BLOCK_ENTITY, entity)
                    .withParameter(LootContextParams.CONTAINER, container);
            return new LootContext.Builder(params.create(LootContextParamSets.CONTAINER_PROCESS))
                    .create(Optional.empty());
        }
        return new LootContext.Builder(params.withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                        .create(LootContextParamSets.BLOCK))
                .create(Optional.empty());
    }
}
