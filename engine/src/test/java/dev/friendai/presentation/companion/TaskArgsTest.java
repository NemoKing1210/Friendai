package dev.friendai.presentation.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.presentation.companion.TaskArgs.Chip;
import dev.friendai.presentation.companion.TaskArgs.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskArgsTest {
    @Test
    void coordinatesCollapseIntoOnePositionChip() {
        List<Chip> chips = TaskArgs.parse("{\"x\":7,\"y\":70,\"z\":-9}");
        assertEquals(List.of(new Chip(Kind.POS, "", "7 70 -9")), chips);
    }

    @Test
    void blockCountAndPositionAreOrderedResourceFirst() {
        List<Chip> chips = TaskArgs.parse("{\"count\":24,\"block\":\"minecraft:stone\",\"x\":1,\"y\":2,\"z\":3}");
        assertEquals(
                List.of(
                        new Chip(Kind.ITEM, "", "minecraft:stone"),
                        new Chip(Kind.POS, "", "1 2 3"),
                        new Chip(Kind.COUNT, "", "24")),
                chips);
    }

    @Test
    void bareIdGetsTheDefaultNamespace() {
        assertEquals(
                "minecraft:oak_log",
                TaskArgs.parse("{\"item\":\"oak_log\"}").getFirst().itemId());
    }

    @Test
    void partialCoordinatesStayPlainText() {
        List<Chip> chips = TaskArgs.parse("{\"x\":4,\"z\":5}");
        assertTrue(chips.stream().allMatch(c -> c.kind() == Kind.TEXT));
        assertEquals(2, chips.size());
    }

    @Test
    void sentenceInAResourceFieldIsTextNotAnIcon() {
        Chip chip = TaskArgs.parse("{\"target\":\"the nearest cow\"}").getFirst();
        assertEquals(Kind.TEXT, chip.kind());
        assertEquals("the nearest cow", chip.text());
    }

    @Test
    void arrayOfIdsBecomesOneChipEach() {
        assertEquals(
                2, TaskArgs.parse("{\"items\":[\"dirt\",\"minecraft:sand\"]}").size());
    }

    @Test
    void emptyBlankAndBrokenArgumentsGiveNoChips() {
        assertTrue(TaskArgs.parse("{}").isEmpty());
        assertTrue(TaskArgs.parse("  ").isEmpty());
        assertTrue(TaskArgs.parse("{not json").isEmpty());
        assertTrue(TaskArgs.parse("[1,2]").isEmpty());
    }

    @Test
    void titleIsReadable() {
        assertEquals("Mine block at", TaskArgs.title("mine_block_at"));
    }
}
