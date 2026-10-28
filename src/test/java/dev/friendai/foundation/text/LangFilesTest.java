package dev.friendai.foundation.text;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LangFilesTest {
    private static final List<Path> LANG_DIRS = List.of(
            Path.of("engine/src/main/resources/assets/friendai/lang"),
            Path.of("src/main/resources/assets/friendai/lang"));
    private static final List<Path> SOURCE_ROOTS =
            List.of(Path.of("engine/src/main/java"), Path.of("src/main/java"), Path.of("src/client/java"));
    private static final Pattern PLACEHOLDER = Pattern.compile("%[sd%]");

    private static JsonObject read(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    private static List<Path> langFiles() throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path langDir : LANG_DIRS) {
            if (Files.isDirectory(langDir)) {
                try (Stream<Path> files = Files.list(langDir)) {
                    result.addAll(files.filter(path -> path.toString().endsWith(".json"))
                            .toList());
                }
            }
        }
        return result.stream().sorted().toList();
    }

    private static JsonObject language(String locale) throws IOException {
        JsonObject merged = new JsonObject();
        for (Path langDir : LANG_DIRS) {
            Path file = langDir.resolve(locale + ".json");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            JsonObject catalog = read(file);
            for (String key : catalog.keySet()) {
                if (merged.has(key)) {
                    throw new IOException("Duplicate language key " + key + " in " + file);
                }
                merged.add(key, catalog.get(key));
            }
        }
        return merged;
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
        TreeSet<String> expected = new TreeSet<>(language("en_us").keySet());
        Set<String> locales = new TreeSet<>();
        for (Path file : langFiles()) {
            String filename = file.getFileName().toString();
            locales.add(filename.substring(0, filename.length() - ".json".length()));
        }
        for (String locale : locales) {
            assertEquals(expected, new TreeSet<>(language(locale).keySet()), locale);
        }
    }

    @Test
    void noEmptyValues() throws IOException {
        for (Path p : langFiles()) {
            read(p).asMap().forEach((k, v) -> assertFalse(v.getAsString().isBlank(), p.getFileName() + ": " + k));
        }
    }

    @Test
    void placeholdersMatch() throws IOException {
        JsonObject en = language("en_us");
        JsonObject ru = language("ru_ru");
        for (String key : en.keySet()) {
            assertEquals(count(en.get(key).getAsString()), count(ru.get(key).getAsString()), key);
        }
    }

    @Test
    void everyKeyUsedInCodeExists() throws IOException {
        JsonObject en = language("en_us");
        Pattern keyPattern = Pattern.compile(
                "\"((?:friendai|key\\.friendai|key\\.category\\.friendai|entity\\.friendai)\\.[a-z0-9_.]+)\"");
        List<String> missing = new ArrayList<>();
        for (Path dir : SOURCE_ROOTS) {
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
