package dev.friendai.features.places;

import dev.friendai.features.memory.MenuStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Builds the places tab's view from one owner's registry. The tab lists the most recently changed places first;
 * ties fall back to the name so two rebuilds of an unchanged book produce the same order.
 */
public final class PlaceViewBuilder {

    private PlaceViewBuilder() {}

    /** The view for {@code registry}: every stored place, newest update first, capped at the wire limit. */
    public static PlaceView build(PlaceRegistry registry, boolean enabled, Optional<MenuStatus> status) {
        List<Place> places = new ArrayList<>(registry.all());
        places.sort(Comparator.comparingLong(Place::updatedTick)
                .reversed()
                .thenComparing(Place::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(Place::id));
        int keep = Math.min(places.size(), PlaceViewCodec.MAX_PLACES);
        List<PlaceView.Row> rows = new ArrayList<>(keep);
        for (Place place : places.subList(0, keep)) {
            rows.add(row(place));
        }
        return new PlaceView(enabled, List.copyOf(rows), status);
    }

    private static PlaceView.Row row(Place place) {
        return new PlaceView.Row(
                place.id(),
                place.name(),
                kindKey(place.kind()),
                place.label(),
                place.dimension(),
                place.anchor().x(),
                place.anchor().y(),
                place.anchor().z(),
                place.radius(),
                place.visits(),
                place.lastVisitTick(),
                sourceKey(place.source()),
                place.note());
    }

    /** The localized kind name; every key is a literal so {@code LangFilesTest} can verify it. */
    static String kindKey(PlaceKind kind) {
        return switch (kind) {
            case HOUSE -> "friendai.menu.place.kind.house";
            case FARM -> "friendai.menu.place.kind.farm";
            case MINE -> "friendai.menu.place.kind.mine";
            case STORAGE -> "friendai.menu.place.kind.storage";
            case PORTAL -> "friendai.menu.place.kind.portal";
            case TRADE -> "friendai.menu.place.kind.trade";
            case LANDMARK -> "friendai.menu.place.kind.landmark";
        };
    }

    /** The localized origin name; every key is a literal so {@code LangFilesTest} can verify it. */
    static String sourceKey(PlaceSource source) {
        return switch (source) {
            case MANUAL -> "friendai.menu.place.source.manual";
            case COMMAND -> "friendai.menu.place.source.command";
            case BUILD -> "friendai.menu.place.source.build";
            case STRUCTURE -> "friendai.menu.place.source.structure";
            case FARM -> "friendai.menu.place.source.farm";
            case MINE -> "friendai.menu.place.source.mine";
        };
    }
}
