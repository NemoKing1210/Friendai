package dev.friendai.entity;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.task.builtin.FollowTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Keeps track of loaded companions and guarantees one per owner. */
public final class CompanionManager {
    public interface Listener {
        void onRemoved(UUID owner);
    }

    private static final Map<MinecraftServer, CompanionManager> INSTANCES = new WeakHashMap<>();

    private final MinecraftServer server;
    private final Map<UUID, CompanionEntity> byOwner = new HashMap<>();
    private final Map<UUID, UUID> pendingDeaths = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    private CompanionManager(MinecraftServer server) {
        this.server = server;
    }

    public static synchronized CompanionManager get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, CompanionManager::new);
    }

    public Optional<CompanionEntity> find(UUID owner) {
        return Optional.ofNullable(byOwner.get(owner)).filter(e -> !e.isRemoved());
    }

    /** Loaded companions that have not been removed. */
    public List<CompanionEntity> all() {
        return byOwner.values().stream().filter(e -> !e.isRemoved()).toList();
    }

    /** Indexes a loaded companion. A second companion of the same owner is discarded. */
    public void register(CompanionEntity entity) {
        UUID owner = entity.ownerUuid();
        if (owner == null) {
            return;
        }
        CompanionEntity existing = byOwner.get(owner);
        UUID dyingEntity = pendingDeaths.get(owner);
        if (dyingEntity != null && !dyingEntity.equals(entity.getUUID())) {
            pendingDeaths.remove(owner);
        }
        if (existing != null && existing != entity && !existing.isRemoved()) {
            entity.discard();
            return;
        }
        byOwner.put(owner, entity);
        FriendaiServerState.get(entity.level().getServer())
                .skins()
                .load(owner)
                .ifPresentOrElse(
                        stored -> entity.setSkin(stored.hash(), stored.slim()), () -> entity.setSkin("", false));
        if (entity.hasCustomName()) {
            // Saves made before the name tag was shown carry a name but not the visible flag.
            entity.setCustomNameVisible(true);
        }
    }

    public void unregister(CompanionEntity entity) {
        UUID owner = entity.ownerUuid();
        if (owner != null && byOwner.get(owner) == entity) {
            byOwner.remove(owner);
            new ArrayList<>(listeners).forEach(l -> l.onRemoved(owner));
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Called only from the entity's actual death path; removals and unloads do not qualify. */
    public void onDeath(CompanionEntity companion) {
        UUID owner = companion.ownerUuid();
        if (owner != null) {
            pendingDeaths.put(owner, companion.getUUID());
        }
    }

    /** Replaces eligible dead companions after their entity-unload callback has removed the old index entry. */
    public void tick() {
        CompanionLifeData life = FriendaiServerState.get(server).companionLife();
        for (Map.Entry<UUID, UUID> entry : new ArrayList<>(pendingDeaths.entrySet())) {
            UUID owner = entry.getKey();
            CompanionEntity indexed = byOwner.get(owner);
            if (indexed != null && !indexed.isRemoved()) {
                if (!indexed.getUUID().equals(entry.getValue())) {
                    pendingDeaths.remove(owner);
                }
                continue;
            }
            if (server.isHardcore() || !life.autoRespawn(owner)) {
                pendingDeaths.remove(owner);
                continue;
            }
            if (respawn(owner, life)) {
                pendingDeaths.remove(owner);
            }
        }
    }

    private boolean respawn(UUID owner, CompanionLifeData life) {
        ServerLevel level = server.overworld();
        BlockPos spawn = null;
        CompanionLifeData.RespawnPoint point = life.respawnPoint(owner).orElse(null);
        if (point != null) {
            ServerLevel checkpointLevel = server.getLevel(point.dimension());
            if (checkpointLevel != null) {
                spawn = bedSpawnSpot(checkpointLevel, point.bed());
                if (spawn != null) {
                    level = checkpointLevel;
                }
            }
        }
        if (spawn == null) {
            BlockPos sharedSpawn = level.getSharedSpawnPos();
            spawn = safeSpawnSpot(level, List.of(sharedSpawn));
            if (spawn == null) {
                spawn = sharedSpawn;
            }
        }
        CompanionEntity companion = FriendaiEntities.COMPANION.create(level, EntitySpawnReason.COMMAND);
        if (companion == null) {
            return false;
        }
        companion.setOwner(owner);
        companion.applyName(FriendaiServerState.get(server).companionName(owner));
        companion.snapTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0.0F, 0.0F);
        if (!level.addFreshEntity(companion)) {
            return false;
        }
        register(companion);
        return true;
    }

    @Nullable
    private BlockPos bedSpawnSpot(ServerLevel level, BlockPos foot) {
        if (!level.dimensionType().bedWorks() || !level.hasChunkAt(foot)) {
            return null;
        }
        BlockState footState = level.getBlockState(foot);
        if (!footState.is(BlockTags.BEDS) || !footState.hasProperty(BlockStateProperties.BED_PART)
                || !footState.hasProperty(HorizontalDirectionalBlock.FACING)
                || footState.getValue(BlockStateProperties.BED_PART) != BedPart.FOOT) {
            return null;
        }
        BlockPos head = foot.relative(footState.getValue(HorizontalDirectionalBlock.FACING));
        if (!level.hasChunkAt(head)) {
            return null;
        }
        BlockState headState = level.getBlockState(head);
        if (!headState.is(BlockTags.BEDS) || !headState.hasProperty(BlockStateProperties.BED_PART)
                || !headState.hasProperty(HorizontalDirectionalBlock.FACING)
                || headState.getValue(BlockStateProperties.BED_PART) != BedPart.HEAD
                || headState.getValue(HorizontalDirectionalBlock.FACING)
                        != footState.getValue(HorizontalDirectionalBlock.FACING)
                || !headState.hasProperty(BedBlock.OCCUPIED) || headState.getValue(BedBlock.OCCUPIED)) {
            return null;
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(foot.relative(direction));
            candidates.add(head.relative(direction));
        }
        return safeSpawnSpot(level, candidates);
    }

    private static BlockPos safeSpawnSpot(ServerLevel level, List<BlockPos> candidates) {
        for (BlockPos pos : candidates) {
            BlockPos below = pos.below();
            if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above()) || !level.hasChunkAt(below)) {
                continue;
            }
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                    && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                    && level.getEntities(null, new AABB(pos).expandTowards(0, 1, 0)).isEmpty()) {
                return pos;
            }
        }
        return null;
    }

    /** Spawns a companion next to the owner, or calls an existing companion over on foot. */
    public CompanionEntity summon(ServerPlayer owner) {
        ServerLevel level = (ServerLevel) owner.level();
        Optional<CompanionEntity> existing = find(owner.getUUID());
        if (existing.isPresent()) {
            CompanionEntity companion = existing.get();
            ensureName(companion);
            if (companion.level() == level) {
                companion.taskRunner().start(new FollowTask(false), "follow");
            }
            return companion;
        }
        CompanionEntity companion = FriendaiEntities.COMPANION.create(level, EntitySpawnReason.COMMAND);
        companion.setOwner(owner.getUUID());
        companion.applyName(FriendaiServerState.get(level.getServer()).companionName(owner.getUUID()));
        BlockPos spot = spawnSpot(level, owner);
        companion.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, owner.getYRot() + 180.0F, 0.0F);
        level.addFreshEntity(companion);
        register(companion);
        return companion;
    }

    /** Explicitly moves a loaded companion to its owner, preserving the index across dimension changes. */
    public boolean teleportToOwner(ServerPlayer owner) {
        Optional<CompanionEntity> found = find(owner.getUUID());
        if (found.isEmpty()) {
            return false;
        }
        CompanionEntity companion = found.get();
        ServerLevel level = (ServerLevel) owner.level();
        BlockPos spot = spawnSpot(level, owner);
        Vec3 target = new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        companion.getNavigation().stop();
        if (companion.level() == level) {
            companion.teleportTo(target.x, target.y, target.z);
            return true;
        }
        // Detach without notifying removal listeners: the replacement may load before the old entity unloads.
        byOwner.remove(owner.getUUID());
        Entity moved = companion.teleport(new TeleportTransition(
                level, target, Vec3.ZERO, owner.getYRot(), 0.0F, TeleportTransition.DO_NOTHING));
        if (moved instanceof CompanionEntity next) {
            register(next);
            return true;
        }
        byOwner.put(owner.getUUID(), companion);
        return false;
    }

    /** Tries nearby standing cells before falling back to the owner's own cell. */
    private static BlockPos spawnSpot(ServerLevel level, ServerPlayer owner) {
        Vec3 ahead = owner.position().add(owner.getLookAngle().multiply(1.5, 0, 1.5));
        BlockPos feet = owner.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(BlockPos.containing(ahead));
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(feet.relative(direction));
        }
        candidates.add(feet);
        for (BlockPos pos : candidates) {
            BlockPos below = pos.below();
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                    && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                    && level.getEntities(owner, new AABB(pos).expandTowards(0, 1, 0)).isEmpty()) {
                return pos;
            }
        }
        return feet;
    }

    /** A loaded companion keeps the name it was given; an old save without one gets the owner's current name. */
    private static void ensureName(CompanionEntity companion) {
        if (companion.hasCustomName()) {
            companion.setCustomNameVisible(true);
            return;
        }
        companion.applyName(
                FriendaiServerState.get(companion.level().getServer()).companionName(companion.ownerUuid()));
    }

    /** Removes the owner's companion, dropping what it carries. Returns false if there was none. */
    public boolean dismiss(ServerPlayer owner) {
        Optional<CompanionEntity> existing = find(owner.getUUID());
        if (existing.isEmpty()) {
            return false;
        }
        CompanionEntity companion = existing.get();
        companion.dropInventory();
        companion.discard();
        unregister(companion);
        return true;
    }
}
