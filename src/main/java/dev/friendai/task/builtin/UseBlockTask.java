package dev.friendai.task.builtin;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.autonomy.ProtectionText;
import dev.friendai.task.nav.Approach;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Uses a door, trapdoor, fence gate, lever or button, optionally until it reaches a wanted state. */
public final class UseBlockTask implements Task {
    private final BlockPos pos;
    private final String want;

    private Approach approach;
    private String summary = "";

    public UseBlockTask(BlockPos pos, String want) {
        this.pos = pos;
        this.want = want;
    }

    @Override
    public String type() {
        return "use_block";
    }

    @Override
    public List<DebugTarget> debugTargets() {
        return List.of(new DebugTarget(pos.getX(), pos.getY(), pos.getZ(), DebugAction.USE, want == null ? "" : want));
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        BlockState state = c.level().getBlockState(pos);
        String id = blockId(state);
        if (!whitelisted(state)) {
            summary = "I can only use doors, trapdoors, fence gates, levers and buttons; " + coords() + " is " + id;
            return TaskStatus.FAILED;
        }
        if (want != null) {
            BooleanProperty property = property(state, want);
            if (property == null) {
                summary = id + " can't be " + want;
                return TaskStatus.FAILED;
            }
            if (state.getValue(property) == wantedValue(want)) {
                summary = id + " at " + coords() + " is already " + want;
                return TaskStatus.SUCCESS;
            }
        }
        if (approach == null) {
            approach = new Approach(pos);
        }
        switch (approach.tick(c.companion())) {
            case UNREACHABLE -> {
                summary = "can't reach " + coords();
                return TaskStatus.FAILED;
            }
            case MOVING -> {
                return TaskStatus.RUNNING;
            }
            case IN_REACH -> {}
        }
        if (!c.companion().hands().canInteract(pos)) {
            summary = coords() + " is " + ProtectionText.describe(c, pos);
            return TaskStatus.FAILED;
        }

        BlockState before = c.level().getBlockState(pos);
        c.companion().hands().use(pos);
        BlockState after = c.level().getBlockState(pos);
        BooleanProperty changed = changedProperty(before, after);
        if (changed == null) {
            summary = id + " at " + coords() + " can't be used by hand";
            return TaskStatus.FAILED;
        }
        if (changed == BlockStateProperties.POWERED && before.is(BlockTags.BUTTONS)) {
            summary = "used " + id + " at " + coords() + ": pressed";
        } else {
            summary = "used " + id + " at " + coords() + ": now " + (after.getValue(changed) ? "on" : "off");
            if (changed == BlockStateProperties.OPEN) {
                summary = "used " + id + " at " + coords() + ": now " + (after.getValue(changed) ? "open" : "closed");
            }
        }
        return TaskStatus.SUCCESS;
    }

    @Override
    public void stop(TaskContext c) {
        if (approach != null) {
            approach.stop(c.companion());
        }
    }

    @Override
    public String describe() {
        return "using the block at " + coords();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private String coords() {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static boolean whitelisted(BlockState state) {
        return state.is(BlockTags.DOORS)
                || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.BUTTONS)
                || state.is(Blocks.LEVER);
    }

    private static BooleanProperty property(BlockState state, String want) {
        if (want.equals("open") || want.equals("closed")) {
            return state.hasProperty(BlockStateProperties.OPEN) ? BlockStateProperties.OPEN : null;
        }
        if (want.equals("on") || want.equals("off")) {
            return state.is(Blocks.LEVER) || state.is(BlockTags.BUTTONS) ? BlockStateProperties.POWERED : null;
        }
        return null;
    }

    private static boolean wantedValue(String want) {
        return want.equals("open") || want.equals("on");
    }

    private static BooleanProperty changedProperty(BlockState before, BlockState after) {
        if (before.hasProperty(BlockStateProperties.OPEN)
                && before.getValue(BlockStateProperties.OPEN) != after.getValue(BlockStateProperties.OPEN)) {
            return BlockStateProperties.OPEN;
        }
        if (before.hasProperty(BlockStateProperties.POWERED)
                && before.getValue(BlockStateProperties.POWERED) != after.getValue(BlockStateProperties.POWERED)) {
            return BlockStateProperties.POWERED;
        }
        return null;
    }
}
