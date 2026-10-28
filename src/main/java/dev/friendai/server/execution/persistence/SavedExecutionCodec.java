package dev.friendai.server.execution.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.queue.QueueSave;
import dev.friendai.task.TaskState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Bounded codec for the single target-format execution document stored by a companion. */
public final class SavedExecutionCodec {
    public static final int MAX_SAVE_BYTES = 1_000_000;
    public static final int CHUNK_CHARS = 16_000;
    public static final int MAX_PARTS = 64;
    private static final int MAX_TASK_STATE_CHARS = 8_192;

    private SavedExecutionCodec() {}

    public static String encode(SavedExecution saved) {
        JsonObject root = new JsonObject();
        root.addProperty("version", saved.version());
        root.add("queue", QueueSave.encode(saved.queue().queue(), saved.queue().activeJobId()));
        if (saved.activeTask() != null) {
            JsonObject task = new JsonObject();
            task.addProperty("type", saved.activeTask().type());
            task.addProperty("data", saved.activeTask().data());
            root.add("activeTask", task);
        }
        String encoded = root.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_SAVE_BYTES)
            throw new IllegalStateException("execution save exceeds size limit");
        return encoded;
    }

    public static Optional<SavedExecution> decode(String encoded, QueueLimits limits) {
        if (encoded == null
                || encoded.length() > MAX_SAVE_BYTES
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_SAVE_BYTES) return Optional.empty();
        try {
            JsonObject root = JsonParser.parseString(encoded).getAsJsonObject();
            if (root.get("version").getAsInt() != SavedExecution.CURRENT_VERSION) return Optional.empty();
            var queue = QueueSave.decode(root.getAsJsonObject("queue"), limits);
            if (queue.isEmpty()) return Optional.empty();
            TaskState activeTask = null;
            if (root.has("activeTask")) {
                JsonObject task = root.getAsJsonObject("activeTask");
                String type = task.get("type").getAsString();
                String data = task.get("data").getAsString();
                if (data.length() > MAX_TASK_STATE_CHARS) return Optional.empty();
                activeTask = new TaskState(type, data);
            }
            return Optional.of(new SavedExecution(SavedExecution.CURRENT_VERSION, queue.get(), activeTask));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    public static List<String> split(String encoded) {
        if (encoded == null || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_SAVE_BYTES)
            throw new IllegalArgumentException("execution save exceeds size limit");
        List<String> parts = new ArrayList<>();
        for (int at = 0; at < encoded.length(); ) {
            int end = Math.min(encoded.length(), at + CHUNK_CHARS);
            if (end < encoded.length()
                    && Character.isHighSurrogate(encoded.charAt(end - 1))
                    && Character.isLowSurrogate(encoded.charAt(end))) end--;
            parts.add(encoded.substring(at, end));
            at = end;
        }
        if (parts.size() > MAX_PARTS) throw new IllegalArgumentException("execution save has too many parts");
        return List.copyOf(parts);
    }

    public static Optional<String> join(List<String> parts) {
        if (parts == null || parts.isEmpty() || parts.size() > MAX_PARTS) return Optional.empty();
        long chars = 0;
        for (String part : parts) {
            if (part == null || part.length() > CHUNK_CHARS) return Optional.empty();
            chars += part.length();
            if (chars > MAX_SAVE_BYTES) return Optional.empty();
        }
        String encoded = String.join("", parts);
        return encoded.getBytes(StandardCharsets.UTF_8).length <= MAX_SAVE_BYTES
                ? Optional.of(encoded)
                : Optional.empty();
    }
}
