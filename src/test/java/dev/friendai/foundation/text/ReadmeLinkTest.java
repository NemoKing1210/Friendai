package dev.friendai.foundation.text;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Keeps the landing page and its translations honest: every local link resolves and every file has the switcher. */
class ReadmeLinkTest {
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)]+)\\)");
    private static final List<String> READMES = List.of(
            "README.md",
            "README.ru.md",
            "README.uk.md",
            "README.de.md",
            "README.fr.md",
            "README.es.md",
            "README.pt-BR.md",
            "README.zh-CN.md");

    @Test
    void everyLocalLinkResolves() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String readme : READMES) {
            Path file = Path.of(readme);
            assertTrue(Files.isRegularFile(file), readme);
            Matcher matcher = LINK.matcher(Files.readString(file));
            while (matcher.find()) {
                String target = matcher.group(1).strip();
                if (target.startsWith("http") || target.startsWith("mailto:") || target.startsWith("#")) {
                    continue;
                }
                int hash = target.indexOf('#');
                String path = hash >= 0 ? target.substring(0, hash) : target;
                if (!Files.exists(Path.of(path))) {
                    missing.add(readme + " -> " + target);
                }
            }
        }
        assertTrue(missing.isEmpty(), "Missing README targets: " + missing);
    }

    @Test
    void everyReadmeOffersTheLanguageSwitcher() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String readme : READMES) {
            String text = Files.readString(Path.of(readme));
            for (String other : READMES) {
                if (!other.equals(readme) && !text.contains("(" + other + ")")) {
                    missing.add(readme + " lacks " + other);
                }
            }
        }
        assertTrue(missing.isEmpty(), "Incomplete language switcher: " + missing);
    }
}
