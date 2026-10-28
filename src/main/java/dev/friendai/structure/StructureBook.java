package dev.friendai.structure;

import dev.friendai.build.BlockSpec;
import dev.friendai.build.Blueprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** The registry of special structures. A new structure is a new entry here, not a new task class. */
public final class StructureBook {
    private static final String PUMPKIN = "minecraft:carved_pumpkin[facing=south]";

    private final TreeMap<String, SpecialStructure> structures = new TreeMap<>();

    public static StructureBook withBuiltins() {
        return new StructureBook().register(snowGolem()).register(ironGolem()).register(netherPortal());
    }

    public StructureBook register(SpecialStructure structure) {
        if (structures.putIfAbsent(structure.id(), structure) != null) {
            throw new IllegalArgumentException("structure already registered: " + structure.id());
        }
        return this;
    }

    public Optional<SpecialStructure> get(String id) {
        return Optional.ofNullable(structures.get(id));
    }

    /** Ids in sorted order. */
    public List<String> ids() {
        return List.copyOf(structures.keySet());
    }

    private static SpecialStructure snowGolem() {
        Blueprint body = new Blueprint(
                "snow_golem",
                1,
                2,
                1,
                List.of(
                        new BlockSpec(0, 0, 0, "minecraft:snow_block"),
                        new BlockSpec(0, 1, 0, "minecraft:snow_block")));
        return new SpecialStructure(
                "snow_golem",
                body,
                new Trigger.LastBlock(PUMPKIN, new Offset(0, 2, 0)),
                new Outcome.EntitySpawned("minecraft:snow_golem", 3),
                new Footprint(1, 3, 1, 1),
                List.of(),
                true);
    }

    private static SpecialStructure ironGolem() {
        Blueprint body = new Blueprint(
                "iron_golem",
                3,
                2,
                1,
                List.of(
                        new BlockSpec(1, 0, 0, "minecraft:iron_block"),
                        new BlockSpec(0, 1, 0, "minecraft:iron_block"),
                        new BlockSpec(1, 1, 0, "minecraft:iron_block"),
                        new BlockSpec(2, 1, 0, "minecraft:iron_block")));
        return new SpecialStructure(
                "iron_golem",
                body,
                new Trigger.LastBlock(PUMPKIN, new Offset(1, 2, 0)),
                new Outcome.EntitySpawned("minecraft:iron_golem", 4),
                new Footprint(3, 3, 1, 1),
                List.of(),
                true);
    }

    private static SpecialStructure netherPortal() {
        List<BlockSpec> frame = new ArrayList<>();
        for (int x = 1; x <= 2; x++) {
            frame.add(new BlockSpec(x, 0, 0, "minecraft:obsidian"));
            frame.add(new BlockSpec(x, 4, 0, "minecraft:obsidian"));
        }
        for (int y = 1; y <= 3; y++) {
            frame.add(new BlockSpec(0, y, 0, "minecraft:obsidian"));
            frame.add(new BlockSpec(3, y, 0, "minecraft:obsidian"));
        }
        return new SpecialStructure(
                "nether_portal",
                new Blueprint("nether_portal", 4, 5, 1, frame),
                new Trigger.Ignite("minecraft:flint_and_steel", new Offset(1, 1, 0)),
                new Outcome.BlocksPresent("minecraft:nether_portal", 6),
                new Footprint(4, 5, 1, 1),
                List.of("minecraft:flint_and_steel"),
                false);
    }
}
