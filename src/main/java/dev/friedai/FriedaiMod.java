package dev.friedai;

import dev.friedai.chat.ChatRouter;
import dev.friedai.command.FriedaiCommands;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.CompanionManager;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.net.DebugStream;
import dev.friedai.net.FriedaiNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FriedaiMod implements ModInitializer {
    public static final String MOD_ID = "friedai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        FriedaiEntities.register();
        FriedaiCommands.register();
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
        FriedaiNetworking.register();
        ChatRouter.register();
        ServerLifecycleEvents.SERVER_STARTING.register(FriedaiServerState::get);
        ServerLifecycleEvents.SERVER_STOPPED.register(FriedaiServerState::close);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            FriedaiServerState.get(server).brains().tick();
            DebugStream.tick(server);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DebugStream.onDisconnect(handler.getPlayer());
            FriedaiServerState.get(server).brains().onOwnerLeft(handler.getPlayer().getUUID());
        });
        LOGGER.info("Friendai initialized");
    }
}
