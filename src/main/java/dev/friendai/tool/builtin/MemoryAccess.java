package dev.friendai.tool.builtin;

import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryLimits;
import java.util.UUID;

/** The tools' view of long-term memory: whether it is on, its limits, and the owner's book. */
public interface MemoryAccess {
    boolean enabled();

    MemoryLimits limits();

    MemoryBook book(UUID owner);

    MemoryAccess DISABLED = new MemoryAccess() {
        private final MemoryBook empty = new MemoryBook();

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public MemoryLimits limits() {
            return MemoryLimits.DEFAULTS;
        }

        @Override
        public MemoryBook book(UUID owner) {
            return empty;
        }
    };
}
