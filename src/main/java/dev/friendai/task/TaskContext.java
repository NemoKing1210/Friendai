package dev.friendai.task;

import dev.friendai.entity.CompanionEntity;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public record TaskContext(CompanionEntity companion, ServerLevel level) {
    public Optional<ServerPlayer> owner() {
        return companion.owner();
    }
}
