package dev.friendai.features.acquisition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The steps to get an item, what could not be obtained, and how many steps the plan has.
 *
 * <p>{@code work} is what the search actually cost — provider evaluations and inventory simulations — and
 * {@code limited} says the pass ran out of that budget before it could decide. A limited pass is not evidence that
 * the item cannot be had: the caller must report a planning limit, never a missing material.
 */
public record ObtainPlan(
        List<ObtainStep> steps,
        Map<String, Integer> missing,
        int estimatedSteps,
        boolean conditional,
        Work work,
        boolean limited) {
    /**
     * The measured cost of one planning pass. Both counters bound the recursion: an evaluation may copy the simulated
     * inventory per candidate provider, so a book with many alternatives costs far more copies than evaluations.
     */
    public record Work(int evaluations, int copies) {
        public static final Work ZERO = new Work(0, 0);

        public Work {
            if (evaluations < 0 || copies < 0) {
                throw new IllegalArgumentException("work counters are never negative");
            }
        }
    }

    public ObtainPlan(List<ObtainStep> steps, Map<String, Integer> missing, int estimatedSteps) {
        this(steps, missing, estimatedSteps, false);
    }

    public ObtainPlan(List<ObtainStep> steps, Map<String, Integer> missing, int estimatedSteps, boolean conditional) {
        this(steps, missing, estimatedSteps, conditional, Work.ZERO, false);
    }

    public ObtainPlan {
        steps = List.copyOf(steps);
        missing = Collections.unmodifiableMap(new LinkedHashMap<>(missing));
        work = work == null ? Work.ZERO : work;
    }

    /** True when every needed item can be obtained and the search was allowed to finish. */
    public boolean ok() {
        return !limited && missing.isEmpty() && !conditional;
    }

    /** A conditional prefix is useful to execute up to its visit/search boundary. */
    public boolean actionable() {
        return !steps.isEmpty() && (ok() || conditional);
    }
}
