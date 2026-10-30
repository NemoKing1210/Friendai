package dev.friendai.features.survival.selfcare;

/** One stock unit the companion can spend on itself. */
public enum SelfCareItem {
    WATER_BUCKET("minecraft:water_bucket"),
    EMPTY_BUCKET("minecraft:bucket"),
    MILK_BUCKET("minecraft:milk_bucket"),
    GOLDEN_APPLE("minecraft:golden_apple"),
    ENCHANTED_GOLDEN_APPLE("minecraft:enchanted_golden_apple"),
    POTION_HEALING("minecraft:potion#healing"),
    POTION_REGENERATION("minecraft:potion#regeneration"),
    POTION_FIRE_RESISTANCE("minecraft:potion#fire_resistance"),
    POTION_WATER_BREATHING("minecraft:potion#water_breathing"),
    POTION_SLOW_FALLING("minecraft:potion#slow_falling"),
    SPLASH_POTION_HEALING("minecraft:splash_potion#healing");

    private final String id;

    SelfCareItem(String id) {
        this.id = id;
    }

    /** The item id, or the item id plus the effect that names the potion kind. */
    public String id() {
        return id;
    }

    /** "water bucket", "splash potion of healing": English for the prompt, never a translation key. */
    public String display() {
        String base = id.substring(id.indexOf(':') + 1);
        int hash = base.indexOf('#');
        if (hash >= 0) {
            return base.substring(0, hash).replace('_', ' ') + " of "
                    + base.substring(hash + 1).replace('_', ' ');
        }
        return base.replace('_', ' ');
    }

    /** The same name for a count: "2 golden apples", "2 potions of healing". */
    public String display(int count) {
        String base = display();
        if (count <= 1) {
            return base;
        }
        int of = base.indexOf(" of ");
        return of < 0 ? base + "s" : base.substring(0, of) + "s" + base.substring(of);
    }
}
