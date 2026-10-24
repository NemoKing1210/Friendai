package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.mining.BlockBreaker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Mines one specific block and collects its drop. */
public final class MineBlockAtTask implements Task {
    private final BlockPos pos;
    private BlockBreaker breaker;
    private BlockState startBlock;
    private String summary = "";

    public MineBlockAtTask(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public String type() {
        return "mine_block_at";
    }

    @Override
    public void start(TaskContext c) {
        startBlock = c.level().getBlockState(pos);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (startBlock.isAir()) {
            summary = "there is no block at " + coords();
            return TaskStatus.FAILED;
        }
        if (startBlock.getDestroySpeed(c.level(), pos) < 0) {
            summary = blockId() + " at " + coords() + " can't be broken";
            return TaskStatus.FAILED;
        }
        if (breaker == null) {
            breaker = new BlockBreaker(pos, state -> state.is(startBlock.getBlock()));
        }
        return switch (breaker.tick(c)) {
            case WORKING -> TaskStatus.RUNNING;
            case BROKEN -> {
                summary = "mined " + blockId() + " at " + coords();
                yield TaskStatus.SUCCESS;
            }
            case GONE -> {
                summary = "the block at " + coords() + " changed before I could mine it";
                yield TaskStatus.FAILED;
            }
            case UNREACHABLE -> {
                summary = "can't reach " + coords();
                yield TaskStatus.FAILED;
            }
            case OUT_OF_SIGHT -> {
                summary = coords() + " is hidden behind another block";
                yield TaskStatus.FAILED;
            }
            case PROTECTED -> {
                summary = coords() + " is protected";
                yield TaskStatus.FAILED;
            }
        };
    }

    @Override
    public void stop(TaskContext c) {
        if (breaker != null) {
            breaker.stop(c);
        }
    }

    @Override
    public String describe() {
        return "mining the block at " + coords();
    }

    @Override
    public String resultSummary() {
        return summary;
    }

    private String coords() {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private String blockId() {
        return BuiltInRegistries.BLOCK.getKey(startBlock.getBlock()).toString();
    }
}
