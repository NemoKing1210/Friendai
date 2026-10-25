package dev.friendai.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.friendai.entity.CompanionEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

/**
 * Renders the companion with the player model and the default Steve skin, plus a thought bubble while it thinks.
 */
public class CompanionRenderer
        extends HumanoidMobRenderer<CompanionEntity, CompanionRenderState, HumanoidModel<CompanionRenderState>> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    /** Sprite pixels per block: the same scale vanilla name tags use, so the bubble keeps their crisp pixel look. */
    private static final float FONT_PIXEL = 0.025F;
    /** Where the bubble floats above the hitbox. High enough to leave the name tag clear underneath it. */
    private static final double BUBBLE_HEIGHT = 0.6;
    /** The bubble body in sprite pixels; y grows downward, so negative values sit above the anchor. */
    private static final float BODY_LEFT = -12.0F;
    private static final float BODY_TOP = -26.0F;
    private static final float BODY_RIGHT = 12.0F;
    private static final float BODY_BOTTOM = -12.0F;
    /** Warm paper, a soft drop shadow and dark ink for the dots. */
    private static final int PAPER_COLOR = 0xE8FFF8E7;
    private static final int SHADOW_COLOR = 0x50000000;
    private static final int DOT_COLOR = 0xFF4A4038;
    /** How the three dots are spread through the bubble, in sprite pixels. */
    private static final int DOTS = 3;
    private static final float DOT_SPACING = 8.0F;
    private static final float DOT_CENTER_Y = -19.0F;
    /** How fast the dots chase each other, and how far apart their phases are. */
    private static final float DOT_SPEED = 0.22F;
    private static final float DOT_PHASE = 0.9F;
    /** Speed of the slow drift and breathing of the whole bubble. */
    private static final float BUBBLE_SPEED = 0.09F;

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

    /**
     * Players raise the arm holding the item while eating or drinking; the mob renderer would leave it hanging down.
     * Reusing the player's own pose keeps the companion's meal looking exactly like theirs.
     */
    @Override
    protected HumanoidModel.ArmPose getArmPose(CompanionEntity companion, HumanoidArm arm) {
        if (isUsingFood(companion) && companion.getUsedItemHand().asArm(companion.getMainArm()) == arm) {
            return HumanoidModel.ArmPose.ITEM;
        }
        return super.getArmPose(companion, arm);
    }

    private static boolean isUsingFood(CompanionEntity companion) {
        if (!companion.isUsingItem()) {
            return false;
        }
        ItemStack used = companion.getUseItem();
        ItemUseAnimation animation = used.getUseAnimation();
        return animation == ItemUseAnimation.EAT || animation == ItemUseAnimation.DRINK;
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
        float time = state.ageInTicks;
        int light = LightCoordsUtil.lightCoordsWithEmission(state.lightCoords, 2);
        // A slow drift and a gentle breath keep the bubble alive instead of pinning it rigidly to the head.
        float bob = Mth.sin(time * BUBBLE_SPEED) * 0.035F;
        float breath = 1.0F + Mth.sin(time * BUBBLE_SPEED + 1.2F) * 0.03F;

        poseStack.pushPose();
        poseStack.translate(0.0, state.boundingBoxHeight + BUBBLE_HEIGHT + bob, 0.0);
        poseStack.rotate(camera.orientation);
        poseStack.scale(FONT_PIXEL * breath, -FONT_PIXEL * breath, FONT_PIXEL);
        // Every rectangle below is the font's own background shape, so it is rounded, blended and lit like a name tag.
        blob(collector, poseStack, BODY_LEFT + 1.0F, BODY_TOP + 1.0F, BODY_RIGHT + 1.0F, BODY_BOTTOM + 1.0F,
                SHADOW_COLOR, light);
        blob(collector, poseStack, BODY_LEFT, BODY_TOP, BODY_RIGHT, BODY_BOTTOM, PAPER_COLOR, light);
        // A tail of two smaller bubbles trails down towards the head, which reads as "thought".
        blob(collector, poseStack, -9.0F, -10.0F, -5.0F, -6.0F, PAPER_COLOR, light);
        blob(collector, poseStack, -11.0F, -5.0F, -8.0F, -2.0F, ARGB.multiplyAlpha(PAPER_COLOR, 0.8F), light);
        // The dots run a smooth wave: each one grows, brightens and lifts as the wave passes through it.
        for (int i = 0; i < DOTS; i++) {
            float pulse = 0.5F + 0.5F * Mth.sin(time * DOT_SPEED - i * DOT_PHASE);
            float radius = 1.5F + 0.5F * pulse;
            float centerX = (i - (DOTS - 1) / 2.0F) * DOT_SPACING;
            float centerY = DOT_CENTER_Y - pulse;
            blob(collector, poseStack, centerX - radius, centerY - radius, centerX + radius, centerY + radius,
                    ARGB.multiplyAlpha(DOT_COLOR, 0.55F + 0.45F * pulse), light);
        }
        poseStack.popPose();
    }

    /** Draws one rounded rectangle of the thought bubble, in the pixel space set up by {@link #submit}. */
    private static void blob(
            SubmitNodeCollector collector,
            PoseStack poseStack,
            float x0,
            float y0,
            float x1,
            float y1,
            int color,
            int light) {
        collector.submitTextBackground(poseStack, x0, y0, x1, y1, color, Font.DisplayMode.NORMAL, light);
    }

    @Override
    public Identifier getTextureLocation(CompanionRenderState state) {
        return TEXTURE;
    }
}
