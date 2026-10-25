package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.inventory.InventoryOps;
import dev.friendai.task.inventory.ItemMatcher;
import java.util.function.Supplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Tosses items toward a target or straight ahead; they land as ordinary item entities. Finishes on its first tick. */
public final class ThrowItemTask implements Task {
    private static final int PICKUP_DELAY_TICKS = 60;
    private static final double SPEED = 0.5;
    private static final double LIFT = 0.2;

    private final ItemMatcher matcher;
    private final int count;
    @Nullable private final Supplier<Vec3> target;
    private final String targetLabel;
    private String summary = "";

    public ThrowItemTask(ItemMatcher matcher, int count, @Nullable Supplier<Vec3> target, String targetLabel) {
        this.matcher = matcher;
        this.count = count;
        this.target = target;
        this.targetLabel = targetLabel;
    }

    @Override
    public String type() {
        return "throw_item";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        CompanionEntity companion = c.companion();
        int have = InventoryOps.count(companion.inventory(), matcher);
        if (have == 0) {
            summary = "I don't have " + matcher.label();
            return TaskStatus.FAILED;
        }
        Vec3 aim = target == null ? null : target.get();
        Vec3 eye = companion.getEyePosition();
        Vec3 direction = aim == null ? companion.getLookAngle() : aim.subtract(eye).normalize();
        Vec3 velocity = direction.scale(SPEED).add(0.0, LIFT, 0.0);
        int thrown = 0;
        for (ItemStack stack : InventoryOps.remove(companion.inventory(), matcher, Math.min(count, have))) {
            ItemEntity entity = new ItemEntity(c.level(), eye.x, eye.y - 0.3, eye.z, stack);
            entity.setThrower(companion);
            entity.setPickUpDelay(PICKUP_DELAY_TICKS);
            entity.setDeltaMovement(velocity);
            c.level().addFreshEntity(entity);
            thrown += stack.getCount();
        }
        summary = "threw " + thrown + " " + matcher.label() + (aim == null ? "" : " toward " + targetLabel);
        return TaskStatus.SUCCESS;
    }

    @Override
    public void stop(TaskContext c) {}

    @Override
    public String describe() {
        return "throwing " + count + " " + matcher.label();
    }

    @Override
    public String resultSummary() {
        return summary;
    }
}
