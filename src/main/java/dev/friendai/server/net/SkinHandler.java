package dev.friendai.server.net;

import dev.friendai.FriendaiMod;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.appearance.SkinHash;
import dev.friendai.infrastructure.persistence.appearance.SkinStore.Stored;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.transport.payload.SkinRequestC2SPayload;
import dev.friendai.transport.payload.SkinS2CPayload;
import dev.friendai.transport.payload.SkinUploadC2SPayload;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Serializes appearance I/O on the runtime's store worker and publishes revisions on the server thread. */
public final class SkinHandler {
    private static final int CACHE_SIZE = 64;

    private final ServerRuntime runtime;
    /** Worker-only LRU. Requests for an unchanged appearance reuse its validated revision. */
    private final LinkedHashMap<UUID, Stored> images = new LinkedHashMap<>(16, 0.75f, true);
    /** Server-thread bookkeeping, only for writes that are still awaiting completion. */
    private final Map<UUID, Integer> writes = new HashMap<>();
    /** Saturated submissions are retried for still-loaded companions, one attempt per server tick. */
    private final LinkedHashMap<UUID, CompanionRef> restoreRetries = new LinkedHashMap<>();

    private BiConsumer<ServerPlayer, SkinS2CPayload> sender = this::send;
    private boolean accepting = true;

    public SkinHandler(ServerRuntime runtime) {
        this.runtime = runtime;
    }

    /** Test hook for observing a reply on mock connections without an appearance-capable client. */
    public void setSenderForTest(BiConsumer<ServerPlayer, SkinS2CPayload> replacement) {
        sender = Objects.requireNonNull(replacement);
    }

    public void restore(CompanionEntity companion) {
        var target = new CompanionRef(companion);
        if (accepting && !submitRestore(target)) restoreRetries.put(target.owner(), target);
    }

    private boolean submitRestore(CompanionRef target) {
        return runtime.storeWriter().trySubmit(() -> {
            Optional<Stored> stored = load(target.owner());
            runtime.server().execute(() -> {
                if (current(target)) apply(target.companion(), stored);
            });
        });
    }

    public void onRemoved(CompanionEntity companion) {
        restoreRetries.remove(companion.ownerUuid(), new CompanionRef(companion));
    }

    public void retryRestore() {
        if (restoreRetries.isEmpty()) return;
        var pending = restoreRetries.firstEntry();
        if (!current(pending.getValue()) || submitRestore(pending.getValue())) {
            restoreRetries.pollFirstEntry();
        }
    }

    public void stopAcceptingWork() {
        accepting = false;
        restoreRetries.clear();
        writes.clear();
    }

    /** Sets or resets the sender's appearance after its replacement commits on the store worker. */
    public void upload(ServerPlayer player, SkinUploadC2SPayload payload) {
        UUID owner = player.getUUID();
        if (!accepting || !runtime.protocolConnections().skinLimiter().tryAcquire(owner)) return;
        CompanionEntity companion = runtime.companions().find(owner).orElse(null);
        if (companion == null) {
            player.sendSystemMessage(Component.translatable("friendai.menu.no_companion"));
            return;
        }
        var target = new CompanionRef(companion);
        var requester = new PlayerRef(player, runtime);
        if (runtime.storeWriter().trySubmit(() -> update(target, requester, payload))) {
            writes.merge(owner, 1, Integer::sum);
        } else {
            FriendaiMod.LOGGER.warn("Could not queue companion appearance update for {}", owner);
            player.sendSystemMessage(Component.translatable("friendai.error.unknown"));
        }
    }

    private void update(CompanionRef target, PlayerRef requester, SkinUploadC2SPayload payload) {
        UUID owner = target.owner();
        try {
            if (payload.png().length == 0) {
                runtime.skins().clear(owner);
                images.remove(owner);
                finishUpdate(target, requester, Optional.empty(), null);
            } else {
                Optional<Stored> stored = runtime.skins().save(owner, payload.png(), payload.slim());
                stored.ifPresent(revision -> remember(owner, revision));
                finishUpdate(target, requester, stored, stored.isPresent() ? null : "friendai.skin.error.rejected");
            }
        } catch (UncheckedIOException failure) {
            FriendaiMod.LOGGER.warn("Could not update companion appearance for {}", owner, failure);
            finishUpdate(target, requester, Optional.empty(), "friendai.error.unknown");
        }
    }

    private void finishUpdate(CompanionRef target, PlayerRef requester, Optional<Stored> stored, String error) {
        runtime.server().execute(() -> {
            if (!accepting) return;
            writes.computeIfPresent(target.owner(), (owner, count) -> count == 1 ? null : count - 1);
            if (!current(target)) return;
            if (error == null) {
                apply(target.companion(), stored);
            } else if (current(requester)) {
                requester.player().sendSystemMessage(Component.translatable(error));
            }
        });
    }

    /** Serves a hash only while a loaded companion still wears that exact revision. */
    public void request(ServerPlayer player, SkinRequestC2SPayload payload) {
        String hash = payload.hash();
        if (!accepting
                || !SkinHash.isValid(hash)
                || !runtime.protocolConnections().skinLimiter().tryAcquire(player.getUUID())) return;
        var viewer = new PlayerRef(player, runtime);
        if (viewer.session() == null) return;
        for (CompanionEntity companion : runtime.companions().all()) {
            if (!hash.equals(companion.getSkinHash())
                    || companion.ownerUuid() == null
                    || writes.containsKey(companion.ownerUuid())) continue;
            var target = new CompanionRef(companion);
            boolean slim = companion.isSlimSkin();
            runtime.storeWriter()
                    .trySubmit(() -> load(target.owner())
                            .filter(stored -> hash.equals(stored.hash()) && slim == stored.slim())
                            .ifPresent(stored -> runtime.server().execute(() -> {
                                if (current(viewer)
                                        && current(target)
                                        && !writes.containsKey(target.owner())
                                        && hash.equals(companion.getSkinHash())
                                        && slim == companion.isSlimSkin()) {
                                    sender.accept(player, new SkinS2CPayload(hash, slim, stored.png()));
                                }
                            })));
            return;
        }
    }

    private Optional<Stored> load(UUID owner) {
        Stored cached = images.get(owner);
        if (cached != null) return Optional.of(cached);
        Optional<Stored> stored = runtime.skins().load(owner);
        stored.ifPresent(revision -> remember(owner, revision));
        return stored;
    }

    private void remember(UUID owner, Stored stored) {
        images.put(owner, stored);
        if (images.size() > CACHE_SIZE) images.pollFirstEntry();
    }

    private static void apply(CompanionEntity companion, Optional<Stored> stored) {
        stored.ifPresentOrElse(
                revision -> companion.setSkin(revision.hash(), revision.slim()), () -> companion.setSkin("", false));
    }

    private boolean current(CompanionRef target) {
        return accepting
                && target.companion().level() == target.level()
                && runtime.companions().find(target.owner()).orElse(null) == target.companion();
    }

    private boolean current(PlayerRef player) {
        return accepting
                && player.session() != null
                && player.player().level() == player.level()
                && runtime.protocolConnections()
                        .sessionId(player.player())
                        .filter(player.session()::equals)
                        .isPresent();
    }

    private void send(ServerPlayer player, SkinS2CPayload payload) {
        if (ServerPlayNetworking.canSend(player, SkinS2CPayload.TYPE)
                && runtime.protocolConnections().allowsAppearance(player)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private record CompanionRef(UUID owner, CompanionEntity companion, Level level) {
        private CompanionRef(CompanionEntity companion) {
            this(companion.ownerUuid(), companion, companion.level());
        }
    }

    private record PlayerRef(ServerPlayer player, UUID session, Level level) {
        private PlayerRef(ServerPlayer player, ServerRuntime runtime) {
            this(player, runtime.protocolConnections().sessionId(player).orElse(null), player.level());
        }
    }
}
