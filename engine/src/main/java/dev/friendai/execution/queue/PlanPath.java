package dev.friendai.execution.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reverses the display paths the interpreter builds for a plan node: {@code "step 3"}, {@code "step 3 → then #2"},
 * {@code "step 3 → repeat → step 4"} and every nesting of those forms. A parsed {@link Target} carries the
 * interpreter's own coordinates: the {@link PlanCursor.Frame#route()} pairs of 0-based node index and branch kind
 * (1 then, 2 else, 3 repeat), plus the 0-based index of the node itself inside that sequence.
 *
 * <p>Path numbers are 1-based because that is how they are printed; they are converted here. Only shapes the
 * interpreter can emit are accepted: the walk must start at a root {@code step}, every branch marker must close the
 * node before it, and a repeat body must open with a {@code step}. Anything else — empty text, unknown words, a
 * dangling marker, a zero or non-numeric number, segments out of the interpreter's order — yields
 * {@link Optional#empty()} instead of a guess.
 */
public final class PlanPath {
    private static final int THEN = 1;
    private static final int ELSE = 2;
    private static final int REPEAT = 3;

    private static final String SEPARATOR = " → ";
    private static final Pattern SPLIT = Pattern.compile(Pattern.quote(SEPARATOR));
    private static final Pattern STEP = Pattern.compile("step ([1-9]\\d*)");
    private static final Pattern BRANCH = Pattern.compile("(then|else) #([1-9]\\d*)");

    private PlanPath() {}

    /** The sequence a path names and the node inside it, both 0-based. */
    public record Target(List<Integer> route, int index) {
        public Target {
            route = List.copyOf(route == null ? List.of() : route);
            if (index < 0 || route.size() % 2 != 0) throw new IllegalArgumentException("invalid target");
            for (int i = 0; i < route.size(); i += 2) {
                if (route.get(i) < 0 || route.get(i + 1) < THEN || route.get(i + 1) > REPEAT) {
                    throw new IllegalArgumentException("invalid route");
                }
            }
        }
    }

    /**
     * Parses one node path, or returns empty when the text is not a path the interpreter produces. Spaces around the
     * whole path and around each arrow-separated segment are ignored.
     */
    public static Optional<Target> parse(String path) {
        if (path == null) return Optional.empty();
        String text = path.strip();
        if (text.isEmpty()) return Optional.empty();
        String[] segments = SPLIT.split(text, -1);
        Integer node = step(segments[0].strip());
        if (node == null) return Optional.empty();
        List<Integer> route = new ArrayList<>();
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i].strip();
            if (segment.equals("repeat")) {
                if (i + 1 >= segments.length) return Optional.empty();
                Integer body = step(segments[++i].strip());
                if (body == null) return Optional.empty();
                route.add(node);
                route.add(REPEAT);
                node = body;
                continue;
            }
            Matcher branch = BRANCH.matcher(segment);
            if (!branch.matches()) return Optional.empty();
            Integer child = index(branch.group(2));
            if (child == null) return Optional.empty();
            route.add(node);
            route.add(branch.group(1).equals("then") ? THEN : ELSE);
            node = child;
        }
        return Optional.of(new Target(route, node));
    }

    private static Integer step(String segment) {
        Matcher matcher = STEP.matcher(segment);
        return matcher.matches() ? index(matcher.group(1)) : null;
    }

    private static Integer index(String digits) {
        try {
            return Integer.parseInt(digits) - 1;
        } catch (NumberFormatException overflow) {
            return null;
        }
    }
}
