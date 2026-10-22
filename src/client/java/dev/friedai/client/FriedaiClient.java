package dev.friedai.client;

import dev.friedai.client.render.CompanionRenderer;
import dev.friedai.entity.FriedaiEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class FriedaiClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRenderers.register(FriedaiEntities.COMPANION, CompanionRenderer::new);
    }
}
