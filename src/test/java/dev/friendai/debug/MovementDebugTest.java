package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MovementDebugTest {
    private static MovementDebug sample() {
        return new MovementDebug(7, 2,
                List.of(new MovementDebug.Segment(0, 64, 0, 1, 64, 0, 0), new MovementDebug.Segment(1, 64, 0, 1, 65, 1, 2)),
                List.of(new MovementDebug.Cell(1, 65, 1)),
                List.of(new MovementDebug.Cell(2, 63, 1)),
                List.of(new MovementDebug.Cell(5, 64, 5)),
                List.of("nav MOVING", "goal point 5 64 5"));
    }

    @Test
    void roundTrips() {
        assertEquals(sample(), MovementDebug.decode(sample().encode()));
    }

    @Test
    void trimsWhatIsTooLarge() {
        List<MovementDebug.Segment> many = new ArrayList<>();
        for (int i = 0; i < MovementDebug.MAX_SEGMENTS + 50; i++) {
            many.add(new MovementDebug.Segment(i, 0, 0, i + 1, 0, 0, 0));
        }
        MovementDebug big = new MovementDebug(1, 0, many, List.of(), List.of(), List.of(), List.of("x".repeat(500)));
        MovementDebug back = MovementDebug.decode(big.encode());
        assertEquals(MovementDebug.MAX_SEGMENTS, back.route().size());
        assertEquals(MovementDebug.MAX_LINE_CHARS, back.lines().get(0).length());
    }

    @Test
    void rejectsGarbage() {
        byte[] bytes = sample().encode();
        assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(new byte[] {1, 2, 3}));
        byte[] longer = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(longer));
    }
}
