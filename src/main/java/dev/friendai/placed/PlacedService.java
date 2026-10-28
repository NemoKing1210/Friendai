package dev.friendai.placed;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.Pos;
import dev.friendai.perception.RelativeDirection;
import dev.friendai.task.mining.BlockMatcher;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The server-thread facade over the owner's placement ledger. Recording happens at the one choke point every
 * placement path already uses, {@code CompanionHands.place}; this class only turns a world fact into an entry and
 * answers queries. A companion without an owner records nothing and sees nothing.
 */
public final class PlacedService {
    /** How many entries one prune call may inspect: a query must never scan the whole book. */
    public static final int PRUNE_BUDGET = 64;
    /** How far the prompt block looks around the companion. */
    public static final int PROMPT_RADIUS = 48;
    /** How many entries the prompt block reads before grouping. */
    public static final int PROMPT_ENTRIES = 24;
    /** How many characters the prompt block may use. */
    public static final int PROMPT_CHARS = 360;
    /** How many material lines the prompt block may show. */
    public static final int PROMPT_GROUPS = 6;

    private PlacedService() {}

    /** The owner's book, or a fresh empty one when the companion has no owner. */
    public static PlacedBook book(CompanionEntity companion) {
        UUID owner = companion.ownerUuid();
        if (owner == null || !(companion.level() instanceof ServerLevel level)) {
            return new PlacedBook(PlacedLimits.DEFAULTS);
        }
        return FriendaiServerState.get(level.getServer()).placed().book(owner);
    }

    /** Records one successful placement; a companion without an owner is not recorded. */
    public static void record(CompanionEntity companion, BlockPos pos, BlockState state, String source) {
        if (!(companion.level() instanceof ServerLevel level)) {
            return;
        }
        UUID owner = companion.ownerUuid();
        if (owner == null || state == null || state.isAir()) {
            return;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        PlacedBlock block = new PlacedBlock(
                level.dimension().identifier().toString(),
                new Pos(pos.getX(), pos.getY(), pos.getZ()),
                blockId,
                level.getGameTime(),
                source);
        FriendaiServerState.get(level.getServer()).placed().book(owner).record(block);
    }

    /** Forgets the entry for one cell, after the block was broken or taken back. */
    public static void forget(CompanionEntity companion, BlockPos pos) {
        if (!(companion.level() instanceof ServerLevel level)) {
            return;
        }
        UUID owner = companion.ownerUuid();
        if (owner == null) {
            return;
        }
        FriendaiServerState.get(level.getServer()).placed().book(owner)
                .remove(level.dimension().identifier().toString(),
                        new Pos(pos.getX(), pos.getY(), pos.getZ()));
    }

    /** Drops entries whose loaded chunk no longer holds the recorded block; unloaded chunks are kept. */
    public static int prune(CompanionEntity companion) {
        return prune(companion, PRUNE_BUDGET);
    }

    public static int prune(CompanionEntity companion, int budget) {
        if (!(companion.level() instanceof ServerLevel level)) {
            return 0;
        }
        PlacedBook book = book(companion);
        String dimension = level.dimension().identifier().toString();
        List<PlacedBlock> remove = new ArrayList<>();
        int checked = 0;
        for (PlacedBlock entry : book.inDimension(dimension)) {
            if (checked++ >= budget) {
                break;
            }
            BlockPos pos = new BlockPos(entry.pos().x(), entry.pos().y(), entry.pos().z());
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            String current = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
            if (!current.equals(entry.blockId())) {
                remove.add(entry);
            }
        }
        for (PlacedBlock entry : remove) {
            book.remove(entry.dimension(), entry.pos());
        }
        return remove.size();
    }

    /** The prompt block for this caller; empty for guests, an empty ledger or a companion without a level. */
    public static String promptBlock(CompanionEntity companion, Caller caller) {
        if (caller == null || !caller.access().allows(AccessLevel.TRUSTED)
                || !(companion.level() instanceof ServerLevel level)) {
            return "";
        }
        BlockPos origin = companion.blockPosition();
        Pos here = new Pos(origin.getX(), origin.getY(), origin.getZ());
        prune(companion);
        PlacedQuery.Result result = PlacedQuery.find(
                book(companion),
                level.dimension().identifier().toString(),
                here,
                PROMPT_RADIUS,
                entry -> true,
                PROMPT_ENTRIES);
        if (result.total() == 0) {
            return "";
        }
        List<PlacedText.Group> groups = PlacedText.group(result.entries(), entry -> {
            int distance = (int) Math.round(Math.sqrt(PlacedQuery.distanceSquared(here, entry.pos())));
            return distance + " blocks " + RelativeDirection.compass(
                    entry.pos().x() - origin.getX(), entry.pos().z() - origin.getZ());
        });
        if (groups.size() > PROMPT_GROUPS) {
            groups = groups.subList(0, PROMPT_GROUPS);
        }
        return PlacedText.prompt(groups, PROMPT_CHARS);
    }

    /** The recorded blocks matching {@code matcher} within {@code radius} of the companion, nearest first. */
    public static PlacedQuery.Result find(
            CompanionEntity companion, BlockMatcher matcher, int radius, int limit) {
        prune(companion);
        if (!(companion.level() instanceof ServerLevel level)) {
            return new PlacedQuery.Result(List.of(), 0, false);
        }
        BlockPos origin = companion.blockPosition();
        return PlacedQuery.find(
                book(companion),
                level.dimension().identifier().toString(),
                new Pos(origin.getX(), origin.getY(), origin.getZ()),
                radius,
                entry -> matches(matcher, entry.blockId()),
                limit);
    }

    static boolean matches(BlockMatcher matcher, String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        return id != null && BuiltInRegistries.BLOCK.containsKey(id)
                && matcher.test(BuiltInRegistries.BLOCK.getValue(id).defaultBlockState());
    }

    /** True when the recorded block still stands at its cell; an unloaded chunk counts as unknown, not gone. */
    public static boolean stillThere(ServerLevel level, PlacedBlock entry) {
        BlockPos pos = new BlockPos(entry.pos().x(), entry.pos().y(), entry.pos().z());
        if (!level.hasChunkAt(pos)) {
            return true;
        }
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString()
                .equals(entry.blockId());
    }
}
