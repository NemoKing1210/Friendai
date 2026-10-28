package dev.friendai.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Works out where the companion button goes in the pause menu's row of icon buttons. */
public final class PauseRowPlacement {
    private static final int DEFAULT_GAP = 4;
    private static final int FALLBACK_MARGIN = 8;

    private PauseRowPlacement() {}

    /**
     * The row is the largest group of icons sharing one y (ties: the smallest y). The new button goes after the
     * last icon, same y and size, at the row's own spacing. With no icons it goes to the top-right corner.
     */
    public static Rect place(List<Rect> icons, int screenWidth, int size) {
        if (icons.isEmpty()) {
            return new Rect(screenWidth - size - FALLBACK_MARGIN, FALLBACK_MARGIN, size, size);
        }
        Map<Integer, List<Rect>> byY = new LinkedHashMap<>();
        for (Rect r : icons) {
            byY.computeIfAbsent(r.y(), y -> new ArrayList<>()).add(r);
        }
        List<Rect> row = byY.entrySet().stream()
                .max(Comparator.<Map.Entry<Integer, List<Rect>>>comparingInt(
                                e -> e.getValue().size())
                        .thenComparing(Comparator.comparingInt((Map.Entry<Integer, List<Rect>> e) -> e.getKey())
                                .reversed()))
                .orElseThrow()
                .getValue();
        List<Rect> sorted =
                row.stream().sorted(Comparator.comparingInt(Rect::x)).toList();
        Rect last = sorted.get(sorted.size() - 1);
        int gap = sorted.size() > 1 ? last.x() - sorted.get(sorted.size() - 2).right() : DEFAULT_GAP;
        Rect first = sorted.get(0);
        return new Rect(last.right() + gap, first.y(), first.width(), first.height());
    }
}
