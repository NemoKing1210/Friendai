package dev.friendai.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Parametric structures the LLM can ask for by name instead of writing a blueprint by hand. Each generator builds
 * the same op list the {@link BlueprintParser} understands, so the output goes through the same validation.
 */
public final class Generators {
    private static final String DEFAULT_WINDOW = "minecraft:glass_pane";
    private static final String DEFAULT_DOOR = "minecraft:oak_door";
    private static final String DEFAULT_GATE = "minecraft:oak_fence_gate";
    /** A torch on the west wall, pointing east into the room. */
    private static final String TORCH_EAST = "minecraft:wall_torch[facing=east]";
    /** A torch on the east wall, pointing west into the room. */
    private static final String TORCH_WEST = "minecraft:wall_torch[facing=west]";

    private static final Set<String> SIDES = Set.of("north", "east", "south", "west");

    private Generators() {}

    /** @param knownState whether a block state string exists; the Minecraft layer implements it */
    public static ParseResult generate(JsonObject spec, Predicate<String> knownState) {
        List<String> errors = new ArrayList<>();
        String type = required(spec, "type", errors);
        if (type == null) {
            return ParseResult.failed(errors);
        }
        JsonArray ops = new JsonArray();
        switch (type) {
            case "house" -> house(spec, ops, errors);
            case "tower" -> tower(spec, ops, errors);
            case "wall" -> wall(spec, ops, errors);
            case "fence" -> fence(spec, ops, errors);
            case "platform" -> platform(spec, ops, errors);
            case "bridge" -> bridge(spec, ops, errors);
            default -> errors.add("unknown type '" + type + "'");
        }
        if (!errors.isEmpty()) {
            return ParseResult.failed(errors);
        }
        JsonObject root = new JsonObject();
        root.addProperty("name", spec.has("name") ? spec.get("name").getAsString() : type);
        root.add("ops", ops);
        return BlueprintParser.parse(root.toString(), knownState);
    }

    private static void house(JsonObject spec, JsonArray ops, List<String> errors) {
        int width = range(spec, "width", 5, 15, errors);
        int depth = range(spec, "depth", 5, 15, errors);
        int floors = spec.has("floors") ? range(spec, "floors", 1, 3, errors) : 1;
        String walls = required(spec, "walls", errors);
        String floor = required(spec, "floor", errors);
        String roof = required(spec, "roof", errors);
        String style = spec.has("roof_style") ? spec.get("roof_style").getAsString() : "gable";
        String window = spec.has("windows") ? spec.get("windows").getAsString() : DEFAULT_WINDOW;
        String side = spec.has("door_side") ? spec.get("door_side").getAsString() : "south";
        if (!SIDES.contains(side)) {
            errors.add("door side '" + side + "' is not north, east, south or west");
        }
        if (walls == null || floor == null || roof == null) {
            return;
        }
        String door = spec.has("door") ? spec.get("door").getAsString() : deriveDoor(walls);
        String frame = spec.has("frame") ? spec.get("frame").getAsString() : null;
        int wallHeight = 3 * floors;

        box(ops, 0, 0, 0, width - 1, 0, depth - 1, floor);
        walls(ops, 0, 1, 0, width - 1, wallHeight, depth - 1, walls, frame);
        if (frame != null) {
            line(ops, 0, 0, 0, width - 1, 0, 0, frame);
            line(ops, 0, 0, depth - 1, width - 1, 0, depth - 1, frame);
            line(ops, 0, 0, 0, 0, 0, depth - 1, frame);
            line(ops, width - 1, 0, 0, width - 1, 0, depth - 1, frame);
        }

        int doorX;
        int doorZ;
        switch (side) {
            case "north" -> {
                doorX = width / 2;
                doorZ = 0;
            }
            case "west" -> {
                doorX = 0;
                doorZ = depth / 2;
            }
            case "east" -> {
                doorX = width - 1;
                doorZ = depth / 2;
            }
            default -> {
                doorX = width / 2;
                doorZ = depth - 1;
            }
        }
        door(ops, doorX, 1, doorZ, side, door);

        List<Integer> alongX = spread(width);
        List<Integer> alongZ = spread(depth);
        for (int floorIndex = 0; floorIndex < floors; floorIndex++) {
            int y = 2 + 3 * floorIndex;
            for (int x : alongX) {
                if (!(side.equals("north") && x == doorX)) {
                    box(ops, x, y, 0, x, y, 0, window);
                }
                if (!(side.equals("south") && x == doorX)) {
                    box(ops, x, y, depth - 1, x, y, depth - 1, window);
                }
            }
            for (int z : alongZ) {
                if (!(side.equals("west") && z == doorZ)) {
                    box(ops, 0, y, z, 0, y, z, window);
                }
                if (!(side.equals("east") && z == doorZ)) {
                    box(ops, width - 1, y, z, width - 1, y, z, window);
                }
            }
            if (spec.has("torches") && spec.get("torches").getAsBoolean()) {
                // Wall torches, not standing ones: interior cells have no floor to stand a torch on above the first.
                box(ops, 1, 1 + 3 * floorIndex, 1, 1, 1 + 3 * floorIndex, 1, TORCH_EAST);
                box(
                        ops,
                        width - 2,
                        1 + 3 * floorIndex,
                        depth - 2,
                        width - 2,
                        1 + 3 * floorIndex,
                        depth - 2,
                        TORCH_WEST);
            }
        }

        String axis = width >= depth ? "x" : "z";
        JsonObject roofOp = op("roof");
        roofOp.add("from", vec(0, wallHeight + 1, 0));
        roofOp.add("to", vec(width - 1, wallHeight + 1, depth - 1));
        roofOp.addProperty("style", style);
        roofOp.addProperty("block", roof);
        roofOp.addProperty("axis", axis);
        ops.add(roofOp);
    }

    private static void tower(JsonObject spec, JsonArray ops, List<String> errors) {
        int size = range(spec, "size", 3, 9, errors);
        int height = range(spec, "height", 4, 24, errors);
        String walls = required(spec, "walls", errors);
        if (walls == null) {
            return;
        }
        box(ops, 0, 0, 0, size - 1, 0, size - 1, walls);
        walls(ops, 0, 1, 0, size - 1, height - 1, size - 1, walls, null);
        if (spec.has("ladder") && spec.get("ladder").getAsBoolean()) {
            line(ops, size / 2, 1, 1, size / 2, height - 1, 1, "minecraft:ladder[facing=south]");
        }
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean edge = x == 0 || x == size - 1 || z == 0 || z == size - 1;
                if (edge && (x + z) % 2 == 0) {
                    box(ops, x, height, z, x, height, z, walls);
                }
            }
        }
    }

    private static void wall(JsonObject spec, JsonArray ops, List<String> errors) {
        int length = range(spec, "length", 1, 32, errors);
        int height = range(spec, "height", 1, 8, errors);
        String block = required(spec, "block", errors);
        String axis = spec.has("axis") ? spec.get("axis").getAsString() : "x";
        if (!axis.equals("x") && !axis.equals("z")) {
            errors.add("axis must be x or z");
        }
        if (block == null) {
            return;
        }
        if (axis.equals("z")) {
            box(ops, 0, 0, 0, 0, height - 1, length - 1, block);
        } else {
            box(ops, 0, 0, 0, length - 1, height - 1, 0, block);
        }
    }

    private static void fence(JsonObject spec, JsonArray ops, List<String> errors) {
        int width = range(spec, "width", 3, 32, errors);
        int depth = range(spec, "depth", 3, 32, errors);
        String block = required(spec, "block", errors);
        String side = spec.has("gate_side") ? spec.get("gate_side").getAsString() : "south";
        if (!SIDES.contains(side)) {
            errors.add("gate side '" + side + "' is not north, east, south or west");
        }
        if (block == null) {
            return;
        }
        String gate = spec.has("gate") ? spec.get("gate").getAsString() : deriveGate(block);
        walls(ops, 0, 0, 0, width - 1, 0, depth - 1, block, null);
        int gateX;
        int gateZ;
        String facing;
        switch (side) {
            case "north" -> {
                gateX = width / 2;
                gateZ = 0;
                facing = "south";
            }
            case "west" -> {
                gateX = 0;
                gateZ = depth / 2;
                facing = "east";
            }
            case "east" -> {
                gateX = width - 1;
                gateZ = depth / 2;
                facing = "west";
            }
            default -> {
                gateX = width / 2;
                gateZ = depth - 1;
                facing = "north";
            }
        }
        box(ops, gateX, 0, gateZ, gateX, 0, gateZ, gate + "[facing=" + facing + "]");
    }

    private static void platform(JsonObject spec, JsonArray ops, List<String> errors) {
        int width = range(spec, "width", 1, 32, errors);
        int depth = range(spec, "depth", 1, 32, errors);
        String block = required(spec, "block", errors);
        if (block == null) {
            return;
        }
        box(ops, 0, 0, 0, width - 1, 0, depth - 1, block);
    }

    private static void bridge(JsonObject spec, JsonArray ops, List<String> errors) {
        int length = range(spec, "length", 3, 32, errors);
        int width = range(spec, "width", 1, 5, errors);
        String block = required(spec, "block", errors);
        if (block == null) {
            return;
        }
        box(ops, 0, 0, 0, length - 1, 0, width - 1, block);
        if (spec.has("railing") && width > 1) {
            String railing = spec.get("railing").getAsString();
            line(ops, 0, 1, 0, length - 1, 1, 0, railing);
            line(ops, 0, 1, width - 1, length - 1, 1, width - 1, railing);
        }
    }

    /** The wall cells that get a window: one per three interior blocks, one block in from either corner. */
    private static List<Integer> spread(int length) {
        List<Integer> positions = new ArrayList<>();
        int count = (length - 2) / 3;
        for (int i = 0; i < count; i++) {
            positions.add(2 + 3 * i);
        }
        return positions;
    }

    private static String deriveDoor(String walls) {
        return walls.endsWith("_planks")
                ? walls.substring(0, walls.length() - "_planks".length()) + "_door"
                : DEFAULT_DOOR;
    }

    private static String deriveGate(String fence) {
        return fence.endsWith("_fence")
                ? fence.substring(0, fence.length() - "_fence".length()) + "_fence_gate"
                : DEFAULT_GATE;
    }

    private static int range(JsonObject spec, String name, int min, int max, List<String> errors) {
        if (!spec.has(name) || !spec.get(name).isJsonPrimitive()) {
            errors.add(name + " is required");
            return min;
        }
        int value = spec.get(name).getAsInt();
        if (value < min) {
            errors.add(name + " " + value + " is below " + min);
        } else if (value > max) {
            errors.add(name + " " + value + " is above " + max);
        }
        return value;
    }

    private static String required(JsonObject spec, String name, List<String> errors) {
        if (!spec.has(name) || !spec.get(name).isJsonPrimitive()) {
            errors.add(name + " is required");
            return null;
        }
        return spec.get(name).getAsString();
    }

    private static JsonObject op(String kind) {
        JsonObject op = new JsonObject();
        op.addProperty("op", kind);
        return op;
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    private static void box(JsonArray ops, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        JsonObject op = op("box");
        op.add("from", vec(x1, y1, z1));
        op.add("to", vec(x2, y2, z2));
        op.addProperty("block", block);
        ops.add(op);
    }

    private static void walls(
            JsonArray ops, int x1, int y1, int z1, int x2, int y2, int z2, String block, String corners) {
        JsonObject op = op("walls");
        op.add("from", vec(x1, y1, z1));
        op.add("to", vec(x2, y2, z2));
        op.addProperty("block", block);
        if (corners != null) {
            op.addProperty("corners", corners);
        }
        ops.add(op);
    }

    private static void line(JsonArray ops, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        JsonObject op = op("line");
        op.add("from", vec(x1, y1, z1));
        op.add("to", vec(x2, y2, z2));
        op.addProperty("block", block);
        ops.add(op);
    }

    private static void door(JsonArray ops, int x, int y, int z, String facing, String block) {
        JsonObject op = op("door");
        op.add("at", vec(x, y, z));
        op.addProperty("facing", facing);
        op.addProperty("block", block);
        ops.add(op);
    }
}
