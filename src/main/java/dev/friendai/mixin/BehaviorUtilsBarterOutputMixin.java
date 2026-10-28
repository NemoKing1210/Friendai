package dev.friendai.mixin;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.task.trading.BarterOutputCapture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Records exact item entity UUIDs at vanilla's spawn call inside the piglin barter response emission. */
@Mixin(BehaviorUtils.class)
abstract class BehaviorUtilsBarterOutputMixin {
    @Redirect(
            method =
                    "throwItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;F)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean friendai$captureVanillaBarterDrop(Level level, Entity entity) {
        boolean added = level.addFreshEntity(entity);
        BarterOutputCapture.Context capture = BarterOutputCapture.current();
        if (added && capture != null && entity instanceof ItemEntity item && level instanceof ServerLevel serverLevel) {
            FriendaiServerState.get(serverLevel.getServer())
                    .barterDropTracker()
                    .observeVanillaOutput(
                            capture.attempt(),
                            capture.piglin(),
                            item.getUUID(),
                            serverLevel.getServer().overworld().getGameTime());
        }
        return added;
    }
}
