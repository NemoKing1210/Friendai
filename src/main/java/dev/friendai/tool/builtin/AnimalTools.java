package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.builtin.BreedTask;
import dev.friendai.task.builtin.FishTask;
import dev.friendai.task.builtin.HoneyTask;
import dev.friendai.task.builtin.LeadTask;
import dev.friendai.task.builtin.MilkTask;
import dev.friendai.task.builtin.ShearTask;
import dev.friendai.task.builtin.TameTask;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.ToolRegistry;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.jetbrains.annotations.Nullable;

/** Animal work: breeding, shearing, milking, taming, leads, honey and fishing. */
final class AnimalTools {
    private AnimalTools() {}

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        ToolKit.add(registry, defs, new TaskToolDef("breed_animals",
                "Feed pairs of adult animals of a type so they breed.",
                SchemaBuilder.object()
                        .string("target", "Entity type id like minecraft:cow")
                        .integer("pairs", "How many pairs to feed", 1, 8)
                        .required("target", "pairs")
                        .build(),
                (c, a) -> breed(c, a.get("target").getAsString(), a.get("pairs").getAsInt()),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("shear",
                "Shear sheep (or another shearable animal you name) and collect what drops.",
                SchemaBuilder.object()
                        .string("target", "Entity type id, sheep by default")
                        .integer("count", "How many animals to shear", 1, 16)
                        .required("count")
                        .build(),
                (c, a) -> shear(a, a.get("count").getAsInt()),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("milk",
                "Fill empty buckets with milk from cows or goats.",
                SchemaBuilder.object()
                        .integer("count", "How many buckets to fill", 1, 16)
                        .enumeration("from", "Which animal to milk", "cow", "goat")
                        .required("count")
                        .build(),
                (c, a) -> new MilkTask(a.get("count").getAsInt(), milkFrom(a)),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("tame_animal",
                "Tame a wolf, cat or parrot for the owner using the right food.",
                SchemaBuilder.object()
                        .enumeration("target", "Which animal to tame", "wolf", "cat", "parrot")
                        .integer("count", "How many to tame", 1, 4)
                        .required("target")
                        .build(),
                (c, a) -> new TameTask(a.get("target").getAsString(),
                        a.has("count") && !a.get("count").isJsonNull() ? a.get("count").getAsInt() : 1),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("lead_animals",
                "Put leads on animals, walk them to a place, and tie them to a fence there or take the leads back.",
                SchemaBuilder.object()
                        .string("target", "Entity type id like minecraft:pig")
                        .integer("count", "How many animals to lead", 1, 8)
                        .integer("x", "Destination X coordinate")
                        .integer("y", "Destination Y coordinate")
                        .integer("z", "Destination Z coordinate")
                        .bool("tie", "Tie the animals to a fence at the destination")
                        .required("target", "count", "x", "y", "z")
                        .build(),
                (c, a) -> new LeadTask(entityType(a.get("target").getAsString()), a.get("count").getAsInt(),
                        new BlockPos(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt()),
                        a.has("tie") && !a.get("tie").isJsonNull() && a.get("tie").getAsBoolean()),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("harvest_honey",
                "Collect honey from a full beehive or bee nest with a glass bottle, or honeycomb with shears. The hive must be smoked by a campfire below.",
                SchemaBuilder.object()
                        .enumeration("using", "What to collect", "bottle", "shears")
                        .integer("x", "Hive X coordinate")
                        .integer("y", "Hive Y coordinate")
                        .integer("z", "Hive Z coordinate")
                        .required("using")
                        .build(),
                (c, a) -> new HoneyTask(a.get("using").getAsString().equals("bottle"),
                        ToolKit.optionalPos(a).orElse(null)),
                true));
        ToolKit.add(registry, defs, new TaskToolDef("fish",
                "Fish with a fishing rod at nearby open water.",
                SchemaBuilder.object()
                        .integer("count", "How many catches", 1, 16)
                        .required("count")
                        .build(),
                (c, a) -> new FishTask(a.get("count").getAsInt()),
                true));
    }

    private static BreedTask breed(CompanionEntity companion, String text, int pairs) {
        EntityType<?> type = entityType(text);
        String id = typeId(type);
        Entity probe = type.create(companion.level(), EntitySpawnReason.COMMAND);
        if (!(probe instanceof Animal)) {
            throw new IllegalArgumentException(id + " is not an animal I can breed");
        }
        if (probe instanceof AbstractHorse) {
            throw new IllegalArgumentException("I can't breed " + id);
        }
        return new BreedTask(type, pairs);
    }

    private static ShearTask shear(JsonObject args, int count) {
        String text = args.has("target") && !args.get("target").isJsonNull()
                ? args.get("target").getAsString()
                : "minecraft:sheep";
        EntityType<?> type = entityType(text);
        if (type != EntityTypes.SHEEP && type != EntityTypes.MOOSHROOM) {
            throw new IllegalArgumentException(typeId(type) + " is not something I can shear");
        }
        return new ShearTask(type, count);
    }

    private static @Nullable EntityType<?> milkFrom(JsonObject args) {
        if (!args.has("from") || args.get("from").isJsonNull()) {
            return null;
        }
        return switch (args.get("from").getAsString()) {
            case "cow" -> EntityTypes.COW;
            case "goat" -> EntityTypes.GOAT;
            default -> throw new IllegalArgumentException("unknown animal: " + args.get("from").getAsString());
        };
    }

    private static EntityType<?> entityType(String text) {
        Identifier id = Identifier.tryParse(text);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            throw new IllegalArgumentException("unknown mob: " + text);
        }
        return BuiltInRegistries.ENTITY_TYPE.getValue(id);
    }

    private static String typeId(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }
}
