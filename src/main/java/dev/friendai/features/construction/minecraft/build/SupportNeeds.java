package dev.friendai.features.construction.minecraft.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * What a planned block needs solid under or beside it to survive, derived from the state itself, so the build order
 * can place the support first. Every family copies one vanilla {@code canSurvive}; a state that is not listed answers
 * {@code null}, which keeps the runtime deferral of {@code BuildTask} instead of inventing a dependency.
 *
 * <p>Covered families and the rule they copy:
 * <ul>
 *   <li>wall torch, ladder, wall sign, tripwire hook, wall banner — solid behind {@code facing.getOpposite()}
 *       ({@code WallTorchBlock.canSurvive}, {@code LadderBlock.canSurvive}, {@code WallSignBlock.canSurvive},
 *       {@code TripWireHookBlock.canSurvive}, {@code WallBannerBlock.canSurvive});</li>
 *   <li>lantern — above when {@code hanging}, below otherwise ({@code LanternBlock.getConnectedDirection});</li>
 *   <li>ceiling hanging sign — above ({@code CeilingHangingSignBlock.canSurvive});</li>
 *   <li>door, snow layer, carpet, candle, rail, floor torch — below ({@code DoorBlock.canSurvive},
 *       {@code SnowLayerBlock.canSurvive}, {@code CarpetBlock.canSurvive}, {@code CandleBlock.canSurvive},
 *       {@code BaseRailBlock.canSurvive}, {@code TorchBlock.canSurvive}).</li>
 * </ul>
 */
public final class SupportNeeds {
    private SupportNeeds() {}
    /** The cell that must be solid before {@code state} can stand at {@code pos}, or null when it stands alone. */
    public static BlockPos supportOf(BlockState state, BlockPos pos) {
        if (attachesToWall(state)) {
            Direction facing = facing(state);
            return facing == null ? null : pos.relative(facing.getOpposite());
        }
        if (state.getBlock() instanceof LanternBlock) {
            return hanging(state) ? pos.above() : pos.below();
        }
        if (state.getBlock() instanceof CeilingHangingSignBlock) {
            return pos.above();
        }
        if (state.getBlock() instanceof TorchBlock) {
            return pos.below();
        }
        if (state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof SnowLayerBlock
                || state.getBlock() instanceof CarpetBlock
                || state.getBlock() instanceof CandleBlock
                || state.getBlock() instanceof BaseRailBlock) {
            return pos.below();
        }
        return null;
    }

    private static boolean attachesToWall(BlockState state) {
        return state.getBlock() instanceof WallTorchBlock
                || state.getBlock() instanceof LadderBlock
                || state.getBlock() instanceof WallSignBlock
                || state.getBlock() instanceof TripWireHookBlock
                || state.getBlock() instanceof WallBannerBlock;
    }
    /**
     * Reads the {@code facing} property by name rather than through a class constant: the families above declare it
     * on different classes, and the name is the one property every wall block shares.
     */
    private static Direction facing(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("facing");
        if (property == null) {
            return null;
        }
        Object value = state.getValue(property);
        return value instanceof Direction direction ? direction : null;
    }

    private static boolean hanging(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("hanging");
        if (property == null) {
            return false;
        }
        Object value = state.getValue(property);
        return value instanceof Boolean bool && bool;
    }
}
