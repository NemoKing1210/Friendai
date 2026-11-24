package dev.friendai.server.execution.persistence;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.queue.QueueSave;
import dev.friendai.server.execution.RetryCheckpoints;
import dev.friendai.task.TaskState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        root.addProperty("version", SavedExecution.CURRENT_VERSION);
        root.add("queue", QueueSave.encode(saved.queue().queue(), saved.queue().activeJobId()));
        if (saved.manualPause()) {
            root.addProperty("manualPause", true);
        }
        if (saved.activeElapsedTicks() > 0) {
            root.addProperty("activeElapsedTicks", saved.activeElapsedTicks());
        }
        if (saved.activeTimeoutSec() > 0) {
            root.addProperty("activeTimeoutSec", saved.activeTimeoutSec());
        }
        if (saved.activeTask() != null) {
            JsonObject task = new JsonObject();
            task.addProperty("type", saved.activeTask().type());
            task.addProperty("data", saved.activeTask().data());
            root.add("activeTask", task);
        }
        if (!saved.retryProgress().isEmpty()) {
            JsonArray retries = new JsonArray();
            saved.retryProgress().forEach((jobId, entry) -> {
                JsonObject row = new JsonObject();
                row.addProperty("job", jobId);
                row.addProperty("args", entry.args());
                if (entry.state() != null) {
                    row.addProperty("type", entry.state().type());
                    row.addProperty("data", entry.state().data());
                }
                retries.add(row);
            });
            root.add("retries", retries);
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
            int version = root.get("version").getAsInt();
            // An older document predates the pause, the retry progress or the active clock, so it loads with defaults.
            if (version < SavedExecution.LEGACY_VERSION || version > SavedExecution.CURRENT_VERSION)
                return Optional.empty();
            var queue = QueueSave.decode(root.getAsJsonObject("queue"), limits);
            if (queue.isEmpty()) return Optional.empty();
            boolean manualPause =
                    root.has("manualPause") && root.get("manualPause").getAsBoolean();
            TaskState activeTask = null;
            if (root.has("activeTask")) {
                JsonObject task = root.getAsJsonObject("activeTask");
                String type = task.get("type").getAsString();
                String data = task.get("data").getAsString();
                if (data.length() > MAX_TASK_STATE_CHARS) return Optional.empty();
                activeTask = new TaskState(type, data);
            }
            Map<Long, RetryCheckpoints.Entry> retryProgress = readRetries(root);
            if (retryProgress == null) return Optional.empty();
            int activeElapsedTicks = 0;
            if (root.has("activeElapsedTicks")) {
                activeElapsedTicks = root.get("activeElapsedTicks").getAsInt();
                if (activeElapsedTicks < 0 || activeElapsedTicks > SavedExecution.MAX_ACTIVE_ELAPSED_TICKS) {
                    return Optional.empty();
                }
            }
            int activeTimeoutSec = 0;
            if (root.has("activeTimeoutSec")) {
                activeTimeoutSec = root.get("activeTimeoutSec").getAsInt();
                if (activeTimeoutSec < 0 || activeTimeoutSec > SavedExecution.MAX_ACTIVE_TIMEOUT_SEC) {
                    return Optional.empty();
                }
            }
            return Optional.of(new SavedExecution(
                    SavedExecution.CURRENT_VERSION,
                    queue.get(),
                    activeTask,
                    manualPause,
                    retryProgress,
                    activeElapsedTicks,
                    activeTimeoutSec));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /** The saved retry progress, or null when the document carries more of it than a save may hold. */
    private static Map<Long, RetryCheckpoints.Entry> readRetries(JsonObject root) {
        if (!root.has("retries")) return Map.of();
        JsonArray rows = root.getAsJsonArray("retries");
        if (rows == null || rows.size() > RetryCheckpoints.MAX_ENTRIES) return null;
        Map<Long, RetryCheckpoints.Entry> progress = new LinkedHashMap<>();
        for (var element : rows) {
            JsonObject row = element.getAsJsonObject();
            long jobId = row.get("job").getAsLong();
            if (jobId <= 0) continue;
            String args = row.get("args").getAsString();
            if (args.length() > RetryCheckpoints.MAX_ARGS_CHARS) return null;
            TaskState state = null;
            if (row.has("type") && row.has("data")) {
                String type = row.get("type").getAsString();
                String data = row.get("data").getAsString();
                if (data.length() > MAX_TASK_STATE_CHARS) return null;
                state = new TaskState(type, data);
            }
            progress.put(jobId, new RetryCheckpoints.Entry(args, state));
        }
        return progress;
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
