package dev.friendai.mixin;

import dev.friendai.entity.CompanionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla mobs choose targets by concrete class, and every "attack the player" goal asks for {@link Player}.
 * The companion is a {@link net.minecraft.world.entity.PathfinderMob}, so those goals never see it. After the
 * vanilla search runs, look for a companion with the same targeting conditions and prefer it over a player when
 * it is closer, so hostile mobs treat the companion like any player.
 */
@Mixin(NearestAttackableTargetGoal.class)
public abstract class NearestAttackableTargetGoalMixin {
    @Shadow
    @Final
    protected Class<? extends LivingEntity> targetType;

    @Shadow
    @Final
    protected TargetingConditions targetConditions;

    @Shadow
    protected LivingEntity target;

    @Inject(method = "findTarget", at = @At("TAIL"))
    private void friendai$considerCompanionAsPlayer(CallbackInfo ci) {
        if (this.targetType != Player.class && this.targetType != ServerPlayer.class) {
            return;
        }
        Mob mob = ((TargetGoalAccessor) this).friendai$mob();
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        double range = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        AABB area = mob.getBoundingBox().inflate(range);
        CompanionEntity companion;
        try {
            companion = level.getNearestEntity(
                    CompanionEntity.class, this.targetConditions, mob, mob.getX(), mob.getEyeY(), mob.getZ(), area);
        } catch (ClassCastException e) {
            // A goal's selector may assume the target really is a Player; that goal then stays player-only.
            return;
        }
        if (companion == null) {
            return;
        }
        double x = mob.getX();
        double y = mob.getEyeY();
        double z = mob.getZ();
        if (this.target == null || companion.distanceToSqr(x, y, z) < this.target.distanceToSqr(x, y, z)) {
            this.target = companion;
        }
    }
}
