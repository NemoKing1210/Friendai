package dev.friedai.crafting;

/** Where a recipe is executed, with the words used in LLM-facing texts. */
public enum Station {
    INVENTORY("in the inventory", "inventory"),
    CRAFTING_TABLE("at a crafting table", "crafting table"),
    STONECUTTER("at a stonecutter", "stonecutter"),
    SMITHING_TABLE("at a smithing table", "smithing table");

    private final String phrase;
    private final String noun;

    Station(String phrase, String noun) {
        this.phrase = phrase;
        this.noun = noun;
    }

    /** Location phrase for a step, e.g. {@code at a crafting table}. */
    public String phrase() {
        return phrase;
    }

    /** Station name without preposition, e.g. {@code crafting table}. */
    public String noun() {
        return noun;
    }
}
