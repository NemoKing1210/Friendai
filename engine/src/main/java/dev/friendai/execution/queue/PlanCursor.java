package dev.friendai.execution.queue;

import java.util.List;
import java.util.Map;

/** Immutable continuation state for a plan interpreter. */
public record PlanCursor(
        List<Frame> stack,
        Map<String, Boolean> selectedBranches,
        Map<String, Integer> repeatIterations,
        long startTick,
        long startDistance,
        String pendingActionPath,
        QueuedStep pendingAction,
        long pendingJobId) {
    public PlanCursor(
            List<Frame> stack,
            Map<String, Boolean> selectedBranches,
            Map<String, Integer> repeatIterations,
            long startTick,
            long startDistance,
            String pendingActionPath,
            QueuedStep pendingAction) {
        this(stack, selectedBranches, repeatIterations, startTick, startDistance, pendingActionPath, pendingAction, 0);
    }

    public PlanCursor {
        stack = List.copyOf(stack == null ? List.of() : stack);
        selectedBranches = Map.copyOf(selectedBranches == null ? Map.of() : selectedBranches);
        repeatIterations = Map.copyOf(repeatIterations == null ? Map.of() : repeatIterations);
        pendingActionPath = pendingActionPath == null ? "" : pendingActionPath;
        if (pendingActionPath.isEmpty()) pendingAction = null;
        else if (pendingAction == null) throw new IllegalArgumentException("pending action required");
        if (pendingActionPath.isEmpty() && pendingJobId != 0 || pendingJobId < 0)
            throw new IllegalArgumentException("invalid pending job");
    }

    public PlanCursor withPendingJob(long jobId) {
        return new PlanCursor(
                stack,
                selectedBranches,
                repeatIterations,
                startTick,
                startDistance,
                pendingActionPath,
                pendingAction,
                jobId);
    }

    public static PlanCursor start(long tick, long distance) {
        return new PlanCursor(
                List.of(new Frame(List.of(), 0, "", "", false)), Map.of(), Map.of(), tick, distance, "", null);
    }

    public record Frame(List<Integer> route, int nextIndex, String pathPrefix, String ownerPath, boolean repeatBody) {
        public Frame {
            route = List.copyOf(route == null ? List.of() : route);
            pathPrefix = pathPrefix == null ? "" : pathPrefix;
            ownerPath = ownerPath == null ? "" : ownerPath;
            if (nextIndex < 0 || route.size() % 2 != 0) throw new IllegalArgumentException("invalid frame");
        }
    }
}
