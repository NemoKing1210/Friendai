package dev.friendai.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The runtime health view is only honest when it names every persistent store the service graph constructs. The graph
 * itself needs a Minecraft server, so this test reads its source: a new store type, a forgotten source or a duplicated
 * one fails here instead of leaving a silent blind spot in diagnostics.
 */
class StoreHealthInventoryTest {
    private static final Path GRAPH = Path.of("")
            .toAbsolutePath()
            .normalize()
            .resolve("src/main/java/dev/friendai/server/runtime/ServerServiceGraph.java");
    private static final Pattern CONSTRUCTED = Pattern.compile("new\\s+(?:[\\w.]+\\.)?(\\w+Store)\\s*\\(");
    private static final Pattern CONSTRUCTED_SERVICE = Pattern.compile("new\\s+(?:[\\w.]+\\.)?(SkillService)\\s*\\(");
    private static final Pattern REPORTED = Pattern.compile("sources\\.put\\(\"([^\"]+)\"");

    /** Every store type the graph builds that reports a write status, and the source names it appears under. */
    private static final Map<String, List<String>> TRACKED = Map.ofEntries(
            Map.entry("MemoryStore", List.of("memory")),
            Map.entry("TranscriptStore", List.of("transcript")),
            Map.entry("CompanionProfileStore", List.of("profiles")),
            Map.entry("GoalStore", List.of("goals")),
            Map.entry("FreeTimeStore", List.of("free-time")),
            Map.entry("RoutineStore", List.of("routines")),
            Map.entry("SocialMemoryStore", List.of("social")),
            Map.entry("TerrainStore", List.of("terrain")),
            Map.entry("KnowledgeStore", List.of("knowledge")),
            Map.entry("PlaceStore", List.of("places")),
            Map.entry("PlacedStore", List.of("placed")),
            Map.entry("TradeStore", List.of("trading")),
            Map.entry("SkillService", List.of("skills", "skills-server")));

    /**
     * Persistent stores the graph builds that cannot report a store status, each with why. Appearance is the one this
     * list exists for: {@code SkinStore} writes synchronously and reports a failure by throwing to the upload caller.
     */
    private static final Map<String, String> NOT_STATUS_TRACKED = Map.of(
            "SkinStore", "synchronous PNG write that throws to the upload caller; no typed status exists",
            "SearchHintStore", "in-memory search hints, no file and nothing to write");

    @Test
    void everyStoreTheGraphBuildsIsReportedExactlyOnce() throws IOException {
        String source = Files.readString(GRAPH);
        assertTrue(source.contains("storeStatuses()"), "the inventory reads the runtime's health source");

        List<String> constructed = constructedTypes(source);
        assertTrue(constructed.size() >= 10, "the graph source was read: " + constructed);

        Map<String, Integer> expected = new TreeMap<>();
        for (String type : constructed) {
            List<String> names = TRACKED.get(type);
            if (names == null) {
                assertTrue(
                        NOT_STATUS_TRACKED.containsKey(type),
                        "a new store type needs a status source or a documented reason: " + type);
                continue;
            }
            for (String name : names) {
                expected.merge(name, 1, Integer::sum);
            }
        }

        Map<String, Integer> reported = new TreeMap<>();
        Matcher matcher = REPORTED.matcher(source);
        while (matcher.find()) {
            reported.merge(matcher.group(1), 1, Integer::sum);
        }

        assertEquals(expected, reported, "every tracked store appears once under its source name");
        assertTrue(reported.values().stream().allMatch(count -> count == 1), "no source is reported twice");
    }

    private static List<String> constructedTypes(String source) {
        List<String> types = new ArrayList<>();
        List<Pattern> patterns = List.of(CONSTRUCTED, CONSTRUCTED_SERVICE);
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) {
                types.add(matcher.group(1));
            }
        }
        return types;
    }
}
