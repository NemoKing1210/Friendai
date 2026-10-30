package dev.friendai.features.places;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Optional;

/**
 * What a tool's {@code near} and {@code x}/{@code y}/{@code z} arguments ask to search around, decided before any
 * world lookup. Pure, and the only place that decides it: a tool that resolves a search origin reads this and never
 * grows a default of its own.
 *
 * <p>The default is the companion. Search, pathing and perception are companion-centred, so a call that names
 * nothing must not move the search to whoever happens to be talking: an absent or blank {@code near} is
 * {@link Companion}, and {@link Speaker} only when the call says so.
 */
public sealed interface NearRequest {
    /** An explicit coordinate triple; it always wins over {@code near}. */
    record Coordinates(int x, int y, int z) implements NearRequest {}

    /** A remembered place, by the name that follows {@code place:}. */
    record Place(String name) implements NearRequest {}

    /** The player who spoke: {@code me}, {@code you} or {@code speaker}. */
    record Speaker() implements NearRequest {}

    /** The companion: an absent or blank {@code near}, or {@code self} / {@code companion}. */
    record Companion() implements NearRequest {}

    /** Reads the arguments; partial coordinates and unknown words are errors the model can read. */
    static NearRequest parse(JsonObject args) {
        int present = 0;
        for (String name : new String[] {"x", "y", "z"}) {
            if (args.has(name) && !args.get(name).isJsonNull()) {
                present++;
            }
        }
        if (present != 0 && present != 3) {
            throw new IllegalArgumentException("give all of x, y, z or none");
        }
        if (present == 3) {
            return new Coordinates(
                    args.get("x").getAsInt(),
                    args.get("y").getAsInt(),
                    args.get("z").getAsInt());
        }
        String raw = args.has("near") && !args.get("near").isJsonNull()
                ? args.get("near").getAsString()
                : "";
        Optional<String> placeName = PlaceNames.fromNear(raw);
        if (placeName.isPresent()) {
            return new Place(placeName.get());
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "", "self", "companion" -> new Companion();
            case "me", "you", "speaker" -> new Speaker();
            default ->
                throw new IllegalArgumentException(
                        "near must be \"self\", \"me\" or \"place:<name>\", or give x, y and z");
        };
    }
}
