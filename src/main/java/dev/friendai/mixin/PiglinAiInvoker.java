package dev.friendai.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the barter task finish admiration through the same vanilla response path without waiting on AI timing. */
@Mixin(PiglinAi.class)
public interface PiglinAiInvoker {
    @Invoker("stopHoldingOffHandItem")
    static void friendai$finishAdmiration(ServerLevel level, Piglin piglin, boolean shouldBarter) {
        throw new AssertionError();
    }
}
