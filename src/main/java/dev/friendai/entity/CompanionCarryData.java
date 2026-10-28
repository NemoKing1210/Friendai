package dev.friendai.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.friendai.FriendaiMod;
import dev.friendai.survival.DeathRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * What a companion carries through death, plus the last few deaths themselves, stored per owner.
 *
 * <p>The body is the same NBT a world save writes for the entity's inventory, equipment, hunger, XP,
 * settings and task queue, captured before the entity is removed and applied to the replacement. The
 * record outlives an automatic respawn so that "auto-respawn off, summon later" restores the same
 * state, and it survives a server restart because it is a {@link SavedData}. A death log is capped:
 * the model only ever needs the most recent one.
 */
public final class CompanionCarryData extends SavedData {
    /** The format version of this store; a newer one is not guessed. */
    static final int SAVE_VERSION = 1;

    /** How many deaths are kept per owner; the prompt renders only the newest. */
    public static final int MAX_DEATHS = 5;

    private record OwnerCarry(CompoundTag body, List<DeathRecord> deaths) {
        private static final Codec<OwnerCarry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        CompoundTag.CODEC
                                .optionalFieldOf("body", new CompoundTag())
                                .forGetter(OwnerCarry::body),
                        DeathRecord.CODEC
                                .listOf()
                                .optionalFieldOf("deaths", List.of())
                                .forGetter(OwnerCarry::deaths))
                .apply(instance, OwnerCarry::new));

        private OwnerCarry {
            body = body == null ? new CompoundTag() : body;
            deaths = List.copyOf(deaths == null ? List.of() : deaths);
        }

        private boolean hasBody() {
            return !body.isEmpty();
        }
    }

    private static final Codec<CompanionCarryData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.optionalFieldOf("version", SAVE_VERSION).forGetter(data -> data.version),
                    Codec.unboundedMap(UUIDUtil.STRING_CODEC, OwnerCarry.CODEC)
                            .optionalFieldOf("owners", Map.of())
                            .forGetter(data -> data.owners))
            .apply(instance, CompanionCarryData::new));

    public static final SavedDataType<CompanionCarryData> TYPE =
            new SavedDataType<>(FriendaiMod.id("companion_carry"), CompanionCarryData::new, CODEC, DataFixTypes.LEVEL);

    private final Map<UUID, OwnerCarry> owners;
    private final int version;
    /** A file written by a newer build is read but never overwritten. */
    private final boolean readOnly;

    public CompanionCarryData() {
        this(SAVE_VERSION, Map.of());
    }

    private CompanionCarryData(int version, Map<UUID, OwnerCarry> owners) {
        if (version > SAVE_VERSION) {
            FriendaiMod.LOGGER.warn(
                    "Companion carry data has format {} but this build reads {}; keeping it read-only",
                    version,
                    SAVE_VERSION);
            this.version = version;
            this.owners = new HashMap<>();
            this.readOnly = true;
            return;
        }
        this.version = SAVE_VERSION;
        this.owners = new HashMap<>(owners == null ? Map.of() : owners);
        this.readOnly = false;
    }

    public static CompanionCarryData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Stores the body captured at death and appends the death, keeping only the newest {@link #MAX_DEATHS}. */
    public void record(UUID owner, CompoundTag body, DeathRecord death) {
        if (owner == null || readOnly) {
            return;
        }
        OwnerCarry current = owners.get(owner);
        List<DeathRecord> deaths = new ArrayList<>(current == null ? List.of() : current.deaths());
        if (death != null) {
            deaths.add(death);
            while (deaths.size() > MAX_DEATHS) {
                deaths.removeFirst();
            }
        }
        owners.put(owner, new OwnerCarry(body, deaths));
        setDirty();
    }

    /** The body waiting to be applied to the owner's next companion, if any. */
    public Optional<CompoundTag> carry(UUID owner) {
        OwnerCarry current = owners.get(owner);
        return current != null && current.hasBody() ? Optional.of(current.body().copy()) : Optional.empty();
    }

    /** Drops the pending body but keeps the death log: the state was applied, the owner still remembers dying. */
    public void clearCarry(UUID owner) {
        if (readOnly) {
            return;
        }
        OwnerCarry current = owners.get(owner);
        if (current == null || !current.hasBody()) {
            return;
        }
        owners.put(owner, new OwnerCarry(new CompoundTag(), current.deaths()));
        setDirty();
    }

    /** How many deaths this build has recorded for the owner, capped at {@link #MAX_DEATHS}. */
    public int deathCount(UUID owner) {
        OwnerCarry current = owners.get(owner);
        return current == null ? 0 : current.deaths().size();
    }

    /** The newest recorded death, empty when the companion has never died in this world. */
    public Optional<DeathRecord> lastDeath(UUID owner) {
        OwnerCarry current = owners.get(owner);
        if (current == null || current.deaths().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(current.deaths().getLast());
    }

    /** One prompt line for the newest death, or an empty string when there is none. */
    public String lastDeathLine(UUID owner) {
        return lastDeath(owner)
                .map(death -> "Last death: " + death.describe() + ".")
                .orElse("");
    }
}
