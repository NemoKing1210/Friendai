package dev.friendai.protocol.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.navigation.pathing.Move;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MovementDebugProtocolViewCodecTest {
    @Test
    void framesMovementWithExplicitMoveAndWorkEnumIds() {
        MovementDebugView view = new MovementDebugView(
                7,
                2,
                List.of(new MovementDebugView.Segment(0, 64, 0, 1, 64, 0, Move.DIG)),
                List.of(new MovementDebugView.Cell(1, 2, 3)),
                List.of(),
                List.of(),
                List.of("route ready"),
                List.of(new MovementDebugView.Target(1, 2, 3, MovementDebugView.Action.BREAK, "obstacle")),
                List.of("mine_block"),
                List.of(new MovementDebugView.WorkSet(
                        "blocks",
                        MovementDebugView.WorkKind.BLOCKS,
                        1,
                        0,
                        1,
                        0,
                        0,
                        false,
                        List.of(new MovementDebugView.WorkCell(
                                1,
                                2,
                                3,
                                MovementDebugView.Action.PLACE,
                                MovementDebugView.WorkStatus.NEXT,
                                "support")))));

        byte[] encoded = MovementDebugProtocolViewCodec.encode(view, 31);
        assertEquals(9, Byte.toUnsignedInt(encoded[49]));
        DecodeResult.Valid<ProtocolView<MovementDebugView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, MovementDebugProtocolViewCodec.decode(encoded));
        assertEquals(31, decoded.value().requestId());
        assertEquals(view, decoded.value().value());
    }

    @Test
    void trimsLargeOverlayAndRejectsUnknownMoveIds() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < MovementDebugView.MAX_LINES; i++) lines.add("😀".repeat(160));
        List<MovementDebugView.WorkCell> cells = new ArrayList<>();
        for (int i = 0; i < MovementDebugView.MAX_WORK_CELLS; i++) {
            cells.add(new MovementDebugView.WorkCell(
                    i, 0, 0, MovementDebugView.Action.BREAK, MovementDebugView.WorkStatus.PENDING, "😀".repeat(80)));
        }
        MovementDebugView.WorkSet workSet = new MovementDebugView.WorkSet(
                "blocks", MovementDebugView.WorkKind.BLOCKS, 256, 0, 0, 256, 0, false, cells);
        List<MovementDebugView.Target> targets = new ArrayList<>();
        for (int i = 0; i < MovementDebugView.MAX_TARGETS; i++) {
            targets.add(new MovementDebugView.Target(i, 0, 0, MovementDebugView.Action.BREAK, "😀".repeat(80)));
        }
        MovementDebugView oversized = new MovementDebugView(
                1,
                0,
                List.of(new MovementDebugView.Segment(0, 0, 0, 1, 0, 0, Move.WALK)),
                List.of(),
                List.of(),
                List.of(),
                lines,
                targets,
                List.of(),
                List.of(workSet));
        byte[] fitted = MovementDebugProtocolViewCodec.encode(oversized);
        DecodeResult.Valid<ProtocolView<MovementDebugView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, MovementDebugProtocolViewCodec.decode(fitted));
        assertTrue(fitted.length <= MovementDebugProtocolViewCodec.MAX_FRAME_BYTES);
        assertTrue(decoded.value().value().workSets().getFirst().truncated());
        assertTrue(decoded.value().value().workSets().getFirst().cells().size() < cells.size());

        byte[] unknownMove = MovementDebugProtocolViewCodec.encode(new MovementDebugView(
                1,
                0,
                List.of(new MovementDebugView.Segment(0, 0, 0, 1, 0, 0, Move.DIG)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
        unknownMove[49] = (byte) 255;
        assertInstanceOf(DecodeResult.Malformed.class, MovementDebugProtocolViewCodec.decode(unknownMove));
    }
}
