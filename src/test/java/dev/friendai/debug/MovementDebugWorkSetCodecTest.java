package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class MovementDebugWorkSetCodecTest {
    private static final byte[] OLD_EMPTY_VIEW = HexFormat.of().parseHex("00000007FFFFFFFF0000000000000000000000");

    @Test
    void oldPayloadDecodesWithoutWorkSets() {
        MovementDebug decoded = MovementDebug.decode(OLD_EMPTY_VIEW);

        assertEquals(List.of(), decoded.workSets());
    }

    @Test
    void roundTripsWorkSetStatusCountsAndCells() {
        WorkCell cell = new WorkCell(4, 65, -2, DebugAction.BREAK, WorkStatus.NEXT, "deepslate");
        WorkSet set = new WorkSet("ore vein", WorkKind.BLOCKS, 3, 1, 1, 0, 1, true, List.of(cell));
        MovementDebug view = new MovementDebug(
                3,
                1,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("task: mine_blocks"),
                List.of(),
                List.of(),
                List.of(set));

        MovementDebug decoded = MovementDebug.decode(view.encode());

        assertEquals(view, decoded);
        assertEquals(List.of(set), decoded.workSets());
    }

    @Test
    void rejectsUnknownWorkKindOrdinal() throws IOException {
        byte[] bytes = extension(out -> {
            out.writeByte(1);
            out.writeUTF("tree");
            out.writeByte(255);
        });

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(bytes));

        assertTrue(error.getMessage().startsWith("unknown work-set kind"), error.getMessage());
    }

    @Test
    void rejectsWorkSetCountAboveProtocolLimit() throws IOException {
        byte[] bytes = extension(out -> out.writeByte(9));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(bytes));

        assertTrue(error.getMessage().startsWith("too many work sets"), error.getMessage());
    }

    @Test
    void rejectsWorkSetCellCountAboveProtocolLimit() throws IOException {
        byte[] bytes = extension(out -> {
            out.writeByte(1);
            out.writeUTF("vein");
            out.writeByte(0);
            out.writeInt(257);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(257);
            out.writeInt(0);
            out.writeBoolean(true);
            out.writeShort(257);
        });

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(bytes));

        assertTrue(error.getMessage().startsWith("too many work-set cells"), error.getMessage());
    }

    @Test
    void rejectsUnknownWorkCellActionOrdinal() throws IOException {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> MovementDebug.decode(oneCellExtension(255, WorkStatus.NEXT.ordinal())));

        assertTrue(error.getMessage().startsWith("unknown work-set action"), error.getMessage());
    }

    @Test
    void rejectsUnknownWorkCellStatusOrdinal() throws IOException {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> MovementDebug.decode(oneCellExtension(DebugAction.BREAK.ordinal(), 255)));

        assertTrue(error.getMessage().startsWith("unknown work-set status"), error.getMessage());
    }

    @Test
    void rejectsCountsThatDoNotEqualTheWorkSetTotal() throws IOException {
        byte[] bytes = extension(out -> {
            out.writeByte(1);
            out.writeUTF("vein");
            out.writeByte(WorkKind.BLOCKS.ordinal());
            out.writeInt(2);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(1);
            out.writeInt(0);
            out.writeBoolean(false);
            out.writeShort(1);
            out.writeInt(0);
            out.writeInt(64);
            out.writeInt(0);
            out.writeByte(DebugAction.BREAK.ordinal());
            out.writeByte(WorkStatus.NEXT.ordinal());
            out.writeUTF("ore");
        });

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(bytes));

        assertTrue(error.getMessage().startsWith("work-set counts"), error.getMessage());
    }

    private static byte[] oneCellExtension(int action, int status) throws IOException {
        return extension(out -> {
            out.writeByte(1);
            out.writeUTF("vein");
            out.writeByte(WorkKind.BLOCKS.ordinal());
            out.writeInt(1);
            out.writeInt(0);
            out.writeInt(1);
            out.writeInt(0);
            out.writeInt(0);
            out.writeBoolean(false);
            out.writeShort(1);
            out.writeInt(4);
            out.writeInt(65);
            out.writeInt(-2);
            out.writeByte(action);
            out.writeByte(status);
            out.writeUTF("ore");
        });
    }

    private static byte[] extension(IoWriter workSets) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(OLD_EMPTY_VIEW);
        workSets.write(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream out) throws IOException;
    }
}
