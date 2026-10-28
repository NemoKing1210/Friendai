package dev.friendai.features.crafting;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One recipe slot: the item ids it accepts, in preference order, and a label for messages (the tag
 * {@code #minecraft:planks} or the single id).
 */
public record Ingredient(Set<String> items, String label) {
    public Ingredient {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("ingredient needs at least one item");
        }
        items = Collections.unmodifiableSet(new LinkedHashSet<>(items));
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("ingredient needs a label");
        }
    }

    /** A slot that accepts exactly one item id; the label is that id. */
    public static Ingredient of(String id) {
        return new Ingredient(new LinkedHashSet<>(Set.of(id)), id);
    }

    /** A slot backed by a tag; {@code tag} may be given with or without the leading {@code #}. */
    public static Ingredient tag(String tag, String... ids) {
        String name = tag.startsWith("#") ? tag.substring(1) : tag;
        return new Ingredient(new LinkedHashSet<>(List.of(ids)), "#" + name);
    }
}
