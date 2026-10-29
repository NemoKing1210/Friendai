package dev.friendai;

import dev.friendai.chat.ChatRouter;
import dev.friendai.command.FriendaiCommands;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.menu.FriendaiMenus;
import dev.friendai.server.net.FriendaiNetworking;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FriendaiMod implements ModInitializer {
    public static final String MOD_ID = "friendai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        FriendaiEntities.register();
        FriendaiMenus.register();
        FriendaiCommands.register();
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            ServerRuntimeRegistry.getOrCreate(level.getServer()).onEntityLoaded(entity);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            ServerRuntimeRegistry.find(level.getServer()).ifPresent(runtime -> runtime.onEntityUnloaded(entity));
        });
        FriendaiNetworking.register();
        ChatRouter.register();
        ServerLifecycleEvents.SERVER_STARTING.register(ServerRuntimeRegistry::getOrCreate);
        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> ServerRuntimeRegistry.find(server).ifPresent(ServerRuntime::onServerStarted));
        ServerLifecycleEvents.SERVER_STOPPING.register(
                server -> ServerRuntimeRegistry.find(server).ifPresent(ServerRuntime::stopAcceptingWork));
        ServerLifecycleEvents.SERVER_STOPPED.register(ServerRuntimeRegistry::removeAndClose);
        ServerTickEvents.END_SERVER_TICK.register(
                server -> ServerRuntimeRegistry.find(server).ifPresent(ServerRuntime::tick));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerRuntimeRegistry.getOrCreate(server).onOwnerJoined(handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerRuntimeRegistry.find(server).ifPresent(runtime -> runtime.onOwnerLeft(handler.getPlayer()));
        });
        LOGGER.info("Friendai initialized");
    }
}
