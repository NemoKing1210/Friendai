package dev.friendai.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the mob a vanilla target goal belongs to. {@code NearestAttackableTargetGoal} keeps the field in its
 * superclass, and a {@code @Shadow} in a mixin only resolves fields declared by the target class itself.
 */
@Mixin(TargetGoal.class)
public interface TargetGoalAccessor {
    @Accessor("mob")
    Mob friendai$mob();
}
