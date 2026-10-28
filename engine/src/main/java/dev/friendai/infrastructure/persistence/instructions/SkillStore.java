package dev.friendai.infrastructure.persistence.instructions;

import dev.friendai.features.instructions.*;
import dev.friendai.features.instructions.SkillFile;
import dev.friendai.infrastructure.persistence.OwnerFileStore;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Owner and global files share the tested atomic writer, with separate scope codecs. */
public final class SkillStore implements SkillRepository, AutoCloseable {
    private static final UUID GLOBAL = new UUID(0, 0);
    private final OwnerFileStore<SkillBook> owners, server;

    public SkillStore(Path dir, SkillLimits limits, UnaryOperator<String> redact) {
        owners = OwnerFileStore.withWriterThread(dir, new SkillFile(limits, redact, InstructionSkill.Scope.COMPANION));
        server = OwnerFileStore.withWriterThread(
                dir, new SkillFile(limits, redact, InstructionSkill.Scope.SERVER), id -> "server.json");
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
        owners.close();
        server.close();
    }
}
