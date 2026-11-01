package dev.friendai.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.friendai.FriendaiMod;
import dev.friendai.client.skin.SkinCache;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.social.Mood;
import net.minecraft.ChatFormatting;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the companion with the player model and its bundled default skin, plus a thought indicator while it thinks.
 */
public class CompanionRenderer
        extends HumanoidMobRenderer<CompanionEntity, CompanionRenderState, HumanoidModel<CompanionRenderState>> {
    private final HumanoidModel<CompanionRenderState> wideModel;
    private final HumanoidModel<CompanionRenderState> slimModel;
    private static final Identifier TEXTURE = FriendaiMod.id("textures/entity/companion.png");
    /** How long one dot of the thinking animation stays on screen. */
    private static final float TICKS_PER_DOT = 8.0F;

    private static final int DOTS = 3;
    /** Where the dots float above the hitbox. High enough to leave the name tag clear underneath them. */
    private static final double THOUGHT_HEIGHT = 0.6;
    /** The mood mark sits just above the thought bubble so the two never overlap. */
    private static final double MOOD_HEIGHT = 0.9;

    public CompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new CompanionModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.wideModel = this.model;
        this.slimModel = new CompanionModel(context.bakeLayer(ModelLayers.PLAYER_SLIM));
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
        state.mood = companion.syncedMood();
        state.skin = SkinCache.textureFor(companion.getSkinHash());
        showPlacedBlock(companion, state);
        // The model is only read while this entity is drawn, so picking the arm shape here is safe.
        this.model = state.skin != null && companion.isSlimSkin() ? slimModel : wideModel;
    }

    /**
     * Draws the block the companion is placing in its main hand. It is a display copy the server sends while the
     * placement window is open; the real stack is in the companion's inventory and never moves for the animation.
     */
    private void showPlacedBlock(CompanionEntity companion, CompanionRenderState state) {
        state.placeItem = companion.syncedPlaceItem();
        if (state.placeItem.isEmpty()) {
            return;
        }
        boolean right = companion.getMainArm() == HumanoidArm.RIGHT;
        itemModelResolver.updateForLiving(
                right ? state.rightHandItemState : state.leftHandItemState,
                state.placeItem,
                right ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                companion);
        if (right) {
            state.rightHandItemStack = state.placeItem;
        } else {
            state.leftHandItemStack = state.placeItem;
        }
    }

    /**
     * Players raise the arm holding the item while eating or drinking; the mob renderer would leave it hanging down.
     * Reusing the player's own pose keeps the companion's meal looking exactly like theirs.
     */
    @Override
    protected HumanoidModel.ArmPose getArmPose(CompanionEntity companion, HumanoidArm arm) {
        if (!companion.syncedPlaceItem().isEmpty() && companion.getMainArm() == arm) {
            // The block is held out front; CompanionModel turns the ITEM pose into a reach toward the clicked face.
            return HumanoidModel.ArmPose.ITEM;
        }
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
            CompanionRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
        if (!state.thinking
                && state.mood != Mood.CALM
                && dev.friendai.client.FriendaiClient.clientConfig().social.moodMark) {
            submitMood(state, poseStack, collector, camera);
        }
        if (!state.thinking) {
            return;
        }
        // The dots grow one by one while the indicator bobs, so the thinking state reads as an animation over the head.
        int dots = 1 + ((int) (state.ageInTicks / TICKS_PER_DOT)) % DOTS;
        double bob = Math.sin(state.ageInTicks * 0.12F) * 0.06;
        collector.submitNameTag(
                poseStack,
                new Vec3(0.0, state.boundingBoxHeight + THOUGHT_HEIGHT + bob, 0.0),
                0,
                Component.literal(".".repeat(dots)),
                false,
                state.lightCoords,
                camera);
    }

    private static void submitMood(
            CompanionRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        String mark = switch (state.mood) {
            case FOCUSED -> "\u2022";
            case WORRIED -> "!";
            case PLEASED -> "^";
            case DISPLEASED -> "~";
            case COMBAT -> "!!";
            case CALM -> "";
        };
        ChatFormatting color = switch (state.mood) {
            case FOCUSED -> ChatFormatting.AQUA;
            case WORRIED -> ChatFormatting.YELLOW;
            case PLEASED -> ChatFormatting.GREEN;
            case DISPLEASED -> ChatFormatting.GRAY;
            case COMBAT -> ChatFormatting.RED;
            case CALM -> ChatFormatting.WHITE;
        };
        collector.submitNameTag(
                poseStack,
                new Vec3(0.0, state.boundingBoxHeight + MOOD_HEIGHT, 0.0),
                0,
                Component.literal(mark).withStyle(color),
                false,
                state.lightCoords,
                camera);
    }

    @Override
    public Identifier getTextureLocation(CompanionRenderState state) {
        return state.skin != null ? state.skin : TEXTURE;
    }

    /**
     * A player lies flat while swimming, but {@link HumanoidMobRenderer} keeps a mob upright, which is what makes a
     * swimming companion look like it crawls over the water. The avatar's own rule is reused here.
     */
    @Override
    protected void setupRotations(CompanionRenderState state, PoseStack poseStack, float bodyRot, float entityScale) {
        super.setupRotations(state, poseStack, bodyRot, entityScale);
        float swimAmount = state.swimAmount;
        if (swimAmount <= 0.0F) {
            return;
        }
        float targetPitch = state.isInWater ? -90.0F - state.xRot : -90.0F;
        poseStack.rotateDegrees(Axis.XP, Mth.lerp(swimAmount, 0.0F, targetPitch));
        if (state.isVisuallySwimming) {
            poseStack.translate(0.0F, -1.0F, 0.3F);
        }
    }
}
