package dev.friendai.features.animals.minecraft;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.combat.minecraft.Threats;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Finding animals the companion may work with. Other players' pets and named animals are skipped. */
public final class Animals {
    public static final int BREED_LIMIT = 24;

    private Animals() {}
    /** Animals of a type near a point that the companion may touch, nearest first. */
    public static List<Animal> nearby(
            ServerLevel level, CompanionEntity companion, Vec3 center, int radius, Predicate<EntityType<?>> type) {
        return level
                .getEntitiesOfClass(
                        Animal.class,
                        new AABB(center, center).inflate(radius),
                        animal -> animal.isAlive()
                                && !animal.isRemoved()
                                && type.test(animal.getType())
                                && !Threats.isProtected(animal, companion))
                .stream()
                .sorted(Comparator.comparingDouble(animal -> animal.position().distanceToSqr(center)))
                .toList();
    }
    /** How many animals of a type near a point belong to someone else or are named. */
    public static int protectedCount(
            ServerLevel level, CompanionEntity companion, Vec3 center, int radius, Predicate<EntityType<?>> type) {
        return (int) level
                .getEntitiesOfClass(
                        Animal.class,
                        new AABB(center, center).inflate(radius),
                        animal -> animal.isAlive()
                                && !animal.isRemoved()
                                && type.test(animal.getType())
                                && Threats.isProtected(animal, companion))
                .stream()
                .count();
    }
    /** Adult animals of a type near a point, including other people's pets (they still count towards the limit). */
    public static int adults(ServerLevel level, Vec3 center, int radius, EntityType<?> type) {
        return (int) level
                .getEntitiesOfClass(
                        Animal.class,
                        new AABB(center, center).inflate(radius),
                        animal ->
                                animal.isAlive() && !animal.isRemoved() && animal.getType() == type && !animal.isBaby())
                .stream()
                .count();
    }
}
