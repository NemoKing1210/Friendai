package dev.friendai.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LangFilesTest {
    private static final Path LANG_DIR = Path.of("src/main/resources/assets/friendai/lang");
    private static final Pattern PLACEHOLDER = Pattern.compile("%[sd%]");

    private static JsonObject read(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    private static List<Path> langFiles() throws IOException {
        try (Stream<Path> s = Files.list(LANG_DIR)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static int count(String s) {
        Matcher m = PLACEHOLDER.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void sameKeysInAllLanguages() throws IOException {
        TreeSet<String> expected = new TreeSet<>(read(LANG_DIR.resolve("en_us.json")).keySet());
        for (Path p : langFiles()) {
            assertEquals(expected, new TreeSet<>(read(p).keySet()), p.getFileName().toString());
        }
    }

    @Test
    void noEmptyValues() throws IOException {
        for (Path p : langFiles()) {
            read(p).asMap().forEach((k, v) ->
                    assertFalse(v.getAsString().isBlank(), p.getFileName() + ": " + k));
        }
    }

    @Test
    void placeholdersMatch() throws IOException {
        JsonObject en = read(LANG_DIR.resolve("en_us.json"));
        JsonObject ru = read(LANG_DIR.resolve("ru_ru.json"));
        for (String key : en.keySet()) {
            assertEquals(count(en.get(key).getAsString()), count(ru.get(key).getAsString()), key);
        }
    }

    @Test
    void everyKeyUsedInCodeExists() throws IOException {
        JsonObject en = read(LANG_DIR.resolve("en_us.json"));
        Pattern keyPattern = Pattern.compile(
                "\"((?:friendai|key\\.friendai|key\\.category\\.friendai|entity\\.friendai)\\.[a-z0-9_.]+)\"");
        List<String> missing = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/client/java")) {
            Path dir = Path.of(root);
            if (!Files.exists(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                    Matcher m = keyPattern.matcher(Files.readString(p));
                    while (m.find()) {
                        if (!en.has(m.group(1))) {
                            missing.add(p + ": " + m.group(1));
                        }
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "Missing translation keys: " + missing);
    }
}
