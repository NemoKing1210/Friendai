package dev.friendai.features.coordination.minecraft.tool;

import dev.friendai.agent.coordination.HandoffRequest;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Validates and commits an exact companion inventory transfer on the server thread. */
public final class HandoffTransfer {
    private HandoffTransfer() {}

    public static ToolResult move(HandoffRequest request, CompanionEntity source, CompanionEntity target) {
        if (source == null
                || target == null
                || source == target
                || !source.isAlive()
                || !target.isAlive()
                || !request.fromOwner().equals(source.ownerUuid())
                || !request.toOwner().equals(target.ownerUuid())) {
            return ToolResult.error("both companions must exist");
        }
        if (source.level() != target.level()
                || !source.level().dimension().identifier().toString().equals(request.dimension())) {
            return ToolResult.error("both companions must be in the offered dimension");
        }
        if (!atMeeting(source, request) || !atMeeting(target, request)) {
            return ToolResult.error("both companions must be at the meeting point");
        }
        ItemMatcher matcher = ItemMatcher.parse(request.item()).orElse(null);
        if (matcher == null) return ToolResult.error("unknown item or tag: " + request.item());
        var sourceInventory = source.inventory();
        var targetInventory = target.inventory();
        int have = InventoryOps.count(sourceInventory, matcher);
        if (have < request.count()) {
            return ToolResult.error("the source companion has only " + have + " " + request.item());
        }

        // Let the same container implementation perform all stack merging and component/capacity checks.
        var remainingSource = copy(sourceInventory);
        var filledTarget = copy(targetInventory);
        for (ItemStack stack : InventoryOps.remove(remainingSource, matcher, request.count())) {
            if (!filledTarget.addItem(stack).isEmpty()) {
                return ToolResult.error("the target companion has no room for the exact handoff");
            }
        }
        apply(sourceInventory, remainingSource);
        apply(targetInventory, filledTarget);
        return ToolResult.ok("moved " + request.count() + " " + request.item() + " to the other companion");
    }

    private static SimpleContainer copy(SimpleContainer original) {
        var copy = new SimpleContainer(original.getContainerSize());
        for (int slot = 0; slot < original.getContainerSize(); slot++) {
            copy.setItem(slot, original.getItem(slot).copy());
        }
        return copy;
    }

    private static void apply(SimpleContainer original, SimpleContainer updated) {
        for (int slot = 0; slot < original.getContainerSize(); slot++) {
            ItemStack stack = updated.getItem(slot);
            if (!ItemStack.matches(original.getItem(slot), stack)) {
                original.setItem(slot, stack);
            }
        }
    }

    private static boolean atMeeting(CompanionEntity companion, HandoffRequest request) {
        var at = companion.blockPosition();
        long dx = (long) at.getX() - request.x();
        long dy = (long) at.getY() - request.y();
        long dz = (long) at.getZ() - request.z();
        return dx * dx + dy * dy + dz * dz <= 25;
    }
}
