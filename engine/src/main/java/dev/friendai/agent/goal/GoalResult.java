package dev.friendai.agent.goal;

/** A value or a message the model can read; goal operations never throw for bad input. */
public record GoalResult<T>(T value, String error) {
    public static <T> GoalResult<T> ok(T value) {
        return new GoalResult<>(value, null);
    }

    public static <T> GoalResult<T> fail(String error) {
        return new GoalResult<>(null, error);
    }

    public boolean ok() {
        return error == null;
    }
}
