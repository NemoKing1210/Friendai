package dev.friendai.features.memory.minecraft.tool;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.memory.MemoryActions;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryLimits;
import java.util.Optional;

/** The memory capabilities exposed to the model-facing memory tools. */
public interface MemoryAccess {
    /** Whether long-term memory is enabled for this server. */
    boolean enabled();

    /** Builds caller-scoped actions for the companion and current turn. */
    MemoryActions actions(CompanionEntity companion, Caller caller);

    /** Registry-only defaults keep memory tools absent unless the server explicitly supplies the memory capability. */
    MemoryAccess DISABLED = new MemoryAccess() {
        private final MemoryBook book = new MemoryBook();

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public MemoryActions actions(CompanionEntity companion, Caller caller) {
            return new MemoryActions(
                    book, companion.ownerUuid(), caller, MemoryLimits.DEFAULTS, 0, name -> Optional.empty());
        }
    };
}
