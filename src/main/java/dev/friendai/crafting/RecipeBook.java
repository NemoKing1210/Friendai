package dev.friendai.crafting;

import java.util.List;

/** The recipes the planner may use, and the items that provide each station. */
public interface RecipeBook {
    /** All recipes whose output is the item, in a stable order. */
    List<Recipe> producing(String item);

    /** The item id that provides a station ({@code minecraft:crafting_table}…), or null for {@link Station#INVENTORY}. */
    String stationItem(Station station);
}
