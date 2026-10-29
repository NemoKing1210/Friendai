package dev.friendai.infrastructure.persistence.instructions;

import dev.friendai.features.instructions.*;
import dev.friendai.features.instructions.SkillFile;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.infrastructure.persistence.OwnerFileStore;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Owner and global files share the tested atomic writer, with separate scope codecs. */
public final class SkillStore implements SkillRepository, AutoCloseable {
    private static final UUID GLOBAL = new UUID(0, 0);
    private final OwnerFileStore<SkillBook> owners, server;
    private final OwnerStoreWriter ownedWriter;

    public SkillStore(Path dir, SkillLimits limits, UnaryOperator<String> redact) {
        this(dir, limits, redact, new OwnerStoreWriter(), true);
    }

    public SkillStore(Path dir, SkillLimits limits, UnaryOperator<String> redact, OwnerStoreWriter writer) {
        this(dir, limits, redact, writer, false);
    }

    private SkillStore(
            Path dir, SkillLimits limits, UnaryOperator<String> redact, OwnerStoreWriter writer, boolean owned) {
        this(dir, limits, redact, OwnerFileStore.Factory.borrowing(writer), owned ? writer : null);
    }

    public SkillStore(Path dir, SkillLimits limits, UnaryOperator<String> redact, OwnerFileStore.Factory stores) {
        this(dir, limits, redact, stores, null);
    }

    private SkillStore(
            Path dir,
            SkillLimits limits,
            UnaryOperator<String> redact,
            OwnerFileStore.Factory stores,
            OwnerStoreWriter ownedWriter) {
        this.ownedWriter = ownedWriter;
        owners = stores.create(dir, new SkillFile(limits, redact, InstructionSkill.Scope.COMPANION));
        server = stores.create(dir, new SkillFile(limits, redact, InstructionSkill.Scope.SERVER))
                .withFileNames(id -> "server.json");
    }

    public StoreWriteStatus status(UUID owner) {
        if (GLOBAL.equals(owner)) throw new IllegalArgumentException("Invalid owner.");
        return owners.status(owner);
    }

    public StoreWriteStatus globalStatus() {
        return server.status(GLOBAL);
    }

    public SkillBook book(UUID owner) {
        if (GLOBAL.equals(owner)) throw new IllegalArgumentException("Invalid owner.");
        return owners.book(owner);
    }

    public SkillBook global() {
        return server.book(GLOBAL);
    }

    public void flushIfDue() {
        owners.flushIfDue();
        server.flushIfDue();
    }

    public void flushAll() {
        owners.flushAll();
        server.flushAll();
    }

    @Override
    public void close() {
        try {
            owners.close();
        } finally {
            try {
                server.close();
            } finally {
                if (ownedWriter != null) ownedWriter.close();
            }
        }
    }
}
