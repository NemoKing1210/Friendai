package dev.friendai.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.brain.Persona;
import dev.friendai.brain.TurnKind;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultSectionsPlacedTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Persona PERSONA = new Persona("Frida", "Cheerful helper", "auto");
    private static final String BLOCK =
            "Blocks you placed that are still in the world (nearest first):\n- 3 minecraft:oak_log: nearest 4 blocks west at 10 64 3 (place_block)";

    private static TurnContext ctx(AccessLevel level, String placed) {
        String name = level == AccessLevel.GUEST ? "Gus" : "Steve";
        return new TurnContext(
                PERSONA, "Position: 1 2 3", new Caller(ID, name, level), "", TurnKind.PLAYER, "",
                List.of(), "", placed, "", List.of());
    }

    @Test
    void ownerAndTrustedSeeTheBlock() {
        assertTrue(DefaultSections.placed().render(ctx(AccessLevel.OWNER, BLOCK)).orElseThrow().contains("oak_log"));
        assertTrue(DefaultSections.placed().render(ctx(AccessLevel.TRUSTED, BLOCK)).orElseThrow().contains("oak_log"));
    }

    @Test
    void guestSeesNothing() {
        assertEquals(Optional.empty(), DefaultSections.placed().render(ctx(AccessLevel.GUEST, BLOCK)));
    }

    @Test
    void blankBlockRendersNothing() {
        assertEquals(Optional.empty(), DefaultSections.placed().render(ctx(AccessLevel.OWNER, "")));
        assertEquals(Optional.empty(), DefaultSections.placed().render(ctx(AccessLevel.OWNER, "  ")));
    }

    @Test
    void sectionSitsBetweenMissionAndPlaces() {
        assertEquals("placed", DefaultSections.placed().id());
        assertEquals(75, DefaultSections.placed().priority());
        List<String> ids = DefaultSections.all().stream().map(PromptSection::id).toList();
        assertTrue(ids.indexOf("mission") < ids.indexOf("placed"), ids.toString());
        assertTrue(ids.indexOf("placed") < ids.indexOf("places"), ids.toString());
    }

    @Test
    void assembledGuestPromptHasNoTraceOfTheBlock() {
        String prompt = PromptAssembler.defaults(0).assemble(ctx(AccessLevel.GUEST, BLOCK));
        assertFalse(prompt.contains("nearest 4 blocks west"), prompt);
        assertFalse(prompt.contains("Blocks you placed"), prompt);
    }
}
