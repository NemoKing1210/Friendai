package dev.friendai.features.navigation.minecraft.nav;

import dev.friendai.entity.CompanionEntity;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;

/** Turns what the LLM names ("owner", a player, "cow") into a nearby entity. */
public final class EntityTargets {
    /** How far from the companion a named entity type is searched for. */
    public static final int SEARCH_RADIUS = 32;

    private EntityTargets() {}

    /**
     * Resolves a target name: {@code owner}, an online player in this level, or the nearest living entity of a type
     * (with or without the {@code minecraft:} prefix).
     */
    public static Optional<Entity> find(CompanionEntity companion, String spec) {
        if (spec == null || spec.isBlank()) {
            return Optional.empty();
        }
        String wanted = spec.trim();
        ServerLevel level = (ServerLevel) companion.level();
        if (wanted.equalsIgnoreCase("owner")) {
            return companion.owner().filter(p -> p.level() == level).map(p -> (Entity) p);
        }
        for (ServerPlayer player : level.players()) {
            if (player.getName().getString().equalsIgnoreCase(wanted) && player.isAlive()) {
                return Optional.of(player);
            }
        }
        Optional<EntityType<?>> type = parseType(wanted);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        AABB area = companion.getBoundingBox().inflate(SEARCH_RADIUS);
        return level
                .getEntitiesOfClass(
                        Entity.class,
                        area,
                        e -> e != companion && e.isAlive() && !e.isRemoved() && e.getType() == type.get())
                .stream()
                .min(Comparator.comparingDouble(companion::distanceToSqr));
    }

    /** An entity type from an id with or without the namespace. */
    public static Optional<EntityType<?>> parseType(String text) {
        String id = text.toLowerCase(java.util.Locale.ROOT);
        Identifier parsed = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (parsed == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(parsed)) {
            return Optional.empty();
        }
        return Optional.of(BuiltInRegistries.ENTITY_TYPE.getValue(parsed));
    }

    /** Short LLM-facing name of an entity. */
    public static String label(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            return player.getName().getString();
        }
        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
    }
}
