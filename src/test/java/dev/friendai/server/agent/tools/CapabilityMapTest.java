package dev.friendai.server.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CapabilityMapTest {
    private static final Pattern TOOL_CARD = Pattern.compile("\"([a-z][a-z0-9_]+)\\|([^\"\\r\\n]+)\"");
    private static final Pattern CATEGORY = Pattern.compile("\\{ id: \"([a-z][a-z0-9_]*)\", domain:");
    private static final Pattern SOURCE = Pattern.compile("\"((?:engine/|src/|scripts/|\\.github/)[^\"\\r\\n]+)\"");
    private static final Set<String> CROSS_LISTED = Set.of();

    @Test
    void cardsMatchTheProductionCatalogAndHaveRussianDescriptions() throws IOException {
        String app = Files.readString(Path.of("site", "app.js"));
        String categories = section(app, "const CATS = [", "/* ================= CODE LOCATIONS");
        String translation = Files.readString(Path.of("site", "i18n.ru.js"));
        var catalog = FriendaiTools.build(() -> 8, MemoryAccess.DISABLED).catalog();
        Set<String> expected =
                catalog.all().stream().map(ToolMeta::name).collect(Collectors.toCollection(TreeSet::new));
        List<String> cards = TOOL_CARD
                .matcher(categories)
                .results()
                .map(match -> match.group(1))
                .toList();
        Set<String> actual = new TreeSet<>(cards);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> unknown = new TreeSet<>(actual);
        unknown.removeAll(expected);
        assertTrue(missing.isEmpty() && unknown.isEmpty(), "missing: " + missing + ", unknown: " + unknown);
        assertEquals(
                CROSS_LISTED,
                actual.stream()
                        .filter(name -> Collections.frequency(cards, name) > 1)
                        .collect(Collectors.toSet()),
                "cross-listed tools must be documented in this test");
        TOOL_CARD
                .matcher(categories)
                .results()
                .forEach(card -> assertTrue(
                        translation.contains(JsonParser.parseString("\"" + card.group(2) + "\"")
                                        .toString() + ":"),
                        "missing Russian description for " + card.group(1)));
        List<String> ids = CATEGORY.matcher(categories)
                .results()
                .map(match -> match.group(1))
                .toList();
        assertTrue(!ids.isEmpty() && ids.size() == new HashSet<>(ids).size(), "category IDs must be unique");
        String sourceMap = section(app, "const CODE = {", "const FLOW = {");
        for (String tool : actual) {
            assertTrue(sourceMap.contains("\"" + tool + "\": ["), "missing source links for " + tool);
        }
    }

    @Test
    void linkedSourceFilesExist() throws IOException {
        String app = Files.readString(Path.of("site", "app.js"));
        var paths = SOURCE.matcher(app)
                .results()
                .map(match -> match.group(1))
                .distinct()
                .toList();
        assertTrue(paths.size() > 100, "the map should link to implementation files");
        List<String> missing =
                paths.stream().filter(path -> !Files.exists(Path.of(path))).toList();
        assertTrue(missing.isEmpty(), "missing source links: " + missing);
    }

    private static String section(String text, String start, String end) {
        int first = text.indexOf(start);
        int last = first < 0 ? -1 : text.indexOf(end, first);
        assertTrue(first >= 0 && last > first, "missing section " + start);
        return text.substring(first, last);
    }
}
