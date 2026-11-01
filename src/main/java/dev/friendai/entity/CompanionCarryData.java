package dev.friendai.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.friendai.FriendaiMod;
import dev.friendai.features.survival.DeathRecord;
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
    /**
     * The format version of this store; a newer one is not guessed. Version 2 adds the recovery facts to a death:
     * the item entities it spawned, the stack fallback and the respawn point. Version 1 files read with no drops.
     */
    static final int SAVE_VERSION = 2;

    /** How many deaths are kept per owner; the prompt renders only the newest. */
    public static final int MAX_DEATHS = 5;

    private static final Codec<DeathRecord.Drops> DROPS_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING
                            .listOf()
                            .optionalFieldOf("entities", List.of())
                            .forGetter(drops -> drops.entities().stream()
                                    .map(UUID::toString)
                                    .toList()),
                    Codec.STRING.listOf().optionalFieldOf("stacks", List.of()).forGetter(DeathRecord.Drops::stacks),
                    Codec.BOOL.optionalFieldOf("unrecoverable", false).forGetter(DeathRecord.Drops::unrecoverable))
            .apply(
                    instance,
                    (entities, stacks, unrecoverable) ->
                            new DeathRecord.Drops(uuids(entities), stacks, unrecoverable)));

    private static final Codec<DeathRecord.Respawn> RESPAWN_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                            Codec.STRING.optionalFieldOf("dimension", "").forGetter(DeathRecord.Respawn::dimension),
                            Codec.INT.optionalFieldOf("x", 0).forGetter(DeathRecord.Respawn::x),
                            Codec.INT.optionalFieldOf("y", 0).forGetter(DeathRecord.Respawn::y),
                            Codec.INT.optionalFieldOf("z", 0).forGetter(DeathRecord.Respawn::z))
                    .apply(instance, DeathRecord.Respawn::new));

    private static final Codec<DeathRecord> DEATH_RECORD_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.optionalFieldOf("dimension", "").forGetter(DeathRecord::dimension),
                    Codec.INT.optionalFieldOf("x", 0).forGetter(DeathRecord::x),
                    Codec.INT.optionalFieldOf("y", 0).forGetter(DeathRecord::y),
                    Codec.INT.optionalFieldOf("z", 0).forGetter(DeathRecord::z),
                    Codec.STRING.optionalFieldOf("cause", "").forGetter(DeathRecord::cause),
                    Codec.STRING.optionalFieldOf("killer", "").forGetter(DeathRecord::killer),
                    Codec.LONG.optionalFieldOf("tick", 0L).forGetter(DeathRecord::tick),
                    DROPS_CODEC.optionalFieldOf("drops").forGetter(death -> Optional.of(death.drops())),
                    RESPAWN_CODEC.optionalFieldOf("respawn").forGetter(death -> Optional.ofNullable(death.respawn())))
            .apply(
                    instance,
                    (dimension, x, y, z, cause, killer, tick, drops, respawn) -> new DeathRecord(
                            dimension,
                            x,
                            y,
                            z,
                            cause,
                            killer,
                            tick,
                            drops.orElse(DeathRecord.Drops.NONE),
                            respawn.orElse(null))));

    private record OwnerCarry(CompoundTag body, List<DeathRecord> deaths, boolean refusedRecovery) {
        private static final Codec<OwnerCarry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        CompoundTag.CODEC
                                .optionalFieldOf("body", new CompoundTag())
                                .forGetter(OwnerCarry::body),
                        DEATH_RECORD_CODEC
                                .listOf()
                                .optionalFieldOf("deaths", List.of())
                                .forGetter(OwnerCarry::deaths),
                        Codec.BOOL.optionalFieldOf("refused_recovery", false).forGetter(OwnerCarry::refusedRecovery))
                .apply(instance, OwnerCarry::new));

        private OwnerCarry {
            body = body == null ? new CompoundTag() : body;
            deaths = List.copyOf(deaths == null ? List.of() : deaths);
        }

        private OwnerCarry withBody(CompoundTag next) {
            return new OwnerCarry(next, deaths, refusedRecovery);
        }

        private OwnerCarry withDeaths(List<DeathRecord> next) {
            return new OwnerCarry(body, next, refusedRecovery);
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

    /** Parses stored ids, skipping anything corrupted rather than dropping the whole record. */
    private static List<UUID> uuids(List<String> stored) {
        List<UUID> ids = new ArrayList<>(stored.size());
        for (String id : stored) {
            try {
                ids.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // A hand-edited file keeps its other drops.
            }
        }
        return ids;
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
        owners.put(owner, new OwnerCarry(body, deaths, false));
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
        owners.put(owner, current.withBody(new CompoundTag()));
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

    /** The newest death that still has drops to fetch, empty when there is nothing to go back for. */
    public Optional<DeathRecord> openDrops(UUID owner) {
        return lastDeath(owner).filter(death -> !death.drops().empty());
    }

    /** Marks the drops as dealt with (collected, gone or forbidden) and keeps the death itself in the log. */
    public void clearDrops(UUID owner) {
        if (readOnly) {
            return;
        }
        OwnerCarry current = owners.get(owner);
        if (current == null || current.deaths().isEmpty()) {
            return;
        }
        List<DeathRecord> deaths = new ArrayList<>(current.deaths());
        DeathRecord newest = deaths.getLast();
        deaths.set(
                deaths.size() - 1,
                new DeathRecord(
                        newest.dimension(),
                        newest.x(),
                        newest.y(),
                        newest.z(),
                        newest.cause(),
                        newest.killer(),
                        newest.tick(),
                        DeathRecord.Drops.NONE,
                        newest.respawn()));
        owners.put(owner, current.withDeaths(deaths));
        setDirty();
    }

    /** Whether the owner told the companion not to go back for its last death. */
    public boolean refusedRecovery(UUID owner) {
        OwnerCarry current = owners.get(owner);
        return current != null && current.refusedRecovery();
    }

    /** Sets or clears the owner's refusal; a later death clears it by itself. */
    public void setRefuseRecovery(UUID owner, boolean refused) {
        if (readOnly) {
            return;
        }
        OwnerCarry current = owners.get(owner);
        if (current == null) {
            owners.put(owner, new OwnerCarry(new CompoundTag(), List.of(), refused));
        } else if (current.refusedRecovery() != refused) {
            owners.put(owner, new OwnerCarry(current.body(), current.deaths(), refused));
        } else {
            return;
        }
        setDirty();
    }

    /** One prompt line for the newest death, or an empty string when there is none. */
    public String lastDeathLine(UUID owner) {
        return lastDeath(owner)
                .map(death -> "Last death: " + death.describe() + ".")
                .orElse("");
    }
}
