package dev.friendai.debug;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Pure-Java wire format for {@link CompanionSnapshot}. Decoding failures are {@link IllegalArgumentException}. */
public final class SnapshotCodec {
    private static final int MAX_STRING_BYTES = 1 << 20;
    private static final int MAX_LIST = 1000;

    private SnapshotCodec() {}

    public static byte[] encode(CompanionSnapshot s) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeBoolean(s.present());
            out.writeBoolean(s.operator());
            writeString(out, s.companionName());
            writeString(out, s.provider());
            writeString(out, s.model());
            out.writeBoolean(s.thinking());
            writeString(out, s.currentTask());
            out.writeInt(s.history().size());
            for (TaskRecord r : s.history()) {
                out.writeInt(r.id());
                writeString(out, r.toolName());
                writeString(out, r.status());
                writeString(out, r.summary());
            }
            out.writeInt(s.newLog().size());
            for (LogEntry e : s.newLog()) {
                out.writeLong(e.id());
                out.writeLong(e.timeMillis());
                out.writeInt(e.kind().ordinal());
                writeString(out, e.text());
            }
            writeString(out, s.perception());
            out.writeInt(s.toolNames().size());
            for (String name : s.toolNames()) {
                writeString(out, name);
            }
            CompanionVitals v = s.vitals();
            out.writeFloat(v.health());
            out.writeFloat(v.maxHealth());
            out.writeInt(v.food());
            out.writeInt(v.inventoryUsed());
            out.writeInt(v.inventorySlots());
            writeString(out, v.combatMode());
            writeString(out, v.paceMode());
            out.writeInt(v.xpLevel());
            out.writeFloat(v.xpProgress());
            out.writeInt(s.queue().size());
            for (QueueRow r : s.queue()) {
                out.writeLong(r.id());
                out.writeLong(r.planId());
                writeString(out, r.planGoal());
                writeString(out, r.planStatus());
                writeString(out, r.tool());
                writeString(out, r.args());
                writeString(out, r.state());
                writeString(out, r.note());
                out.writeInt(r.after().size());
                for (long dependency : r.after()) {
                    out.writeLong(dependency);
                }
                out.writeInt(r.attempts());
                out.writeInt(r.maxAttempts());
                out.writeInt(r.priority());
            }
            out.writeBoolean(s.autoRespawn());
            out.writeBoolean(s.autoRespawnAvailable());
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static CompanionSnapshot decode(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            boolean present = in.readBoolean();
            boolean operator = in.readBoolean();
            String name = readString(in);
            String provider = readString(in);
            String model = readString(in);
            boolean thinking = in.readBoolean();
            String task = readString(in);
            int historySize = readCount(in);
            List<TaskRecord> history = new ArrayList<>();
            for (int i = 0; i < historySize; i++) {
                history.add(new TaskRecord(in.readInt(), readString(in), readString(in), readString(in)));
            }
            int logSize = readCount(in);
            List<LogEntry> log = new ArrayList<>();
            LogKind[] kinds = LogKind.values();
            for (int i = 0; i < logSize; i++) {
                long id = in.readLong();
                long time = in.readLong();
                int kind = in.readInt();
                if (kind < 0 || kind >= kinds.length) {
                    throw new IllegalArgumentException("bad log kind " + kind);
                }
                log.add(new LogEntry(id, time, kinds[kind], readString(in)));
            }
            String perception = readString(in);
            int toolCount = readCount(in);
            List<String> tools = new ArrayList<>();
            for (int i = 0; i < toolCount; i++) {
                tools.add(readString(in));
            }
            CompanionVitals vitals = new CompanionVitals(
                    in.readFloat(), in.readFloat(), in.readInt(), in.readInt(), in.readInt(), readString(in), readString(in),
                    in.readInt(), in.readFloat());
            int queueSize = readCount(in);
            List<QueueRow> queue = new ArrayList<>();
            for (int i = 0; i < queueSize; i++) {
                long id = in.readLong();
                long planId = in.readLong();
                String goal = readString(in);
                String planStatus = readString(in);
                String tool = readString(in);
                String args = readString(in);
                String state = readString(in);
                String note = readString(in);
                int afterSize = readCount(in);
                List<Long> after = new ArrayList<>();
                for (int j = 0; j < afterSize; j++) {
                    after.add(in.readLong());
                }
                queue.add(new QueueRow(id, planId, goal, planStatus, tool, args, state, note, after,
                        in.readInt(), in.readInt(), in.readInt()));
            }
            boolean autoRespawn = true;
            boolean autoRespawnAvailable = present;
            if (in.available() == 2) {
                autoRespawn = in.readBoolean();
                autoRespawnAvailable = in.readBoolean();
            }
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing bytes");
            }
            return new CompanionSnapshot(
                    present, operator, name, provider, model, thinking, task, history, log, perception, tools, vitals,
                    queue, autoRespawn, autoRespawnAvailable);
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed snapshot", e);
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > in.available()) {
            throw new IllegalArgumentException("bad string length " + length);
        }
        byte[] b = new byte[length];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static int readCount(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_LIST) {
            throw new IllegalArgumentException("bad count " + n);
        }
        return n;
    }
}
