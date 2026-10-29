package dev.friendai.infrastructure.persistence;

import dev.friendai.foundation.persistence.StoreCodec;
import dev.friendai.foundation.persistence.StoreReadOnlyReason;
import java.nio.charset.StandardCharsets;

/** Domain preparation around the shared owner-file lifecycle. */
public interface OwnerFilePolicy<T> {
    default byte[] encode(T value, StoreCodec<T> codec) {
        return codec.save(value).getBytes(StandardCharsets.UTF_8);
    }

    default boolean emptyOnUnsupportedVersion() {
        return false;
    }

    default boolean canRepair(StoreReadOnlyReason reason) {
        return false;
    }

    default boolean backupOversizedOnLoad() {
        return true;
    }
}
