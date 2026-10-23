package dev.friendai.crafting;

/** Where a recipe is executed, with the words used in LLM-facing texts. */
public enum Station {
    INVENTORY("in the inventory", "inventory", false),
    CRAFTING_TABLE("at a crafting table", "crafting table", false),
    STONECUTTER("at a stonecutter", "stonecutter", false),
    SMITHING_TABLE("at a smithing table", "smithing table", false),
    FURNACE("in a furnace", "furnace", true),
    SMOKER("in a smoker", "smoker", true),
    BLAST_FURNACE("in a blast furnace", "blast furnace", true),
    CAMPFIRE("on a campfire", "campfire", true);

    private final String phrase;
    private final String noun;
    private final boolean cooking;

    Station(String phrase, String noun, boolean cooking) {
        this.phrase = phrase;
        this.noun = noun;
        this.cooking = cooking;
    }

    /** Location phrase for a step, e.g. {@code at a crafting table}. */
    public String phrase() {
        return phrase;
    }

    /** Station name without preposition, e.g. {@code crafting table}. */
    public String noun() {
        return noun;
    }

    /** True for the four stations that cook with time and fuel instead of crafting. */
    public boolean isCooking() {
        return cooking;
    }
}
