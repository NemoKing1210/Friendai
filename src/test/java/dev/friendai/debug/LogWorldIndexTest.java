package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogWorldIndexTest {
    @Test
    void oneLinePerWorldEvent(@TempDir Path gameRoot) throws IOException {
        Path worldPath = gameRoot.resolve("saves").resolve("New World");
        LogWorldIndex.append(gameRoot, "world.load", "New World", worldPath, "7f3c", "0.203.0", "26.3", 1L);
        LogWorldIndex.append(gameRoot, "world.close", "New World", worldPath, "7f3c", "0.203.0", "26.3", 2L);

        List<String> lines = Files.readAllLines(gameRoot.resolve("index.jsonl"));
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"event\":\"world.load\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"world\":\"New World\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"boot\":\"7f3c\""), lines.get(0));
        assertTrue(lines.get(1).contains("\"event\":\"world.close\""), lines.get(1));
        assertTrue(
                lines.get(0).contains(pathJson(worldPath)),
                "the entries survive a deleted world through their path: " + lines.get(0));
    }

    @Test
    void aFailedWriteIsSwallowed(@TempDir Path temp) throws IOException {
        Path notADirectory = temp.resolve("index.jsonl");
        Files.writeString(notADirectory, "occupied");

        assertDoesNotThrow(
                () -> LogWorldIndex.append(notADirectory, "world.load", "W", temp, "7f3c", "0.203.0", "26.3", 1L));
        assertEquals("occupied", Files.readString(notADirectory));
    }

    private static String pathJson(Path path) {
        String absolute = path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
        return "\"path\":\"" + absolute + "\"";
    }
}
