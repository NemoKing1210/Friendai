package dev.friendai.features.trading;

/**
 * The page a trader list is showing. Turning a page keeps the page that was asked for, while a new filter starts at the
 * first page again — the two are different actions, so they are different calls.
 */
public final class TradePager {
    private int page;

    public int page() {
        return page;
    }

    /** A changed filter or direction is a new query, so it starts again at the first page. */
    public void filterChanged() {
        page = 0;
    }

    /** Moves by {@code delta} inside {@code totalPages}; an empty result keeps page zero. */
    public void turn(int delta, int totalPages) {
        int last = Math.max(0, totalPages - 1);
        page = Math.max(0, Math.min(last, page + delta));
    }

    public void reset() {
        page = 0;
    }
}
