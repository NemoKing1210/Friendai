package dev.friendai.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Parses the compact blueprint format the LLM writes: a palette, a list of ops (box, walls, floor, line, roof,
 * door, window) and optional ASCII layers that override the ops. Every problem is collected with its location.
 */
public final class BlueprintParser {
    private static final String DEFAULT_NAME = "blueprint";
    private static final String AIR = "minecraft:air";
    private static final String DEFAULT_WINDOW = "minecraft:glass_pane";

    private final Predicate<String> knownState;
    private final List<String> errors = new ArrayList<>();
    private final Map<Character, String> palette = new LinkedHashMap<>();
    private final Set<Character> declaredPaletteKeys = new LinkedHashSet<>();
    private final Map<Pos, String> blocks = new LinkedHashMap<>();
    private final Set<String> reportedUnknownBlocks = new LinkedHashSet<>();

    private BlueprintParser(Predicate<String> knownState) {
        this.knownState = knownState;
    }

    /** @param knownState whether a block state string exists; the Minecraft layer implements it */
    public static ParseResult parse(String json, Predicate<String> knownState) {
        return new BlueprintParser(knownState).run(json);
    }

    private record Pos(int x, int y, int z) {}

    private ParseResult run(String json) {
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                return ParseResult.failed(List.of("the blueprint must be a JSON object"));
            }
            root = element.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            return ParseResult.failed(List.of("invalid JSON: " + e.getMessage()));
        }

        String name = root.has("name") && root.get("name").isJsonPrimitive()
                ? root.get("name").getAsString()
                : DEFAULT_NAME;
        readPalette(root);
        readOps(root);
        readLayers(root);

        if (errors.isEmpty()) {
            checkSizeAndCount();
        }
        if (!errors.isEmpty()) {
            return ParseResult.failed(errors);
        }
        return ParseResult.ok(build(name));
    }

    private void readPalette(JsonObject root) {
        if (!root.has("palette")) {
            return;
        }
        if (!root.get("palette").isJsonObject()) {
            errors.add("palette: must be an object of one-character keys");
            return;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("palette").entrySet()) {
            String key = entry.getKey();
            if (!entry.getValue().isJsonPrimitive()) {
                errors.add("palette: key \"" + key + "\" must map to a block state");
                continue;
            }
            String state = entry.getValue().getAsString();
            if (key.length() != 1) {
                errors.add("palette: key \"" + key + "\" must be one character");
                continue;
            }
            if (key.equals(".")) {
                errors.add("palette: key \".\" is reserved");
                continue;
            }
            declaredPaletteKeys.add(key.charAt(0));
            if (!checkKnown(state)) {
                continue;
            }
            palette.put(key.charAt(0), state);
        }
    }

    private void readOps(JsonObject root) {
        if (!root.has("ops")) {
            return;
        }
        if (!root.get("ops").isJsonArray()) {
            errors.add("ops: must be an array");
            return;
        }
        JsonArray ops = root.getAsJsonArray("ops");
        for (int i = 0; i < ops.size(); i++) {
            if (!ops.get(i).isJsonObject()) {
                errors.add("ops[" + i + "]: must be an object");
                continue;
            }
            readOp(i, ops.get(i).getAsJsonObject());
        }
    }

    private void readOp(int index, JsonObject op) {
        String location = "ops[" + index + "]";
        if (!op.has("op") || !op.get("op").isJsonPrimitive()) {
            errors.add(location + ": missing 'op'");
            return;
        }
        String kind = op.get("op").getAsString();
        switch (kind) {
            case "box", "floor", "walls", "line" -> readShape(location, kind, op);
            case "roof" -> readRoof(location, op);
            case "door" -> readDoor(location, op);
            case "window" -> readWindow(location, op);
            default -> errors.add(location + ": unknown op '" + kind + "'");
        }
    }

    private void readShape(String location, String kind, JsonObject op) {
        int[] from = vector(op, "from");
        int[] to = vector(op, "to");
        if (from == null || to == null) {
            errors.add(location + ": missing 'from' or 'to'");
            return;
        }
        if (below(from, to)) {
            errors.add(location + ": 'to' is below 'from'");
            return;
        }
        String block = resolveBlock(location, op);
        if (block == null) {
            return;
        }
        switch (kind) {
            case "box" -> fillBox(from, to, block, op.has("hollow") && op.get("hollow").getAsBoolean());
            case "floor" -> fillFloor(from, to, block);
            case "walls" -> fillWalls(from, to, block, corners(op));
            case "line" -> readLine(location, from, to, block);
            default -> throw new IllegalStateException(kind);
        }
    }

    private void readLine(String location, int[] from, int[] to, String block) {
        int changes = 0;
        for (int axis = 0; axis < 3; axis++) {
            if (from[axis] != to[axis]) {
                changes++;
            }
        }
        if (changes > 1) {
            errors.add(location + ": line must be axis-aligned");
            return;
        }
        for (int x = from[0]; x <= to[0]; x++) {
            for (int y = from[1]; y <= to[1]; y++) {
                for (int z = from[2]; z <= to[2]; z++) {
                    put(x, y, z, block);
                }
            }
        }
    }

    private String corners(JsonObject op) {
        if (!op.has("corners")) {
            return null;
        }
        String key = op.get("corners").isJsonPrimitive() ? op.get("corners").getAsString() : null;
        if (key == null) {
            return null;
        }
        return resolve(key);
    }

    private void fillBox(int[] from, int[] to, String block, boolean hollow) {
        for (int x = from[0]; x <= to[0]; x++) {
            for (int y = from[1]; y <= to[1]; y++) {
                for (int z = from[2]; z <= to[2]; z++) {
                    boolean edge = x == from[0] || x == to[0]
                            || y == from[1] || y == to[1]
                            || z == from[2] || z == to[2];
                    if (hollow && !edge) {
                        continue;
                    }
                    put(x, y, z, block);
                }
            }
        }
    }

    private void fillFloor(int[] from, int[] to, String block) {
        for (int x = from[0]; x <= to[0]; x++) {
            for (int z = from[2]; z <= to[2]; z++) {
                put(x, from[1], z, block);
            }
        }
    }

    private void fillWalls(int[] from, int[] to, String block, String corner) {
        for (int y = from[1]; y <= to[1]; y++) {
            for (int x = from[0]; x <= to[0]; x++) {
                for (int z = from[2]; z <= to[2]; z++) {
                    boolean xEdge = x == from[0] || x == to[0];
                    boolean zEdge = z == from[2] || z == to[2];
                    if (!xEdge && !zEdge) {
                        continue;
                    }
                    put(x, y, z, corner != null && xEdge && zEdge ? corner : block);
                }
            }
        }
    }

    private record RoofShape(int[] from, int[] to, String style, String block, String axis) {}

    private void readRoof(String location, JsonObject op) {
        int[] from = vector(op, "from");
        int[] to = vector(op, "to");
        if (from == null || to == null) {
            errors.add(location + ": missing 'from' or 'to'");
            return;
        }
        if (below(from, to)) {
            errors.add(location + ": 'to' is below 'from'");
            return;
        }
        String style = op.has("style") ? op.get("style").getAsString() : "gable";
        String block = resolveBlock(location, op);
        if (block == null) {
            return;
        }
        if (!style.equals("gable") && !style.equals("pyramid") && !style.equals("flat")) {
            errors.add(location + ": unknown roof style '" + style + "'");
            return;
        }
        String axis = op.has("axis") ? op.get("axis").getAsString() : "x";
        RoofShape roof = new RoofShape(from, to, style, block, axis);
        switch (style) {
            case "flat" -> fillFloor(from, to, block);
            case "pyramid" -> buildPyramid(roof);
            case "gable" -> {
                if (!axis.equals("x") && !axis.equals("z")) {
                    errors.add(location + ": gable roof axis must be x or z");
                    return;
                }
                buildGable(roof);
            }
            default -> throw new IllegalStateException(style);
        }
    }

    private void buildGable(RoofShape roof) {
        int[] from = roof.from();
        int[] to = roof.to();
        String stairs = stairsId(roof.block());
        String ridge = ridgeBlock(roof.block());
        boolean alongX = roof.axis().equals("x");
        int span = (alongX ? to[2] - from[2] : to[0] - from[0]) + 1;
        int levels = (span + 1) / 2;
        for (int level = 0; level < levels; level++) {
            int y = from[1] + level;
            int near = (alongX ? from[2] : from[0]) + level;
            int far = (alongX ? to[2] : to[0]) - level;
            if (near == far) {
                for (int other = 0; other < (alongX ? to[0] - from[0] : to[2] - from[2]) + 1; other++) {
                    if (alongX) {
                        put(from[0] + other, y, near, ridge);
                    } else {
                        put(near, y, from[2] + other, ridge);
                    }
                }
                continue;
            }
            String nearState = stairState(stairs, alongX ? "south" : "east");
            String farState = stairState(stairs, alongX ? "north" : "west");
            for (int other = 0; other < (alongX ? to[0] - from[0] : to[2] - from[2]) + 1; other++) {
                int x = alongX ? from[0] + other : near;
                int z = alongX ? near : from[2] + other;
                put(x, y, z, nearState);
                x = alongX ? from[0] + other : far;
                z = alongX ? far : from[2] + other;
                put(x, y, z, farState);
            }
        }
    }

    private void buildPyramid(RoofShape roof) {
        int[] from = roof.from();
        int[] to = roof.to();
        String stairs = stairsId(roof.block());
        String ridge = ridgeBlock(roof.block());
        int x1 = from[0];
        int x2 = to[0];
        int z1 = from[2];
        int z2 = to[2];
        int y = from[1];
        while (x1 <= x2 && z1 <= z2) {
            if (x1 == x2 && z1 == z2) {
                put(x1, y, z1, ridge);
            } else if (x1 == x2) {
                for (int z = z1; z <= z2; z++) {
                    put(x1, y, z, ridge);
                }
            } else if (z1 == z2) {
                for (int x = x1; x <= x2; x++) {
                    put(x, y, z1, ridge);
                }
            } else {
                String north = stairState(stairs, "south");
                String south = stairState(stairs, "north");
                String west = stairState(stairs, "east");
                String east = stairState(stairs, "west");
                for (int x = x1; x <= x2; x++) {
                    put(x, y, z1, north);
                    put(x, y, z2, south);
                }
                for (int z = z1 + 1; z < z2; z++) {
                    put(x1, y, z, west);
                    put(x2, y, z, east);
                }
            }
            x1++;
            x2--;
            z1++;
            z2--;
            y++;
        }
    }

    private void readDoor(String location, JsonObject op) {
        int[] at = vector(op, "at");
        if (at == null) {
            errors.add(location + ": missing 'at'");
            return;
        }
        String block = resolveBlock(location, op);
        if (block == null) {
            return;
        }
        String facing = op.has("facing") ? op.get("facing").getAsString() : "south";
        String id = base(block);
        put(at[0], at[1], at[2], id + "[facing=" + facing + ",half=lower]");
        put(at[0], at[1] + 1, at[2], id + "[facing=" + facing + ",half=upper]");
    }

    private void readWindow(String location, JsonObject op) {
        int[] from = vector(op, "from");
        int[] to = vector(op, "to");
        if (from == null || to == null) {
            errors.add(location + ": missing 'from' or 'to'");
            return;
        }
        if (below(from, to)) {
            errors.add(location + ": 'to' is below 'from'");
            return;
        }
        String block = op.has("block") ? resolveBlock(location, op) : DEFAULT_WINDOW;
        if (block == null) {
            return;
        }
        if (!op.has("block") && !checkKnown(DEFAULT_WINDOW)) {
            return;
        }
        fillBox(from, to, block, false);
    }

    private void readLayers(JsonObject root) {
        if (!root.has("layers")) {
            return;
        }
        if (!root.get("layers").isJsonArray()) {
            errors.add("layers: must be an array");
            return;
        }
        JsonArray layers = root.getAsJsonArray("layers");
        for (int layer = 0; layer < layers.size(); layer++) {
            if (!layers.get(layer).isJsonArray()) {
                errors.add("layer " + layer + ": must be an array of rows");
                continue;
            }
            JsonArray rows = layers.get(layer).getAsJsonArray();
            int expected = -1;
            for (int row = 0; row < rows.size(); row++) {
                if (!rows.get(row).isJsonPrimitive()) {
                    errors.add("layer " + layer + ", row " + row + ": must be a string");
                    continue;
                }
                String text = rows.get(row).getAsString();
                if (expected < 0) {
                    expected = text.length();
                } else if (text.length() != expected) {
                    errors.add("layer " + layer + ": row " + row + " has length " + text.length()
                            + ", expected " + expected);
                    continue;
                }
                for (int x = 0; x < text.length(); x++) {
                    char symbol = text.charAt(x);
                    if (symbol == '.') {
                        continue;
                    }
                    String state = palette.get(symbol);
                    if (state == null) {
                        errors.add("layer " + layer + ", row " + row + ": unknown symbol '" + symbol + "'");
                        continue;
                    }
                    put(x, layer, row, state);
                }
            }
        }
    }

    private void checkSizeAndCount() {
        if (blocks.isEmpty()) {
            errors.add("the blueprint has no blocks");
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Pos pos : blocks.keySet()) {
            minX = Math.min(minX, pos.x());
            minY = Math.min(minY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxX = Math.max(maxX, pos.x());
            maxY = Math.max(maxY, pos.y());
            maxZ = Math.max(maxZ, pos.z());
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int depth = maxZ - minZ + 1;
        if (width > Blueprint.MAX_SIZE || height > Blueprint.MAX_SIZE || depth > Blueprint.MAX_SIZE) {
            errors.add("size " + width + "×" + height + "×" + depth + " exceeds " + Blueprint.MAX_SIZE);
        }
        if (blocks.size() > Blueprint.MAX_BLOCKS) {
            errors.add("block count " + blocks.size() + " exceeds " + Blueprint.MAX_BLOCKS);
        }
    }

    private Blueprint build(String name) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Pos pos : blocks.keySet()) {
            minX = Math.min(minX, pos.x());
            minY = Math.min(minY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxX = Math.max(maxX, pos.x());
            maxY = Math.max(maxY, pos.y());
            maxZ = Math.max(maxZ, pos.z());
        }
        List<BlockSpec> specs = new ArrayList<>(blocks.size());
        for (Map.Entry<Pos, String> entry : blocks.entrySet()) {
            Pos pos = entry.getKey();
            specs.add(new BlockSpec(pos.x() - minX, pos.y() - minY, pos.z() - minZ, entry.getValue()));
        }
        specs.sort((a, b) -> {
            if (a.y() != b.y()) {
                return Integer.compare(a.y(), b.y());
            }
            if (a.z() != b.z()) {
                return Integer.compare(a.z(), b.z());
            }
            return Integer.compare(a.x(), b.x());
        });
        return new Blueprint(name, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, specs);
    }

    private void put(int x, int y, int z, String state) {
        blocks.put(new Pos(x, y, z), state);
    }

    /** A palette key or a block id from the op's {@code block} field, or null after recording an error. */
    private String resolveBlock(String location, JsonObject op) {
        if (!op.has("block") || !op.get("block").isJsonPrimitive()) {
            errors.add(location + ": missing 'block'");
            return null;
        }
        String block = resolve(op.get("block").getAsString());
        return block;
    }

    private String resolve(String reference) {
        if (reference.length() == 1 && declaredPaletteKeys.contains(reference.charAt(0))) {
            // A palette key whose own value already failed is not reported again as a block id.
            return palette.get(reference.charAt(0));
        }
        if (!checkKnown(reference)) {
            return null;
        }
        return reference;
    }

    private boolean checkKnown(String state) {
        if (knownState.test(state)) {
            return true;
        }
        if (reportedUnknownBlocks.add(state)) {
            errors.add("unknown block " + state);
        }
        return false;
    }

    private static String base(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    private String stairsId(String block) {
        return base(block);
    }

    /** A stairs state facing uphill; a slab or full block keeps its own shape. */
    private static String stairState(String id, String facing) {
        if (id.endsWith("_slab")) {
            return id + "[type=bottom]";
        }
        return id + "[facing=" + facing + ",half=bottom]";
    }

    /** The full block (or slab) that matches a stairs id, falling back to the id itself. */
    private String ridgeBlock(String block) {
        String id = base(block);
        if (id.endsWith("_slab") || id.endsWith("_stairs")) {
            String stem = id.substring(0, id.lastIndexOf('_'));
            for (String candidate : new String[] {stem, stem + "s", stem + "es", stem + "_planks", id}) {
                if (knownState.test(candidate)) {
                    return candidate;
                }
            }
        }
        return id;
    }

    private static int[] vector(JsonObject op, String name) {
        if (!op.has(name) || !op.get(name).isJsonArray()) {
            return null;
        }
        JsonArray array = op.getAsJsonArray(name);
        if (array.size() != 3) {
            return null;
        }
        int[] vector = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!array.get(i).isJsonPrimitive()) {
                return null;
            }
            vector[i] = array.get(i).getAsInt();
        }
        return vector;
    }

    private static boolean below(int[] from, int[] to) {
        return to[0] < from[0] || to[1] < from[1] || to[2] < from[2];
    }
}
