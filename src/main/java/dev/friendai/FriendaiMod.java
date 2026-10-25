package dev.friendai;

import dev.friendai.chat.ChatRouter;
import dev.friendai.command.FriendaiCommands;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.menu.FriendaiMenus;
import dev.friendai.net.DebugStream;
import dev.friendai.net.FriendaiNetworking;
import dev.friendai.net.MemoryViewStream;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.UUID;
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
            if (entity instanceof CompanionEntity companion) {
                CompanionManager.get(level.getServer()).register(companion);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof CompanionEntity companion) {
                CompanionManager.get(level.getServer()).unregister(companion);
            }
        });
        FriendaiNetworking.register();
        ChatRouter.register();
        ServerLifecycleEvents.SERVER_STARTING.register(FriendaiServerState::get);
        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> FriendaiServerState.get(server).chunkLoader().restore());
        ServerLifecycleEvents.SERVER_STOPPED.register(FriendaiServerState::close);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            FriendaiServerState state = FriendaiServerState.get(server);
            state.brains().tick();
            state.chunkLoader().tick();
            state.tick(System.currentTimeMillis());
            state.memory().flushIfDue();
            DebugStream.tick(server);
            MemoryViewStream.tick(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(
                server -> FriendaiServerState.get(server).memory().flushAll());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            FriendaiServerState state = FriendaiServerState.get(server);
            UUID player = handler.getPlayer().getUUID();
            state.brains().onOwnerJoined(player);
            state.memory().book(player);
            if (state.memory().takeCorruptionNotice(player)) {
                handler.getPlayer().sendSystemMessage(Component.translatable("friendai.memory.corrupt"));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DebugStream.onDisconnect(handler.getPlayer());
            MemoryViewStream.onDisconnect(handler.getPlayer());
            FriendaiServerState.get(server).brains().onOwnerLeft(handler.getPlayer().getUUID());
        });
        LOGGER.info("Friendai initialized");
    }
}
