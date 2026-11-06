package dev.friendai.server.agent.coordination;

import dev.friendai.agent.coordination.HandoffRequest;
import dev.friendai.agent.coordination.HelpRequest;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.coordination.minecraft.tool.CoordinationAccess;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** The live coordination books of one server, as the coordination tools see them. */
public final class CoordinationToolAccess implements CoordinationAccess {
    private final MinecraftServer server;

    public CoordinationToolAccess(MinecraftServer server) {
        this.server = Objects.requireNonNull(server);
    }

    private Optional<ServerRuntime> runtime() {
        return ServerRuntimeRegistry.find(server);
    }

    @Override
    public boolean available() {
        return runtime().isPresent();
    }

    @Override
    public Optional<OnlineOwner> onlineOwner(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        ServerPlayer player = server.getPlayerList().getPlayerByName(name.strip());
        return player == null
                ? Optional.empty()
                : Optional.of(new OnlineOwner(player.getUUID(), player.getName().getString()));
    }

    @Override
    public boolean requestHelp(HelpRequest request) {
        return runtime()
                .map(runtime -> runtime.coordination().requestHelp(request))
                .orElse(false);
    }

    @Override
    public Optional<HelpRequest> acceptHelp(UUID fromOwner, UUID toOwner, long now) {
        return runtime().flatMap(runtime -> runtime.coordination().acceptHelp(fromOwner, toOwner, now));
    }

    @Override
    public boolean declineHelp(UUID fromOwner, UUID toOwner) {
        return runtime()
                .map(runtime -> runtime.coordination().declineHelp(fromOwner, toOwner))
                .orElse(false);
    }

    @Override
    public boolean offerHandoff(HandoffRequest request) {
        return runtime()
                .map(runtime -> runtime.coordination().offerHandoff(request))
                .orElse(false);
    }

    @Override
    public Optional<HandoffRequest> takeHandoff(UUID fromOwner, UUID toOwner, long now) {
        return runtime().flatMap(runtime -> runtime.coordination().takeHandoff(fromOwner, toOwner, now));
    }

    @Override
    public Optional<CompanionEntity> companion(UUID owner) {
        return runtime().flatMap(runtime -> runtime.companions().find(owner));
    }

    @Override
    public void requestReplan(UUID owner, long planId, String reason) {
        runtime().ifPresent(runtime -> runtime.sessions().post(owner, new BrainEvent.Replan(planId, reason)));
    }
}
