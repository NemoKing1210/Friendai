package dev.friendai.features.memory.minecraft.tool;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.memory.PlayerRecord;
import dev.friendai.features.memory.PlayerRef;
import dev.friendai.features.memory.TrustRequest;
import java.util.Optional;
import java.util.UUID;

/** The owner, player lookup, trust status and confirmation capabilities used by trust tools. */
public interface TrustAccess {
    UUID ownerUuid(CompanionEntity companion);

    boolean ownerOnline(CompanionEntity companion);

    Optional<PlayerRef> findOnlinePlayer(String name);

    Optional<PlayerRecord> findKnownPlayer(UUID owner, String name);

    boolean isTrusted(UUID owner, UUID target);

    TrustRequest request(CompanionEntity companion, UUID target, String targetName, boolean trust);
}
