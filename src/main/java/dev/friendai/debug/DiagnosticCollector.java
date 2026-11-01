package dev.friendai.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.memory.transcript.TranscriptEntry;
import dev.friendai.foundation.diagnostics.DebugLog;
import dev.friendai.foundation.diagnostics.DiagnosticRedaction;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.server.runtime.ServerRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/** Collects the bounded, redacted facts a maintainer needs from one owner's companion. */
public final class DiagnosticCollector {
    private static final int LOG_LIMIT = 200;
    private static final int CHAT_LIMIT = 200;
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private DiagnosticCollector() {}

    public static Map<String, String> collect(
            ServerRuntime runtime, ServerPlayer owner, boolean includeChat, UnaryOperator<String> redactor) {
        Map<String, String> files = new LinkedHashMap<>();
        ServerConfig config = runtime.config();
        CompanionEntity companion = runtime.companions().find(owner.getUUID()).orElse(null);
        List<LogEntry> logs = runtime.sessions()
                .debug()
                .logOf(owner.getUUID())
                .query(new DebugLog.Query(Set.of(), 0L, 0L, LOG_LIMIT, true));

        StringBuilder summary = new StringBuilder();
        summary.append("friendai diagnostic bundle\n");
        summary.append("created: ").append(Instant.now()).append('\n');
        summary.append("subject: ")
                .append(owner.getName().getString())
                .append(" (")
                .append(owner.getUUID())
                .append(")\n");
        summary.append("mod: ").append(version("friendai")).append('\n');
        summary.append("minecraft: ").append(version("minecraft")).append('\n');
        summary.append("fabric loader: ").append(version("fabricloader")).append('\n');
        summary.append("java: ").append(System.getProperty("java.version")).append('\n');
        summary.append("active profile: ").append(config.activeProfile).append('\n');
        ServerConfig.ProfileConfig profile = config.profiles.get(config.activeProfile);
        if (profile != null) {
            summary.append("provider: ")
                    .append(profile.provider)
                    .append(", model: ")
                    .append(profile.model)
                    .append('\n');
        }
        summary.append("profiles: ").append(config.profiles.keySet()).append('\n');
        if (companion == null) {
            summary.append("companion: absent\n");
        } else {
            summary.append("companion: ")
                    .append(companion.getName().getString())
                    .append(" at ")
                    .append(companion.blockPosition())
                    .append(" in ")
                    .append(companion.level().dimension().identifier())
                    .append(", health ")
                    .append(companion.getHealth())
                    .append(", food ")
                    .append(companion.syncedFood())
                    .append('\n');
            summary.append("queue: ")
                    .append(companion.executionCoordinator().queueSummary())
                    .append('\n');
            summary.append("active task: ")
                    .append(companion.executionCoordinator().currentToolName())
                    .append('\n');
            summary.append("places: ")
                    .append(runtime.placeService().book(companion).all().size())
                    .append('\n');
        }
        summary.append("memory: ")
                .append(runtime.memory().book(owner.getUUID()).allFacts().size())
                .append(" facts, ")
                .append(runtime.memory().book(owner.getUUID()).players().size())
                .append(" players\n");
        summary.append("skills: ")
                .append(runtime.skills().listed(owner.getUUID()).size())
                .append('\n');
        summary.append("tools: ").append(runtime.sessions().toolNames().size()).append('\n');
        summary.append("included chat: ").append(includeChat).append('\n');
        summary.append("last provider error: ").append(lastProviderError(logs)).append('\n');
        files.put("summary.txt", summary.toString());

        String configJson = PRETTY.toJson(JsonParser.parseString(new Gson().toJson(config)));
        files.put("config.json", PRETTY.toJson(DiagnosticRedaction.maskConfig(JsonParser.parseString(configJson))));

        if (companion != null) {
            String queue = companion.executionCoordinator().queueSummary() + "\n"
                    + companion.executionCoordinator().queueRows().stream()
                            .map(row -> row.toString())
                            .collect(Collectors.joining("\n"));
            files.put("queue.txt", queue);
        }
        files.put(
                "tasks.json",
                PRETTY.toJson(
                        runtime.sessions().debug().historyOf(owner.getUUID()).recent()));
        files.put(
                "log.txt",
                logs.stream()
                        .map(entry -> "[" + entry.id() + "] " + entry.kind() + " " + entry.text())
                        .collect(Collectors.joining("\n")));
        files.put("tools.txt", String.join("\n", runtime.sessions().toolNames()));
        if (includeChat) {
            files.put("chat.txt", chat(runtime, owner));
        }
        if (redactor != null) {
            files.replaceAll((name, text) -> redactor.apply(text));
        }
        return files;
    }

    private static String version(String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static String lastProviderError(List<LogEntry> logs) {
        for (LogEntry entry : logs) {
            if (entry.text().contains("LLM request failed")) {
                return entry.text();
            }
        }
        return "none";
    }

    private static String chat(ServerRuntime runtime, ServerPlayer owner) {
        List<TranscriptEntry> entries =
                runtime.transcripts().book(owner.getUUID()).all(CHAT_LIMIT);
        return entries.stream()
                .filter(entry -> owner.getUUID().equals(entry.playerUuid()))
                .map(entry -> entry.atMillis() + " " + entry.speaker() + ": " + entry.text())
                .collect(Collectors.joining("\n"));
    }
}
