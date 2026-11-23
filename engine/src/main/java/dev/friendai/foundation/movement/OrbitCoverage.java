package dev.friendai.foundation.movement;

/**
 * One orbit's waypoints and what actually happened at each: reaching a point and bypassing it around an obstacle are
 * both verified coverage, while a point the companion could not get to is blocked. Attempts are not coverage, so a
 * skipped waypoint can never finish a lap.
 */
public final class OrbitCoverage {
    public enum State {
        PENDING,
        REACHED,
        BYPASSED,
        BLOCKED
    }

    private final State[] states;

    public OrbitCoverage(int waypoints) {
        if (waypoints < 1) throw new IllegalArgumentException("an orbit needs at least one waypoint");
        this.states = new State[waypoints];
        java.util.Arrays.fill(states, State.PENDING);
    }

    public int total() {
        return states.length;
    }

    public State state(int index) {
        return states[index];
    }

    /**
     * Records one waypoint's final state. A waypoint is resolved once; a second answer for it is refused so a retry
     * cannot inflate the count.
     */
    public boolean resolve(int index, State state) {
        if (state == null || state == State.PENDING) {
            throw new IllegalArgumentException("a resolved waypoint needs a terminal state");
        }
        if (index < 0 || index >= states.length) return false;
        if (states[index] != State.PENDING) return false;
        states[index] = state;
        return true;
    }

    /** Waypoints the companion provably covered, by walking to them or around them. */
    public int verified() {
        int verified = 0;
        for (State state : states) {
            if (state == State.REACHED || state == State.BYPASSED) verified++;
        }
        return verified;
    }

    public int reached() {
        return count(State.REACHED);
    }

    public int bypassed() {
        return count(State.BYPASSED);
    }

    public int blocked() {
        return count(State.BLOCKED);
    }

    public int remaining() {
        return count(State.PENDING);
    }

    public boolean complete() {
        return remaining() == 0;
    }

    private int count(State wanted) {
        int count = 0;
        for (State state : states) {
            if (state == wanted) count++;
        }
        return count;
    }
}
