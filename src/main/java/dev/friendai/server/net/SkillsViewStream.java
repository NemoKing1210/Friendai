package dev.friendai.server.net;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.features.instructions.*;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.instructions.SkillProtocolViewCodec;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-scoped subscriptions; disconnect/server disposal cannot retain another world's viewers. */
public final class SkillsViewStream {
    private record Query(int page, String selected) {}

    private static final Map<MinecraftServer, Map<UUID, Query>> QUERIES = new WeakHashMap<>();

    private SkillsViewStream() {}

    private static Map<UUID, Query> queries(MinecraftServer server) {
        return QUERIES.computeIfAbsent(server, s -> new HashMap<>());
    }

    public static boolean isOpen(ServerPlayer p) {
        return queries(p.level().getServer()).containsKey(p.getUUID());
    }

    public static void subscribe(ServerPlayer p, int page, String selected) {
        queries(p.level().getServer()).put(p.getUUID(), new Query(page, selected));
    }

    public static void open(ServerPlayer p, int page, String selected) {
        subscribe(p, page, selected);
        sendNow(p, null);
    }

    public static void close(ServerPlayer p) {
        queries(p.level().getServer()).remove(p.getUUID());
    }

    public static void sendNow(ServerPlayer p, SkillActionResult result) {
        sendNow(p, result, 0);
    }

    public static void sendNow(ServerPlayer p, SkillActionResult result, long requestId) {
        var server = p.level().getServer();
        var query = queries(server).get(p.getUUID());
        if (!isOpen(p) || query == null) return;
        if (result != null && result.skill().isPresent()) {
            query = new Query(query.page(), result.skill().get().id());
            queries(server).put(p.getUUID(), query);
        }
        var state = FriendaiServerState.get(server);
        var service = state.skills();
        try {
            var local = service.store().book(p.getUUID());
            var profile = state.profiles().profile(p.getUUID());
            long revision = local.revision()
                    + service.store().global().revision()
                    + state.profiles().revision(p.getUUID());
            var view = SkillViewBuilder.build(
                    state.config().skills.enabled,
                    revision,
                    service.listed(p.getUUID()),
                    local.disabledIds(),
                    query.page(),
                    query.selected(),
                    profile.description(),
                    profile.ownerProfile(),
                    result);
            ServerRuntimeRegistry.getOrCreate(server)
                    .protocolConnections()
                    .sendView(
                            p,
                            ProtocolFamily.INSTRUCTIONS,
                            SkillProtocolViewCodec.encode(view.withResponseId(requestId), requestId));
        } catch (RuntimeException e) {
            var view = new SkillView(
                    false,
                    0,
                    0,
                    0,
                    List.of(),
                    null,
                    dev.friendai.features.memory.CompanionDescription.EMPTY,
                    dev.friendai.features.memory.OwnerProfile.EMPTY,
                    "friendai.menu.skills.invalid",
                    false,
                    "");
            ServerRuntimeRegistry.getOrCreate(server)
                    .protocolConnections()
                    .sendView(
                            p,
                            ProtocolFamily.INSTRUCTIONS,
                            SkillProtocolViewCodec.encode(view.withResponseId(requestId), requestId));
        }
    }

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) return;
        for (UUID id : List.copyOf(queries(server).keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) queries(server).remove(id);
            else sendNow(p, null);
        }
    }

    public static void onDisconnect(ServerPlayer p) {
        close(p);
    }
}
