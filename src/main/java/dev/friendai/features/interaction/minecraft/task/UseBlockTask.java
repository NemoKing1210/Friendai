package dev.friendai.features.interaction.minecraft.task;

import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.autonomy.ProtectionText;
import dev.friendai.world.movement.Approach;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Uses a door, trapdoor, fence gate, lever or button and verifies any requested state. */
public final class UseBlockTask implements Task {
    // Vanilla buttons normally release in 20 or 30 ticks. An arrow can hold a wooden button longer.
    private static final int BUTTON_RELEASE_LIMIT_TICKS = 40;

    private final BlockPos pos;
    private final String want;

    private Approach approach;
    private Block buttonAwaitingRelease;
    private int buttonWaitTicks;
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
        if (buttonAwaitingRelease != null) {
            return awaitButtonRelease(state);
        }
        if (!whitelisted(state)) {
            summary = "I can only use doors, trapdoors, fence gates, levers and buttons; " + coords() + " is "
                    + blockId(state);
            return TaskStatus.FAILED;
        }
        BooleanProperty requested = null;
        if (want != null) {
            requested = property(state, want);
            if (requested == null) {
                summary = blockId(state) + " can't be " + want;
                return TaskStatus.FAILED;
            }
            if (state.getValue(requested) == wantedValue(want)) {
                summary = blockId(state) + " at " + coords() + " is already " + want;
                return TaskStatus.SUCCESS;
            }
        }
        if (!c.companion().hands().canInteract(pos)) {
            summary = coords() + " is " + ProtectionText.describe(c, pos);
            return TaskStatus.FAILED;
        }
        if ("off".equals(want) && state.is(BlockTags.BUTTONS)) {
            stopApproach(c);
            buttonAwaitingRelease = state.getBlock();
            return TaskStatus.RUNNING;
        }
        if (approach == null) {
            approach = new Approach(pos);
        }
        switch (approach.tick(c)) {
            case UNREACHABLE -> {
                summary = "can't reach " + coords();
                return TaskStatus.FAILED;
            }
            case MOVING -> {
                return TaskStatus.RUNNING;
            }
            case IN_REACH -> approach = null;
        }

        BlockState before = c.level().getBlockState(pos);
        if (!before.is(state.getBlock())) {
            summary = "the block at " + coords() + " changed while approaching";
            return TaskStatus.FAILED;
        }
        String id = blockId(before);
        if (requested != null && before.getValue(requested) == wantedValue(want)) {
            summary = id + " at " + coords() + " is already " + want;
            return TaskStatus.SUCCESS;
        }
        c.companion().hands().use(pos);
        BlockState after = c.level().getBlockState(pos);
        if (!after.is(before.getBlock())) {
            summary = "the block at " + coords() + " changed during use";
            return TaskStatus.FAILED;
        }
        BooleanProperty changed = changedProperty(before, after);
        if (changed == null) {
            summary = id + " at " + coords() + " can't be used by hand";
            return TaskStatus.FAILED;
        }
        if (requested != null && (changed != requested || after.getValue(requested) != wantedValue(want))) {
            summary = id + " at " + coords() + " did not reach " + want;
            return TaskStatus.FAILED;
        }
        if (changed == BlockStateProperties.POWERED && before.is(BlockTags.BUTTONS)) {
            summary = "used " + id + " at " + coords() + ": pressed";
        } else {
            String observed = changed == BlockStateProperties.OPEN
                    ? (after.getValue(changed) ? "open" : "closed")
                    : (after.getValue(changed) ? "on" : "off");
            summary = "used " + id + " at " + coords() + ": now " + observed;
        }
        return TaskStatus.SUCCESS;
    }

    private TaskStatus awaitButtonRelease(BlockState state) {
        if (!state.is(buttonAwaitingRelease)) {
            summary = "the button at " + coords() + " was replaced while waiting for release";
            return TaskStatus.FAILED;
        }
        if (!state.getValue(BlockStateProperties.POWERED)) {
            summary = blockId(state) + " at " + coords() + " released and is now off";
            return TaskStatus.SUCCESS;
        }
        if (++buttonWaitTicks >= BUTTON_RELEASE_LIMIT_TICKS) {
            summary = blockId(state) + " at " + coords() + " stayed on while waiting for release";
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public int waitingTicks() {
        return buttonAwaitingRelease == null ? -1 : buttonWaitTicks;
    }

    @Override
    public int waitingLimitTicks() {
        return buttonAwaitingRelease == null ? 0 : BUTTON_RELEASE_LIMIT_TICKS;
    }

    @Override
    public void stop(TaskContext c) {
        stopApproach(c);
    }

    @Override
    public void onTerminal(TaskContext c) {
        stopApproach(c);
    }

    private void stopApproach(TaskContext c) {
        if (approach != null) {
            approach.stop(c);
            approach = null;
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
            return state.hasProperty(BlockStateProperties.POWERED)
                            && (state.is(Blocks.LEVER) || state.is(BlockTags.BUTTONS))
                    ? BlockStateProperties.POWERED
                    : null;
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
