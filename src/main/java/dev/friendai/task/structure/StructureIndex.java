package dev.friendai.task.structure;

import dev.friendai.structure.Outcome;
import dev.friendai.structure.SpecialStructure;
import dev.friendai.structure.StructureBook;
import dev.friendai.structure.Trigger;
import dev.friendai.task.build.BlueprintResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Checks the registry data of the pure book against what Minecraft actually knows. */
public final class StructureIndex {
    private StructureIndex() {}

    /** English descriptions of every unknown block state, item or entity type; empty means the book is sound. */
    public static List<String> validate(StructureBook book) {
        Predicate<String> knownState = BlueprintResolver.knownState();
        List<String> problems = new ArrayList<>();
        for (String id : book.ids()) {
            SpecialStructure s = book.get(id).orElseThrow();
            s.body().blocks().forEach(b -> state(problems, id, knownState, b.state()));
            switch (s.trigger()) {
                case Trigger.LastBlock t -> state(problems, id, knownState, t.state());
                case Trigger.Ignite t -> item(problems, id, t.item());
            }
            switch (s.outcome()) {
                case Outcome.EntitySpawned o -> entity(problems, id, o.entityType());
                case Outcome.BlocksPresent o -> state(problems, id, knownState, o.state());
            }
            s.requiredItems().forEach(item -> item(problems, id, item));
        }
        return problems;
    }

    private static void state(List<String> problems, String id, Predicate<String> known, String state) {
        if (!known.test(state)) {
            problems.add(id + ": unknown block " + state);
        }
    }

    private static void item(List<String> problems, String id, String item) {
        Identifier key = Identifier.tryParse(item);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            problems.add(id + ": unknown item " + item);
        }
    }

    private static void entity(List<String> problems, String id, String type) {
        Identifier key = Identifier.tryParse(type);
        if (key == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
            problems.add(id + ": unknown entity " + type);
        }
    }
}
