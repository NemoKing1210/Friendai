package dev.friendai.task.obtain;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.knowledge.search.ResourceSearchProfile;
import dev.friendai.features.knowledge.search.StructureMarkers;
import dev.friendai.features.knowledge.search.TargetKind;
import dev.friendai.features.navigation.minecraft.nav.Sight;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.places.Place;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.place.PlaceService;
import dev.friendai.task.autonomy.PlaceGuard;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

/**
 * The Minecraft half of a target search: resolves a requested target into a {@link TargetKind}, builds the facts a
 * kind decides on, and answers the cheap per-tick "is it found" question. The pure layer owns the decision; this
 * class owns the reads.
 */
public final class SearchTargets {

    /** A resolved kind, or a sentence the model can act on. */
    public record Resolution(TargetKind kind, String error) {
        public boolean ok() {
            return kind != null;
        }

        static Resolution ok(TargetKind kind) {
            return new Resolution(kind, "");
        }

        static Resolution fail(String error) {
            return new Resolution(null, error);
        }
    }

    private SearchTargets() {}

    /** Resolves {@code kind}/{@code target} in the companion's current dimension. */
    public static Resolution resolve(CompanionEntity companion, String kind, String target) {
        ServerLevel level = (ServerLevel) companion.level();
        String dimension = level.dimension().identifier().toString();
        String requested = target == null ? "" : target.strip();
        if (requested.isEmpty()) return Resolution.fail("the target is missing");
        return switch (kind == null ? "resource" : kind.toLowerCase(Locale.ROOT)) {
            case "resource", "block", "blocks" -> resolveBlock(level, dimension, requested);
            case "structure" -> resolveStructure(requested);
            case "biome" -> resolveBiome(level, requested);
            case "entity" -> resolveEntity(requested);
            case "place" -> resolvePlace(companion, dimension, requested);
            default ->
                Resolution.fail("unknown target kind: " + kind + " (use resource, structure, biome, entity or place)");
        };
    }

    private static Resolution resolveBlock(ServerLevel level, String dimension, String target) {
        ResourceResolver.Resolution resolved = ResourceResolver.resolve(level, target);
        if (!resolved.executable() || resolved.blockIds().isEmpty()) return Resolution.fail(resolved.reason());
        ResourceSearchProfile profile = ResourceSearchProfile.builtin(resolved.family(), dimension);
        if (profile.strategies().isEmpty()) {
            return Resolution.fail("no search strategy for " + resolved.family() + " in " + dimension);
        }
        return Resolution.ok(new TargetKind.BlockFamily(resolved.family(), resolved.blockIds(), profile));
    }

    private static Resolution resolveStructure(String target) {
        Optional<StructureMarkers.Entry> entry = StructureMarkers.get(target);
        if (entry.isEmpty()) {
            return Resolution.fail(
                    "unknown structure: " + target + " (known: " + String.join(", ", StructureMarkers.ids()) + ")");
        }
        String id = target.contains(":") ? target : "minecraft:" + target;
        return Resolution.ok(
                new TargetKind.Structure(id, entry.get().markers(), entry.get().minimum(), 64));
    }

    private static Resolution resolveBiome(ServerLevel level, String target) {
        Identifier id = Identifier.tryParse(target);
        if (id == null) return Resolution.fail("biome id is invalid: " + target);
        var biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        if (!biomes.containsKey(ResourceKey.create(Registries.BIOME, id))) {
            return Resolution.fail("unknown biome: " + target);
        }
        return Resolution.ok(new TargetKind.Biome(id.toString(), 64));
    }

    private static Resolution resolveEntity(String target) {
        Identifier id = Identifier.tryParse(target);
        if (id == null) return Resolution.fail("entity id is invalid: " + target);
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return Resolution.fail("unknown entity: " + target);
        return Resolution.ok(new TargetKind.Entity(id.toString(), 32));
    }

    private static Resolution resolvePlace(CompanionEntity companion, String dimension, String target) {
        Optional<Place> place = PlaceService.book(companion).find(target, dimension);
        if (place.isEmpty()) return Resolution.fail("unknown place in this dimension: " + target);
        return Resolution.ok(new TargetKind.Place(place.get().name(), dimension, 64));
    }

    /**
     * The per-tick find check. It builds only the facts the kind decides on — a remembered block is not a find
     * until it is seen again — and then lets the pure {@link TargetKind#matches} answer.
     */
    public static boolean found(CompanionEntity companion, TargetKind kind, int radius) {
        ServerLevel level = (ServerLevel) companion.level();
        FriendaiServerState state = FriendaiServerState.get(level.getServer());
        ResourceCandidates resources = state.resources();
        Pos position = blockPosition(companion);
        String dimension = level.dimension().identifier().toString();
        if (kind instanceof TargetKind.Structure) {
            java.util.Map<Pos, String> locations = new java.util.LinkedHashMap<>();
            for (var observation : resources.confirmedRecent(companion, radius)) {
                locations.put(observation.location().pos(), observation.blockId());
            }
            return kind.matches(new TargetKind.CandidateFacts(
                    position,
                    dimension,
                    "",
                    new LinkedHashSet<>(locations.values()),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    locations));
        }
        TargetKind.CandidateFacts facts = switch (kind) {
            case TargetKind.BlockFamily ignored ->
                new TargetKind.CandidateFacts(
                        position,
                        dimension,
                        "",
                        new LinkedHashSet<>(resources
                                .confirmedRecentBlockIds(companion, radius)
                                .values()),
                        Set.of(),
                        Set.of(),
                        Set.of());
            case TargetKind.Biome ignored ->
                new TargetKind.CandidateFacts(
                        position, dimension, biomeAt(level, position), Set.of(), Set.of(), Set.of(), Set.of());
            case TargetKind.Entity entity ->
                new TargetKind.CandidateFacts(
                        position,
                        dimension,
                        "",
                        Set.of(),
                        Set.of(),
                        visibleEntityIds(level, companion, entity.radius()),
                        Set.of());
            case TargetKind.Place ignored ->
                new TargetKind.CandidateFacts(
                        position, dimension, "", Set.of(), Set.of(), Set.of(), placeNamesAt(companion, position));
            case TargetKind.Structure ignored -> throw new IllegalStateException("handled above");
        };
        return kind.matches(facts);
    }

    public static String biomeAt(ServerLevel level, Pos pos) {
        return level.getBiome(new BlockPos(pos.x(), pos.y(), pos.z()))
                .unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse("");
    }

    public static boolean landAt(ServerLevel level, Pos pos) {
        BlockPos feetPos = new BlockPos(pos.x(), pos.y(), pos.z());
        BlockState below = level.getBlockState(feetPos.below());
        BlockState feet = level.getBlockState(feetPos);
        BlockState head = level.getBlockState(feetPos.above());
        if (!below.isSolid()
                || !feet.getCollisionShape(level, feetPos).isEmpty()
                || !head.getCollisionShape(level, feetPos.above()).isEmpty()) return false;
        return !liquid(feet) && !liquid(head) && !liquid(below) && !hazard(feet);
    }

    public static boolean inWorld(ServerLevel level, Pos pos) {
        if (pos.y() <= level.getMinY() || pos.y() >= level.getMaxY()) return false;
        return level.getWorldBorder().isWithinBounds(new BlockPos(pos.x(), pos.y(), pos.z()));
    }

    /** True when the position is inside an owner-known place; the hint may not steer into one. */
    public static boolean protectedAt(CompanionEntity companion, Pos pos) {
        return PlaceGuard.insideAnyPlace(companion, new BlockPos(pos.x(), pos.y(), pos.z()));
    }

    private static Set<String> placeNamesAt(CompanionEntity companion, Pos position) {
        String dimension = companion.level().dimension().identifier().toString();
        Set<String> names = new LinkedHashSet<>();
        for (Place place : PlaceService.book(companion).all()) {
            if (place.dimension().equals(dimension) && within(place, position)) names.add(place.name());
        }
        return names;
    }

    private static boolean within(Place place, Pos position) {
        return place.contains(position);
    }

    private static Set<String> visibleEntityIds(ServerLevel level, CompanionEntity companion, int radius) {
        Pos at = blockPosition(companion);
        AABB box =
                new AABB(at.x() - radius, at.y() - 8, at.z() - radius, at.x() + radius, at.y() + 16, at.z() + radius);
        Set<String> ids = new LinkedHashSet<>();
        List<LivingEntity> entities =
                level.getEntitiesOfClass(LivingEntity.class, box, entity -> entity != companion && entity.isAlive());
        for (LivingEntity entity : entities) {
            if (Sight.canSee(level, companion, entity.blockPosition())) {
                ids.add(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            }
        }
        return ids;
    }

    private static boolean liquid(BlockState state) {
        return state.getFluidState().getType() == Fluids.WATER
                || state.getFluidState().getType() == Fluids.LAVA;
    }

    private static boolean hazard(BlockState state) {
        return state.getFluidState().getType() == Fluids.LAVA || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
    }

    private static Pos blockPosition(Entity entity) {
        BlockPos pos = entity.blockPosition();
        return new Pos(pos.getX(), pos.getY(), pos.getZ());
    }
}
