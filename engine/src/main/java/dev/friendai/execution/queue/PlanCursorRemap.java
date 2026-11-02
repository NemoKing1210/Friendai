package dev.friendai.execution.queue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shifts a plan cursor after one sequence gains {@code count} nodes at {@code index}. The route pairs are the
 * interpreter's authoritative coordinates; path strings are presentation only and are rebuilt from the shifted
 * routes. A pending action is never remapped: callers must refuse active frames before patching.
 */
public final class PlanCursorRemap {
    private PlanCursorRemap() {}

    public static PlanCursor afterInsert(PlanCursor cursor, List<Integer> parentRoute, int index, int count) {
        if (cursor == null || parentRoute == null || parentRoute.size() % 2 != 0 || index < 0 || count < 1) {
            throw new IllegalArgumentException("invalid remap");
        }
        if (!cursor.pendingActionPath().isEmpty()) {
            throw new IllegalArgumentException("a pending action cannot be remapped");
        }
        List<PlanCursor.Frame> frames = new ArrayList<>(cursor.stack().size());
        for (PlanCursor.Frame frame : cursor.stack()) {
            frames.add(shift(frame, parentRoute, index, count));
        }
        return new PlanCursor(
                frames,
                shiftBooleanKeys(cursor.selectedBranches(), parentRoute, index, count),
                shiftKeys(cursor.repeatIterations(), parentRoute, index, count),
                cursor.startTick(),
                cursor.startDistance(),
                "",
                null,
                0);
    }

    private static PlanCursor.Frame shift(PlanCursor.Frame frame, List<Integer> parentRoute, int index, int count) {
        List<Integer> route = frame.route();
        if (!startsWith(route, parentRoute)) return frame;
        int next = frame.nextIndex();
        List<Integer> shifted = new ArrayList<>(route);
        if (route.size() == parentRoute.size()) {
            if (next >= index) next += count;
        } else {
            int at = parentRoute.size();
            if (route.get(at) >= index) shifted.set(at, route.get(at) + count);
        }
        Paths paths = paths(shifted, parentRoute, index, count);
        return new PlanCursor.Frame(shifted, next, paths.prefix(), paths.owner(), frame.repeatBody());
    }

    private static Map<String, Integer> shiftKeys(
            Map<String, Integer> values, List<Integer> parentRoute, int index, int count) {
        Map<String, Integer> out = new LinkedHashMap<>();
        values.forEach((key, value) -> out.put(shiftKey(key, parentRoute, index, count), value));
        return out;
    }

    private static Map<String, Boolean> shiftBooleanKeys(
            Map<String, Boolean> values, List<Integer> parentRoute, int index, int count) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        values.forEach((key, value) -> out.put(shiftKey(key, parentRoute, index, count), value));
        return out;
    }

    /** Only the first numeric segment at the insertion level changes; deeper segments are already rebased by routes. */
    private static String shiftKey(String key, List<Integer> parentRoute, int index, int count) {
        String prefix = pathPrefix(parentRoute);
        String marker =
                parentRoute.isEmpty() ? "step " : (repeatBody(parentRoute) ? prefix + " → step " : prefix + " #");
        if (!key.startsWith(marker)) return key;
        int start = marker.length();
        int end = start;
        while (end < key.length() && Character.isDigit(key.charAt(end))) end++;
        if (end == start) return key;
        int number = Integer.parseInt(key.substring(start, end));
        int shifted = number > index + 1 ? number + count : number;
        return marker + shifted + key.substring(end);
    }

    private static boolean startsWith(List<Integer> route, List<Integer> prefix) {
        if (route.size() < prefix.size()) return false;
        for (int i = 0; i < prefix.size(); i++) if (!route.get(i).equals(prefix.get(i))) return false;
        return true;
    }

    private static boolean repeatBody(List<Integer> route) {
        return !route.isEmpty() && route.getLast() == 3;
    }

    private static Paths paths(List<Integer> route, List<Integer> parentRoute, int index, int count) {
        if (route.size() <= parentRoute.size()) {
            return new Paths(pathPrefix(route), ownerPath(route));
        }
        return new Paths(pathPrefix(route), ownerPath(route));
    }

    /** Rebuilds the display prefix from the shifted route, with the insertion level already shifted. */
    private static String pathPrefix(List<Integer> route) {
        String prefix = "";
        for (int offset = 0; offset < route.size(); offset += 2) {
            int node = route.get(offset);
            int kind = route.get(offset + 1);
            String nodePath = prefix.isEmpty()
                    ? "step " + (node + 1)
                    : (repeatBody(route.subList(0, offset)) ? prefix + " → step " : prefix + " #") + (node + 1);
            prefix = switch (kind) {
                case 1 -> nodePath + " → then";
                case 2 -> nodePath + " → else";
                case 3 -> nodePath + " → repeat";
                default -> nodePath;
            };
        }
        return prefix;
    }

    private static String ownerPath(List<Integer> route) {
        String prefix = pathPrefix(route);
        for (String suffix : List.of(" → then", " → else", " → repeat")) {
            if (prefix.endsWith(suffix)) {
                return prefix.substring(0, prefix.length() - suffix.length());
            }
        }
        return "";
    }

    private record Paths(String prefix, String owner) {}
}
