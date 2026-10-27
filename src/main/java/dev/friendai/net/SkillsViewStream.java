package dev.friendai.net;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.skill.*;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-scoped subscriptions; disconnect/server disposal cannot retain another world's viewers. */
public final class SkillsViewStream {
    private record Query(int page,String selected) {}
    private static final Map<MinecraftServer,Map<UUID,Query>> QUERIES=new WeakHashMap<>();
    private SkillsViewStream() {}
    private static Map<UUID,Query> queries(MinecraftServer server) { return QUERIES.computeIfAbsent(server,s->new HashMap<>()); }
    public static void open(ServerPlayer p,int page,String selected) {
        queries(p.level().getServer()).put(p.getUUID(),new Query(page,selected));sendNow(p,null);
    }
    public static void close(ServerPlayer p) { queries(p.level().getServer()).remove(p.getUUID()); }
    public static void sendNow(ServerPlayer p,SkillActionResult result) {
        var server=p.level().getServer();var query=queries(server).get(p.getUUID());
        if(query==null||!ServerPlayNetworking.canSend(p,SkillsViewS2CPayload.TYPE))return;
        var state=FriendaiServerState.get(server);var service=state.skills();
        try {
            var local=service.store().book(p.getUUID());var memory=state.memory().book(p.getUUID());
            long revision=local.revision()+service.store().global().revision()+memory.revision();
            var view=SkillViewBuilder.build(state.config().skills.enabled,revision,service.listed(p.getUUID()),local.disabledIds(),
                    query.page(),query.selected(),memory.description(),memory.ownerProfile(),result);
            ServerPlayNetworking.send(p,new SkillsViewS2CPayload(SkillViewCodec.encode(view)));
        } catch(RuntimeException e) {
            var view=new SkillView(false,0,0,0,List.of(),null,dev.friendai.memory.CompanionDescription.EMPTY,
                    dev.friendai.memory.OwnerProfile.EMPTY,"friendai.menu.skills.invalid",false,"");
            ServerPlayNetworking.send(p,new SkillsViewS2CPayload(SkillViewCodec.encode(view)));
        }
    }
    public static void tick(MinecraftServer server) {
        if(server.getTickCount()%10!=0)return;
        for(UUID id:List.copyOf(queries(server).keySet())) {
            ServerPlayer p=server.getPlayerList().getPlayer(id);
            if(p==null)queries(server).remove(id);else sendNow(p,null);
        }
    }
    public static void onDisconnect(ServerPlayer p) { close(p);SkillRequestHandler.disconnect(p.getUUID()); }
}
