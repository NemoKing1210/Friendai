package dev.friendai.features.construction.clear;

/** Why one cell of a clear-area volume was not cleared. The label is what a task summary shows. */
public enum ClearSkip {
    NOT_LOADED("not loaded"),
    PROTECTED("protected"),
    BLACKLISTED("blacklisted"),
    HAS_CONTENTS("has contents"),
    UNDER_FEET("under my feet"),
    UNBREAKABLE("unbreakable"),
    LAVA("lava"),
    FLOWING("flowing water"),
    NOT_WANTED("not selected"),
    NO_BUCKET("no empty bucket"),
    WATER_LEFT("water left"),
    UNREACHABLE("unreachable"),
    OUT_OF_SIGHT("out of sight"),
    CHANGED("changed");

    private final String label;

    ClearSkip(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
