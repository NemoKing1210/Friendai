package dev.friendai.task;

/** A bounded checkpoint payload identified by a stable task or tool key, never by a Java class name. */
public record TaskState(String type, String data) {
    public TaskState {
        if (type == null || type.isBlank() || type.length() > 128)
            throw new IllegalArgumentException("invalid task type");
        data = data == null ? "" : data;
        if (data.length() > 8_192) throw new IllegalArgumentException("task state exceeds size limit");
    }
}
