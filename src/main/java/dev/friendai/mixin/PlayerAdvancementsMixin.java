package dev.friendai.mixin;

import dev.friendai.server.agent.social.HappeningFeed;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notices an advancement the moment it is awarded. Fabric has no advancement event, and vanilla announces the
 * advancement from this same method, so the hook sits at its return and only fires for a real award.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
    @Shadow
    private ServerPlayer player;

    @Inject(method = "award(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z", at = @At("RETURN"))
    private void friendai$recordAdvancement(
            AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> returned) {
        if (returned.getReturnValueZ()) {
            HappeningFeed.advancement(player, holder);
        }
    }
}
