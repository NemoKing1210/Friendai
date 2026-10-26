package dev.friendai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Pins the lines of the system prompt per role and turn kind. The comparison ignores line order (sections may
 * move) but not line content. Regenerate with {@code -Dfriendai.golden.write=true} after reviewing the diff.
 */
class PromptGoldenTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Persona PERSONA = new Persona("Frida", "Cheerful helper", "auto");
    private static final String PERCEPTION = "Position: 1 2 3";
    private static final String MEMORY = "Memory:\n- Steve likes oak";

    private static Map<String, String> cases() {
        Map<String, String> cases = new LinkedHashMap<>();
        for (AccessLevel level : AccessLevel.values()) {
            String who = level.name().toLowerCase();
            Caller caller = new Caller(ID, "Steve", level);
            cases.put(who + "_player", PromptBuilder.system(PERSONA, PERCEPTION, caller, "", TurnKind.PLAYER));
            cases.put(who + "_event", PromptBuilder.system(PERSONA, PERCEPTION, caller, "", TurnKind.EVENT));
        }
        cases.put("owner_memory", PromptBuilder.system(
                PERSONA, PERCEPTION, new Caller(ID, "Steve", AccessLevel.OWNER), MEMORY, TurnKind.PLAYER));
        cases.put("guest_russian", PromptBuilder.system(
                new Persona("Frida", "Cheerful helper", "Russian"), PERCEPTION,
                new Caller(ID, "Gus", AccessLevel.GUEST), "", TurnKind.PLAYER));
        return cases;
    }

    private static List<String> sortedLines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        Collections.sort(lines);
        return lines;
    }

    @TestFactory
    List<DynamicTest> promptMatchesGolden() {
        boolean write = Boolean.getBoolean("friendai.golden.write");
        List<DynamicTest> tests = new ArrayList<>();
        for (Map.Entry<String, String> entry : cases().entrySet()) {
            String name = entry.getKey();
            String prompt = entry.getValue();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                if (write) {
                    Path dir = Path.of("src/test/resources/prompt-golden");
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve(name + ".txt"), prompt, StandardCharsets.UTF_8);
                    return;
                }
                String golden = read(name);
                assertEquals(sortedLines(golden), sortedLines(prompt), "prompt case " + name + " changed");
            }));
        }
        return tests;
    }

    private static String read(String name) throws IOException {
        try (InputStream in = PromptGoldenTest.class.getResourceAsStream("/prompt-golden/" + name + ".txt")) {
            if (in == null) {
                throw new IOException("missing golden " + name + "; run with -Dfriendai.golden.write=true");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
