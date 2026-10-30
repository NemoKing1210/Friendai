package dev.friendai.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.HumanoidArm;

/**
 * The player model with one extra pose: while the companion places a block its main arm reaches forward, holding the
 * block where a player's hand would be, instead of hanging at its side. The vanilla model has no placing pose, so the
 * reach is applied after its own animation.
 */
final class CompanionModel extends HumanoidModel<CompanionRenderState> {
    /**
     * How far the placing arm reaches forward, added to whatever the walk and swing animation already produced, so the
     * one swing per placed block stays visible on top of the reach instead of being overwritten by it.
     */
    private static final float REACH = -1.1F;

    CompanionModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(CompanionRenderState state) {
        super.setupAnim(state);
        if (state.placeItem.isEmpty()) {
            return;
        }
        ModelPart arm = state.mainArm == HumanoidArm.RIGHT ? this.rightArm : this.leftArm;
        arm.xRot += REACH;
    }
}
