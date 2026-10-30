package dev.friendai.features.memory.minecraft.tool;

import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.transcript.TranscriptBook;
import dev.friendai.features.memory.transcript.TranscriptLimits;
import dev.friendai.foundation.diagnostics.DebugLog;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** The self-knowledge tools' view of the server: the owner's log, transcript, memory and the prompt budget. */
public interface SelfAccess {
    boolean enabled();

    /** The owner the companion's records belong to. */
    default UUID owner(dev.friendai.entity.CompanionEntity companion) {
        return companion.ownerUuid();
    }

    DebugLog log(UUID owner);

    TranscriptBook transcript(UUID owner);

    /** The current config bounds, so a lowered retention is enforced on read. */
    TranscriptLimits transcriptLimits();

    MemoryBook book(UUID owner);

    MemoryLimits memoryLimits();

    /** The system prompt budget; zero means unlimited. */
    int promptBudget();

    /** Fill share of a facts book at which it is reported as full. */
    int compactAtPercent();

    /** Whether compress_memory may apply a plan (a dry run is always allowed). */
    boolean compactionEnabled();

    UnaryOperator<String> redactor();

    long now();

    /** The largest text one tool result may return: a quarter of the prompt, between 1 200 and 6 000 characters. */
    default int resultBudget() {
        int budget = promptBudget();
        return budget <= 0 ? 6_000 : Math.min(6_000, Math.max(1_200, budget / 4));
    }

    /** Registry-only default: the self-knowledge tools stay absent unless the server supplies the capability. */
    SelfAccess DISABLED = new SelfAccess() {
        private final DebugLog log = new DebugLog(1, 1, text -> text, () -> 0L);
        private final TranscriptBook transcript = new TranscriptBook(new TranscriptLimits(1, 1, 0, 1024));
        private final MemoryBook book = new MemoryBook();

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public DebugLog log(UUID owner) {
            return log;
        }

        @Override
        public TranscriptBook transcript(UUID owner) {
            return transcript;
        }

        @Override
        public TranscriptLimits transcriptLimits() {
            return TranscriptLimits.DEFAULTS;
        }

        @Override
        public MemoryBook book(UUID owner) {
            return book;
        }

        @Override
        public MemoryLimits memoryLimits() {
            return MemoryLimits.DEFAULTS;
        }

        @Override
        public int promptBudget() {
            return 0;
        }

        @Override
        public int compactAtPercent() {
            return 100;
        }

        @Override
        public boolean compactionEnabled() {
            return false;
        }

        @Override
        public UnaryOperator<String> redactor() {
            return text -> text;
        }

        @Override
        public long now() {
            return 0L;
        }
    };
}
