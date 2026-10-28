package dev.friendai.features.instructions;

import java.util.UUID;

/** Persistence boundary for owner and server instruction books. */
public interface SkillRepository {
    SkillBook book(UUID owner);

    SkillBook global();
}
