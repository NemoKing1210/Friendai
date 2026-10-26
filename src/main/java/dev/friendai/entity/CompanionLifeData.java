package dev.friendai.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.friendai.FriendaiMod;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Per-owner lifecycle preferences and bed checkpoints, persisted independently from companion entities. */
public final class CompanionLifeData extends SavedData {
    public record RespawnPoint(ResourceKey<Level> dimension, BlockPos bed) {}

    private record OwnerState(boolean autoRespawn, String respawnPoint) {
        static final Codec<OwnerState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("autoRespawn", true).forGetter(OwnerState::autoRespawn),
                Codec.STRING.optionalFieldOf("respawnPoint", "").forGetter(OwnerState::respawnPoint))
                .apply(instance, OwnerState::new));
    }

    private static final Codec<CompanionLifeData> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, OwnerState.CODEC)
            .xmap(CompanionLifeData::new, data -> data.owners);
    public static final SavedDataType<CompanionLifeData> TYPE = new SavedDataType<>(
            FriendaiMod.id("companion_life"), CompanionLifeData::new, CODEC, DataFixTypes.LEVEL);

    private final Map<UUID, OwnerState> owners = new HashMap<>();

    public CompanionLifeData() {}

    private CompanionLifeData(Map<UUID, OwnerState> owners) {
        this.owners.putAll(owners);
    }

    public static CompanionLifeData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public boolean autoRespawn(UUID owner) {
        return owners.getOrDefault(owner, new OwnerState(true, "")).autoRespawn();
    }

    public Optional<RespawnPoint> respawnPoint(UUID owner) {
        String saved = owners.getOrDefault(owner, new OwnerState(true, "")).respawnPoint();
        if (saved.isBlank()) {
            return Optional.empty();
        }
        int separator = saved.lastIndexOf('|');
        if (separator <= 0 || separator == saved.length() - 1) {
            return Optional.empty();
        }
        Identifier dimension = Identifier.tryParse(saved.substring(0, separator));
        if (dimension == null) {
            return Optional.empty();
        }
        try {
            long packedBed = Long.parseLong(saved.substring(separator + 1));
            return Optional.of(new RespawnPoint(
                    ResourceKey.create(Registries.DIMENSION, dimension), BlockPos.of(packedBed)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public void setAutoRespawn(UUID owner, boolean enabled) {
        OwnerState current = owners.getOrDefault(owner, new OwnerState(true, ""));
        if (current.autoRespawn() != enabled) {
            owners.put(owner, new OwnerState(enabled, current.respawnPoint()));
            setDirty();
        }
    }

    public void setRespawnPoint(UUID owner, RespawnPoint point) {
        OwnerState current = owners.getOrDefault(owner, new OwnerState(true, ""));
        String next = point == null ? "" : point.dimension().identifier() + "|" + point.bed().asLong();
        if (!current.respawnPoint().equals(next)) {
            owners.put(owner, new OwnerState(current.autoRespawn(), next));
            setDirty();
        }
    }
}
