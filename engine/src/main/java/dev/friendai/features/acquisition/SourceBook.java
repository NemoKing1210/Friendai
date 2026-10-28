package dev.friendai.features.acquisition;

import java.util.List;
import java.util.Optional;

/** The non-recipe ways to get an item, and what a carried item counts as. */
public interface SourceBook {
    /** Sources for the item in a stable order. */
    List<Source> sourcesOf(String item);

    /** The cheapest item that satisfies the need (a wooden pickaxe for {@code (PICKAXE, WOOD)}). */
    Optional<String> toolItem(ToolNeed need);

    /** What a carried item counts as; {@link ToolNeed#HAND} when it is not a tool. */
    ToolNeed toolOf(String item);
}
