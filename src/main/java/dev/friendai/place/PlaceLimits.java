package dev.friendai.place;

/**
 * Hard bounds for one owner's place file. The owner-facing policy cap is {@code places.maxPlaces} in the
 * config and is enforced before a place is created; the registry and the file accept anything up to these
 * bounds so lowering the config can never make an existing file unreadable.
 */
public record PlaceLimits(
        int maxPlaces, int maxNameChars, int maxAliases, int maxNoteChars, long maxFileBytes) {

    /** Longest name or alias the model itself stores. */
    public static final int NAME_MAX = 32;

    /** Longest label a place may carry: one short detail such as the crop or the building style. */
    public static final int LABEL_MAX = 64;

    /** Most aliases one place may carry. */
    public static final int ALIASES_MAX = 4;

    /** Longest note the model itself stores. */
    public static final int NOTE_MAX = 160;

    public static final PlaceLimits DEFAULTS =
            new PlaceLimits(1024, NAME_MAX, ALIASES_MAX, NOTE_MAX, 2L * 1024 * 1024);
}
