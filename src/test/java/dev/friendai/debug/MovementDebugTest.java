package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.queue.QueuedStep;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MovementDebugTest {
    private static QueuedStep step(String tool) {
        return new QueuedStep(
                tool, new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.STOP_PLAN, 1, List.of());
    }

    private static MovementDebug sample() {
        return new MovementDebug(
                7,
                2,
                List.of(
                        new MovementDebug.Segment(0, 64, 0, 1, 64, 0, 0),
                        new MovementDebug.Segment(1, 64, 0, 1, 65, 1, 2)),
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
    void roundTripsActionTargetsAndUpcomingSteps() {
        MovementDebug view = new MovementDebug(
                3,
                1,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("task: mining"),
                List.of(new DebugTarget(4, 65, -2, DebugAction.BREAK, "deepslate")),
                List.of("craft_item", "smelt_items"));

        assertEquals(view, MovementDebug.decode(view.encode()));
    }

    @Test
    void targetAndUpcomingListsAreImmutable() {
        List<DebugTarget> targets = new ArrayList<>(List.of(new DebugTarget(1, 2, 3, DebugAction.USE, "door")));
        List<String> steps = new ArrayList<>(List.of("use_block"));
        MovementDebug view =
                new MovementDebug(1, 0, List.of(), List.of(), List.of(), List.of(), List.of(), targets, steps);
        targets.clear();
        steps.clear();

        assertEquals(1, view.targets().size());
        assertEquals(List.of("use_block"), view.upcomingSteps());
        assertThrows(UnsupportedOperationException.class, () -> view.targets().clear());
        assertThrows(
                UnsupportedOperationException.class, () -> view.upcomingSteps().clear());
    }

    @Test
    void boundsTargetDetailAndUpcomingStepText() {
        DebugTarget noDetail = new DebugTarget(0, 0, 0, DebugAction.USE, null);
        DebugTarget longDetail = new DebugTarget(0, 0, 0, DebugAction.USE, "x".repeat(120));
        MovementDebug view = new MovementDebug(
                1,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(noDetail, longDetail),
                List.of("y".repeat(300)));
        MovementDebug decoded = MovementDebug.decode(view.encode());

        assertEquals("", decoded.targets().get(0).detail());
        assertEquals(80, decoded.targets().get(1).detail().length());
        assertEquals(160, decoded.upcomingSteps().get(0).length());
    }

    @Test
    void trimsExcessTargetsAndUpcomingSteps() {
        List<DebugTarget> targets = new ArrayList<>();
        for (int i = 0; i < MovementDebug.MAX_TARGETS + 3; i++) {
            targets.add(new DebugTarget(i, 0, 0, DebugAction.BREAK, "ore"));
        }
        MovementDebug view = new MovementDebug(
                1,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                targets,
                List.of("first", "second", "third"));

        MovementDebug decoded = MovementDebug.decode(view.encode());
        assertEquals(MovementDebug.MAX_TARGETS, decoded.targets().size());
        assertEquals(List.of("first", "second"), decoded.upcomingSteps());
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

    @Test
    void rejectsInvalidActionOrdinal() throws IOException {
        byte[] bytes = emptySnapshotWithOneTarget(255);

        assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(bytes));
    }

    @Test
    void rejectsExcessiveTargetAndUpcomingCounts() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(emptySnapshotWithTargetCount(129)));
        assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(emptySnapshotWithUpcomingCount(3)));
    }

    @Test
    void rejectsTruncatedTargetAndUpcomingStepData() throws IOException {
        byte[] target = emptySnapshotWithOneTarget(DebugAction.BREAK.ordinal());
        byte[] step = emptySnapshotWithOneUpcomingStep();

        assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(java.util.Arrays.copyOf(target, 20)));
        assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(java.util.Arrays.copyOf(step, 17)));
    }

    @Test
    void rejectsOverlongTargetDetail() throws IOException {
        byte[] bytes = snapshotPrefix(1, 0, out -> {
            out.writeInt(4);
            out.writeInt(5);
            out.writeInt(6);
            out.writeByte(DebugAction.BREAK.ordinal());
            out.writeUTF("x".repeat(81));
        });

        assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(bytes));
    }

    @Test
    void rejectsOverlongHudLine() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeInt(-1);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeByte(0); // targets
        out.writeByte(0); // upcoming steps
        out.writeByte(1); // lines
        out.writeUTF("x".repeat(MovementDebug.MAX_LINE_CHARS + 1));

        assertThrows(IllegalArgumentException.class, () -> MovementDebug.decode(bytes.toByteArray()));
    }

    @Test
    void combinesTaskAndPathTargetsInFirstSeenOrderAndDeduplicatesExactMatches() {
        DebugTarget task = new DebugTarget(1, 2, 3, DebugAction.BREAK, "Path obstacle");
        List<MovementDebug.Cell> routeBreaks =
                List.of(new MovementDebug.Cell(1, 2, 3), new MovementDebug.Cell(4, 5, 6));
        List<MovementDebug.Cell> routePlaces = List.of(new MovementDebug.Cell(7, 8, 9));

        assertEquals(
                List.of(
                        task,
                        new DebugTarget(4, 5, 6, DebugAction.BREAK, "Path obstacle"),
                        new DebugTarget(7, 8, 9, DebugAction.PLACE, "Path support")),
                MovementDebug.combineTargets(List.of(task), routeBreaks, routePlaces));
        assertEquals(List.of(), MovementDebug.combineTargets(List.of(), List.of(), List.of()));
    }

    @Test
    void capsCombinedTargetsAtProtocolLimit() {
        List<DebugTarget> taskTargets = new ArrayList<>();
        for (int i = 0; i < MovementDebug.MAX_TARGETS + 5; i++) {
            taskTargets.add(new DebugTarget(i, 0, 0, DebugAction.USE, "target"));
        }
        assertEquals(
                MovementDebug.MAX_TARGETS,
                MovementDebug.combineTargets(taskTargets, List.of(), List.of()).size());
    }

    @Test
    void upcomingStepsArePendingOnlyOrderedAndBounded() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        Job first = queue.addDirect(step("first_tool"));
        Job running = queue.addDirect(step("running_tool"));
        Job third = queue.addDirect(step("third_tool"));
        Job terminal = queue.addDirect(step("terminal_tool"));
        queue.markRunning(running.id());
        queue.finish(terminal.id(), "done");

        assertEquals(
                List.of("first_tool", "third_tool"),
                MovementDebug.upcomingSteps(List.of(
                        queue.job(terminal.id()).orElseThrow(),
                        queue.job(third.id()).orElseThrow(),
                        queue.job(running.id()).orElseThrow(),
                        queue.job(first.id()).orElseThrow())));
        assertEquals(List.of(), MovementDebug.upcomingSteps(List.of()));
        assertEquals(List.of("first_tool"), MovementDebug.upcomingSteps(List.of(first)));
    }

    private static byte[] emptySnapshotWithOneTarget(int action) throws IOException {
        return snapshotPrefix(1, 1, out -> {
            out.writeInt(4);
            out.writeInt(5);
            out.writeInt(6);
            out.writeByte(action);
            out.writeUTF("block");
        });
    }

    private static byte[] emptySnapshotWithTargetCount(int count) throws IOException {
        return snapshotPrefix(count, 0, out -> {});
    }

    private static byte[] emptySnapshotWithUpcomingCount(int count) throws IOException {
        byte[] prefix = snapshotPrefix(0, count, out -> {});
        return prefix;
    }

    private static byte[] emptySnapshotWithOneUpcomingStep() throws IOException {
        return snapshotPrefix(0, 1, out -> out.writeUTF("craft_item"));
    }

    private static byte[] snapshotPrefix(int targetCount, int stepCount, IoWriter contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeInt(-1);
        out.writeShort(0); // route
        out.writeShort(0); // breaks
        out.writeShort(0); // places
        out.writeShort(0); // goal cells
        out.writeByte(targetCount);
        contents.write(out);
        out.writeByte(stepCount);
        if (stepCount == 0) {
            out.writeByte(0); // lines
        }
        return bytes.toByteArray();
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream out) throws IOException;
    }
}
