package dev.friedai.entity;

import dev.friedai.FriedaiMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class FriedaiEntities {
    private static final ResourceKey<EntityType<?>> COMPANION_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, FriedaiMod.id("companion"));

    public static final EntityType<CompanionEntity> COMPANION = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            COMPANION_KEY,
            EntityType.Builder.of(CompanionEntity::new, MobCategory.MISC).sized(0.6F, 1.8F).build(COMPANION_KEY));

    private FriedaiEntities() {}

    public static void register() {
        FabricDefaultAttributeRegistry.register(COMPANION, CompanionEntity.createAttributes());
    }
}
