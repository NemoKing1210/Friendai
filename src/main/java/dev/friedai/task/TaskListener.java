package dev.friedai.task;

public interface TaskListener {
    void onFinished(int id, String toolName, TaskStatus status, String summary);

    void onCancelled(int id, String toolName, String reason);
}
