package dev.friedai;

import dev.friedai.command.FriedaiCommands;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.CompanionManager;
import dev.friedai.entity.FriedaiEntities;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
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
        LOGGER.info("Friedai initialized");
    }
}
