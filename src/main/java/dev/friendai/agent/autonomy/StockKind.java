package dev.friendai.agent.autonomy;

/** The bag contents free time counts, each with the words the model reads. */
public enum StockKind {
    LOGS("logs"),
    PLANKS("planks"),
    COBBLE("cobblestone"),
    COAL("coal"),
    RAW_ORE("raw ore"),
    IRON_INGOT("iron ingots"),
    DIAMOND("diamonds"),
    TORCHES("torches"),
    SEEDS("seeds"),
    BONEMEAL("bone meal");

    private final String words;

    StockKind(String words) {
        this.words = words;
    }

    public String words() {
        return words;
    }
}
