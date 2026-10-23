package dev.friedai.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Keeps track of loaded companions and guarantees one per owner. */
public final class CompanionManager {
    public interface Listener {
        void onRemoved(UUID owner);
    }

    private static final Map<MinecraftServer, CompanionManager> INSTANCES = new WeakHashMap<>();

    private final Map<UUID, CompanionEntity> byOwner = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    private CompanionManager() {}

    public static synchronized CompanionManager get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new CompanionManager());
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
        if (existing != null && existing != entity && !existing.isRemoved()) {
            entity.discard();
            return;
        }
        byOwner.put(owner, entity);
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

    /** Spawns a companion next to the owner, or brings the existing one over. */
    public CompanionEntity summon(ServerPlayer owner) {
        ServerLevel level = (ServerLevel) owner.level();
        Vec3 target = owner.position().add(owner.getLookAngle().multiply(1.5, 0, 1.5));
        Optional<CompanionEntity> existing = find(owner.getUUID());
        if (existing.isPresent()) {
            CompanionEntity companion = existing.get();
            if (companion.level() == level) {
                companion.teleportTo(target.x, owner.getY(), target.z);
            } else {
                companion.teleport(new TeleportTransition(
                        level, new Vec3(target.x, owner.getY(), target.z), Vec3.ZERO,
                        owner.getYRot(), 0.0F, TeleportTransition.DO_NOTHING));
            }
            return companion;
        }
        CompanionEntity companion = FriedaiEntities.COMPANION.create(level, EntitySpawnReason.COMMAND);
        companion.setOwner(owner.getUUID());
        companion.snapTo(target.x, owner.getY(), target.z, owner.getYRot() + 180.0F, 0.0F);
        level.addFreshEntity(companion);
        register(companion);
        return companion;
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
