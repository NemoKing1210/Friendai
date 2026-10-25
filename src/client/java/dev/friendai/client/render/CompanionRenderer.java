package dev.friendai.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.friendai.entity.CompanionEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Renders the companion with the player model and the default Steve skin, plus a thought bubble while it thinks. */
public class CompanionRenderer
        extends HumanoidMobRenderer<CompanionEntity, CompanionRenderState, HumanoidModel<CompanionRenderState>> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");
    /** How long one dot of the thinking animation stays on screen. */
    private static final float TICKS_PER_DOT = 8.0F;
    private static final int DOTS = 3;

    public CompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(
                this,
                ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), HumanoidModel::new),
                context.getEquipmentRenderer()));
    }

    @Override
    public CompanionRenderState createRenderState() {
        return new CompanionRenderState();
    }

    @Override
    public void extractRenderState(CompanionEntity companion, CompanionRenderState state, float partialTick) {
        super.extractRenderState(companion, state, partialTick);
        state.thinking = companion.isThinking();
    }

    @Override
    public void submit(
            CompanionRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
        if (!state.thinking) {
            return;
        }
        // The dots grow one by one while the bubble bobs, so the thinking state reads as an animation over the head.
        int dots = 1 + ((int) (state.ageInTicks / TICKS_PER_DOT)) % DOTS;
        double bob = Math.sin(state.ageInTicks * 0.12F) * 0.06;
        collector.submitNameTag(
                poseStack,
                new Vec3(0.0, state.boundingBoxHeight + bob, 0.0),
                0,
                Component.literal(".".repeat(dots)),
                false,
                state.lightCoords,
                camera);
    }

    @Override
    public Identifier getTextureLocation(CompanionRenderState state) {
        return TEXTURE;
    }
}
