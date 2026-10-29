package dev.friendai.server.net;

import dev.friendai.features.instructions.*;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.instructions.SkillProtocolViewCodec;
import dev.friendai.server.runtime.ServerRuntime;
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

    /** Changes only the catalogue page; the current selection and its detail stay valid. */
    public static void page(ServerPlayer p, int page) {
        Map<UUID, Query> map = queries(p.level().getServer());
        Query current = map.get(p.getUUID());
        if (current != null) map.put(p.getUUID(), new Query(page, current.selected()));
    }

    /** Changes only the selection; the current page is retained. */
    public static void select(ServerPlayer p, String selected) {
        Map<UUID, Query> map = queries(p.level().getServer());
        Query current = map.get(p.getUUID());
        if (current != null) map.put(p.getUUID(), new Query(current.page(), selected));
    }

    public static void open(ServerRuntime runtime, ServerPlayer p, int page, String selected) {
        subscribe(p, page, selected);
        sendNow(runtime, p, null);
    }

    public static void close(ServerPlayer p) {
        queries(p.level().getServer()).remove(p.getUUID());
    }

    /** Current catalogue page of an open subscription; zero when the player has none. */
    public static int pageOf(ServerPlayer p) {
        Query query = queries(p.level().getServer()).get(p.getUUID());
        return query == null ? 0 : query.page();
    }

    /** Current selected skill id of an open subscription; empty when the player has none selected. */
    public static String selectionOf(ServerPlayer p) {
        Query query = queries(p.level().getServer()).get(p.getUUID());
        return query == null ? "" : query.selected();
    }

    public static void sendNow(ServerRuntime runtime, ServerPlayer p, SkillActionResult result) {
        sendNow(runtime, p, result, 0);
    }

    public static void sendNow(ServerRuntime runtime, ServerPlayer p, SkillActionResult result, long requestId) {
        var server = runtime.server();
        var query = queries(server).get(p.getUUID());
        if (!isOpen(p) || query == null) return;
        if (result != null && result.skill().isPresent()) {
            query = new Query(query.page(), result.skill().get().id());
            queries(server).put(p.getUUID(), query);
        }
        var service = runtime.skills();
        try {
            var local = service.store().book(p.getUUID());
            var profile = runtime.profiles().profile(p.getUUID());
            long revision = local.revision()
                    + service.store().global().revision()
                    + runtime.profiles().revision(p.getUUID());
            var view = SkillViewBuilder.build(
                    runtime.config().skills.enabled,
                    revision,
                    service.listed(p.getUUID()),
                    local.disabledIds(),
                    query.page(),
                    query.selected(),
                    profile.description(),
                    profile.ownerProfile(),
                    result);
            runtime.protocolConnections()
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
            runtime.protocolConnections()
                    .sendView(
                            p,
                            ProtocolFamily.INSTRUCTIONS,
                            SkillProtocolViewCodec.encode(view.withResponseId(requestId), requestId));
        }
    }

    public static void tick(ServerRuntime runtime) {
        MinecraftServer server = runtime.server();
        if (server.getTickCount() % 10 != 0) return;
        for (UUID id : List.copyOf(queries(server).keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) queries(server).remove(id);
            else sendNow(runtime, p, null);
        }
    }

    public static void onDisconnect(ServerPlayer p) {
        close(p);
    }
}
