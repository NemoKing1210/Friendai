package dev.friendai.features.navigation.pathing;

/** The outcome of one search call. {@code path} is null unless the status is {@code FOUND} or {@code PARTIAL}. */
public record SearchResult(Status status, Path path) {

    public enum Status {
        /** The search needs more time; call {@link PathSearch#step} again. */
        RUNNING,
        FOUND,
        /** A limit was hit; the path gets closer to the goal than the start did. */
        PARTIAL,
        NO_PATH
    }

    public static SearchResult running() {
        return new SearchResult(Status.RUNNING, null);
    }
}
