package dev.friendai.features.placements.minecraft;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.features.navigation.RelativeDirection;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.placements.*;
import dev.friendai.task.interact.ContainerAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The server-thread facade over the owner's placement ledger. It receives an owner-book provider at construction;
 * recording happens at the one choke point every placement path already uses, {@code CompanionHands.place}. This
 * class only turns a world fact into an entry and answers queries. A companion without an owner records nothing.
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

    private final Function<UUID, PlacedBook> books;

    public PlacedService(Function<UUID, PlacedBook> books) {
        this.books = java.util.Objects.requireNonNull(books, "books");
    }

    /** Isolated ledger used by catalog-only tool registries that have no server persistence. */
    public static PlacedService unavailable() {
        return new PlacedService(ignored -> new PlacedBook(PlacedLimits.DEFAULTS));
    }

    /** The owner's book, or a fresh empty one when the companion has no owner. */
    public PlacedBook book(CompanionEntity companion) {
        UUID owner = companion.ownerUuid();
        if (owner == null || !(companion.level() instanceof ServerLevel level)) {
            return new PlacedBook(PlacedLimits.DEFAULTS);
        }
        return books.apply(owner);
    }

    /** Records one successful placement; a companion without an owner is not recorded. */
    public void record(CompanionEntity companion, BlockPos pos, BlockState state, String source) {
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
        books.apply(owner).record(block);
    }

    /** Forgets the entry for one cell, after the block was broken or taken back. */
    public void forget(CompanionEntity companion, BlockPos pos) {
        if (!(companion.level() instanceof ServerLevel level)) {
            return;
        }
        UUID owner = companion.ownerUuid();
        if (owner == null) {
            return;
        }
        books.apply(owner)
                .remove(level.dimension().identifier().toString(), new Pos(pos.getX(), pos.getY(), pos.getZ()));
    }

    /** Drops entries whose loaded chunk no longer holds the recorded block; unloaded chunks are kept. */
    public int prune(CompanionEntity companion) {
        return prune(companion, PRUNE_BUDGET);
    }

    public int prune(CompanionEntity companion, int budget) {
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
            BlockPos pos =
                    new BlockPos(entry.pos().x(), entry.pos().y(), entry.pos().z());
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            String current = BuiltInRegistries.BLOCK
                    .getKey(level.getBlockState(pos).getBlock())
                    .toString();
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
    public String promptBlock(CompanionEntity companion, Caller caller) {
        if (caller == null
                || !caller.access().allows(AccessLevel.TRUSTED)
                || !(companion.level() instanceof ServerLevel level)) {
            return "";
        }
        BlockPos origin = companion.blockPosition();
        Pos here = new Pos(origin.getX(), origin.getY(), origin.getZ());
        PlacedQuery.Result result = findAt(companion, null, here, PROMPT_RADIUS, PROMPT_ENTRIES);
        if (result.total() == 0) {
            return "";
        }
        List<PlacedText.Group> groups = PlacedText.group(result.entries(), entry -> {
            int distance = (int) Math.round(Math.sqrt(PlacedQuery.distanceSquared(here, entry.pos())));
            return distance + " blocks "
                    + RelativeDirection.compass(
                            entry.pos().x() - origin.getX(), entry.pos().z() - origin.getZ());
        });
        if (groups.size() > PROMPT_GROUPS) {
            groups = groups.subList(0, PROMPT_GROUPS);
        }
        return PlacedText.prompt(groups, PROMPT_CHARS);
    }

    /** The recorded blocks matching {@code matcher} (or every block when null) within radius, nearest first. */
    public PlacedQuery.Result find(CompanionEntity companion, BlockMatcher matcher, int radius, int limit) {
        BlockPos origin = companion.blockPosition();
        return findAt(companion, matcher, new Pos(origin.getX(), origin.getY(), origin.getZ()), radius, limit);
    }

    /** As {@link #find}, around an explicit origin. */
    public PlacedQuery.Result findAt(
            CompanionEntity companion, BlockMatcher matcher, Pos origin, int radius, int limit) {
        prune(companion);
        if (!(companion.level() instanceof ServerLevel level)) {
            return new PlacedQuery.Result(List.of(), 0, false);
        }
        return PlacedQuery.find(
                book(companion),
                level.dimension().identifier().toString(),
                origin,
                radius,
                entry -> (matcher == null || matches(matcher, entry.blockId())) && stillThere(level, entry),
                limit);
    }

    /**
     * Candidate cells for {@code mine_blocks(placed=true)}: only recorded blocks that still hold their block in a
     * loaded chunk, nearest first. An unplaced block is never a candidate, even when it sits beside one.
     */
    public List<BlockPos> candidates(
            CompanionEntity companion, BlockMatcher matcher, BlockPos origin, int radius, int limit) {
        if (!(companion.level() instanceof ServerLevel level)) {
            return List.of();
        }
        BlockPos center = origin != null ? origin : companion.blockPosition();
        PlacedQuery.Result result =
                findAt(companion, matcher, new Pos(center.getX(), center.getY(), center.getZ()), radius, limit);
        List<BlockPos> cells = new ArrayList<>(result.entries().size());
        for (PlacedBlock entry : result.entries()) {
            BlockPos pos = toBlockPos(entry);
            if (level.hasChunkAt(pos) && matcher.test(level.getBlockState(pos))) {
                cells.add(pos);
            }
        }
        return cells;
    }

    /** The nearest recorded block within {@code radius}, if any, for a pickup centred on it. */
    public Optional<BlockPos> nearestCenter(CompanionEntity companion, int radius) {
        PlacedQuery.Result result = find(companion, null, radius, 1);
        return result.entries().isEmpty()
                ? Optional.empty()
                : Optional.of(toBlockPos(result.entries().getFirst()));
    }

    /** The nearest recorded block that is still a storage container, for {@code store_items(placed=true)}. */
    public Optional<BlockPos> nearestContainer(CompanionEntity companion, int radius) {
        if (!(companion.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        PlacedQuery.Result result = find(companion, null, radius, Integer.MAX_VALUE);
        for (PlacedBlock entry : result.entries()) {
            BlockPos pos = toBlockPos(entry);
            if (level.hasChunkAt(pos) && ContainerAccess.at(level, pos).isPresent()) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    private static BlockPos toBlockPos(PlacedBlock entry) {
        return new BlockPos(entry.pos().x(), entry.pos().y(), entry.pos().z());
    }

    static boolean matches(BlockMatcher matcher, String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        return id != null
                && BuiltInRegistries.BLOCK.containsKey(id)
                && matcher.test(BuiltInRegistries.BLOCK.getValue(id).defaultBlockState());
    }

    /** True when the recorded block still stands at its cell; an unloaded chunk counts as unknown, not gone. */
    public boolean stillThere(ServerLevel level, PlacedBlock entry) {
        BlockPos pos =
                new BlockPos(entry.pos().x(), entry.pos().y(), entry.pos().z());
        if (!level.hasChunkAt(pos)) {
            return true;
        }
        return BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock())
                .toString()
                .equals(entry.blockId());
    }
}
