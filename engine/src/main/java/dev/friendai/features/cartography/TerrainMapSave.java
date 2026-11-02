package dev.friendai.features.cartography;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Versioned, bounded record for one owner's terrain grid. A missing, corrupt, oversized or newer record reads as
 * empty, so the sampler starts clean instead of trusting half a file. Unknown fields are ignored; every cell is
 * revalidated through {@link TerrainCell}, so a corrupt Y, id or tick cannot enter the grid.
 */
public final class TerrainMapSave {
    public static final int FORMAT_VERSION = 2;
    /** Largest accepted encoded record; well above {@link TerrainGrid#MAX_CELLS} cells. */
    public static final int MAX_INPUT = 256 * 1024;

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private TerrainMapSave() {}

    public static String encode(TerrainGrid grid) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        JsonArray cells = new JsonArray();
        for (TerrainCell cell : grid.cells()) {
            JsonObject row = new JsonObject();
            row.addProperty("dimension", cell.key().dimension());
            row.addProperty("x", cell.key().x());
            row.addProperty("z", cell.key().z());
            row.addProperty("biome", cell.biome());
            row.addProperty("surfaceY", cell.surfaceY());
            row.addProperty("water", cell.water());
            row.addProperty("landmark", cell.landmark());
            row.addProperty("seen", cell.lastSeenTick());
            cells.add(row);
        }
        root.add("cells", cells);
        root.addProperty("generation", grid.generation());
        JsonArray sites = new JsonArray();
        for (TerrainSite site : grid.sites()) {
            JsonObject row = new JsonObject();
            row.addProperty("dimension", site.dimension());
            row.addProperty("x", site.x());
            row.addProperty("z", site.z());
            row.addProperty("kind", site.kind());
            row.addProperty("name", site.name());
            row.addProperty("seen", site.discoveredTick());
            sites.add(row);
        }
        root.add("sites", sites);
        JsonArray roads = new JsonArray();
        for (TerrainRoad road : grid.roads()) {
            JsonObject row = new JsonObject();
            row.addProperty("dimension", road.dimension());
            row.addProperty("fromX", road.fromX());
            row.addProperty("fromZ", road.fromZ());
            row.addProperty("toX", road.toX());
            row.addProperty("toZ", road.toZ());
            row.addProperty("kind", road.kind());
            row.addProperty("seen", road.lastSeenTick());
            roads.add(row);
        }
        root.add("roads", roads);
        return root.toString();
    }

    public static Optional<TerrainGrid> decode(String json) {
        if (json == null || json.isBlank() || json.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("formatVersion") || root.get("formatVersion").getAsInt() != FORMAT_VERSION) {
                return Optional.empty();
            }
            JsonArray rows = root.has("cells") ? root.getAsJsonArray("cells") : new JsonArray();
            JsonArray siteRows = root.has("sites") ? root.getAsJsonArray("sites") : new JsonArray();
            JsonArray roadRows = root.has("roads") ? root.getAsJsonArray("roads") : new JsonArray();
            if (rows.size() > TerrainGrid.MAX_CELLS
                    || siteRows.size() > TerrainGrid.MAX_SITES
                    || roadRows.size() > TerrainGrid.MAX_ROADS) {
                return Optional.empty();
            }
            TerrainGrid grid = new TerrainGrid();
            if (root.has("generation"))
                grid.setGeneration(root.get("generation").getAsLong());
            for (var element : rows) {
                JsonObject row = element.getAsJsonObject();
                TerrainCell cell = new TerrainCell(
                        new TerrainCell.CellKey(
                                row.get("dimension").getAsString(),
                                row.get("x").getAsInt(),
                                row.get("z").getAsInt()),
                        row.get("biome").getAsString(),
                        row.get("surfaceY").getAsInt(),
                        row.get("water").getAsBoolean(),
                        row.has("landmark") ? row.get("landmark").getAsString() : "",
                        row.get("seen").getAsLong());
                grid.observe(cell);
            }
            for (var element : siteRows) {
                JsonObject row = element.getAsJsonObject();
                grid.addSite(new TerrainSite(
                        row.get("dimension").getAsString(),
                        row.get("x").getAsInt(),
                        row.get("z").getAsInt(),
                        row.get("kind").getAsString(),
                        row.get("name").getAsString(),
                        row.get("seen").getAsLong()));
            }
            for (var element : roadRows) {
                JsonObject row = element.getAsJsonObject();
                grid.addRoad(new TerrainRoad(
                        row.get("dimension").getAsString(),
                        row.get("fromX").getAsInt(),
                        row.get("fromZ").getAsInt(),
                        row.get("toX").getAsInt(),
                        row.get("toZ").getAsInt(),
                        row.get("kind").getAsString(),
                        row.get("seen").getAsLong()));
            }
            return Optional.of(grid);
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
