package dev.friendai.server.execution.persistence;

import java.util.List;

/** Secret-free summary of work restored or rejected from one saved execution. */
public record RestoreReport(List<String> restored, List<String> rejected) {
    public RestoreReport {
        restored = List.copyOf(restored == null ? List.of() : restored);
        rejected = List.copyOf(rejected == null ? List.of() : rejected);
    }

    public static RestoreReport empty() {
        return new RestoreReport(List.of(), List.of());
    }
}
