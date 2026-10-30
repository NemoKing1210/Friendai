package dev.friendai.client.render;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** The companion's render state: the humanoid pose plus whether the brain is waiting for the model. */
public final class CompanionRenderState extends HumanoidRenderState {
    /** When set, a thought bubble with animated dots is drawn above the head. */
    public boolean thinking;
    /** The custom skin texture once it has arrived, otherwise null and the default skin is drawn. */
    public Identifier skin;
    /** The block the companion is placing right now, drawn in its main hand; empty when it is not placing. */
    public ItemStack placeItem = ItemStack.EMPTY;
}
