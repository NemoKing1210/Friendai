package dev.friendai.memory;

import dev.friendai.text.UserMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure-Java wire format for {@link MemoryView}. Decoding failures are {@link IllegalArgumentException}. */
public final class MemoryViewCodec {
    public static final int MAX_FACTS = 2000;
    public static final int MAX_GROUPS = 500;
    private static final int MAX_STRING_BYTES = 1 << 20;
    private static final int MAX_ARGS = 100;

    private MemoryViewCodec() {}

    public static byte[] encode(MemoryView view) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeBoolean(view.enabled());
            out.writeInt(view.limits().maxFactChars());
            out.writeInt(view.limits().maxFactsPerPlayer());
            out.writeInt(view.limits().maxWorldFacts());
            out.writeInt(view.groups().size());
            for (MemoryView.Group group : view.groups()) {
                writeString(out, group.key());
                writeString(out, group.name());
                out.writeInt(group.role().ordinal());
                out.writeInt(group.count());
                out.writeBoolean(group.online());
            }
            out.writeInt(view.facts().size());
            for (MemoryView.FactRow fact : view.facts()) {
                out.writeInt(fact.id());
                writeString(out, fact.groupKey());
                out.writeInt(fact.category().ordinal());
                writeString(out, fact.text());
                writeString(out, fact.authorName());
            }
            if (view.status().isPresent()) {
                MenuStatus status = view.status().get();
                out.writeBoolean(true);
                out.writeBoolean(status.ok());
                writeString(out, status.message().key());
                out.writeInt(status.message().args().size());
                for (Object arg : status.message().args()) {
                    writeString(out, String.valueOf(arg));
                }
            } else {
                out.writeBoolean(false);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Encodes a view no larger than {@code maxBytes}. Facts are dropped from the end until it fits, so a
     * book larger than the packet limit degrades to a partial tab instead of a view the client rejects.
     */
    public static byte[] encodeFitting(MemoryView view, int maxBytes) {
        byte[] data = encode(view);
        if (data.length <= maxBytes) {
            return data;
        }
        List<MemoryView.FactRow> facts = view.facts();
        int keep = facts.size();
        while (keep > 0) {
            keep /= 2;
            data = encode(withFacts(view, facts.subList(0, keep)));
            if (data.length <= maxBytes) {
                return data;
            }
        }
        return data;
    }

    private static MemoryView withFacts(MemoryView view, List<MemoryView.FactRow> facts) {
        return new MemoryView(
                view.enabled(), view.limits(), view.groups(), List.copyOf(facts), view.status());
    }

    public static MemoryView decode(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            boolean enabled = in.readBoolean();
            MemoryLimits limits = new MemoryLimits(in.readInt(), in.readInt(), in.readInt());
            int groupCount = readCount(in, MAX_GROUPS);
            List<MemoryView.Group> groups = new ArrayList<>();
            MemoryView.Role[] roles = MemoryView.Role.values();
            for (int i = 0; i < groupCount; i++) {
                String key = readString(in);
                String name = readString(in);
                int role = in.readInt();
                if (role < 0 || role >= roles.length) {
                    throw new IllegalArgumentException("bad role " + role);
                }
                groups.add(new MemoryView.Group(key, name, roles[role], in.readInt(), in.readBoolean()));
            }
            int factCount = readCount(in, MAX_FACTS);
            List<MemoryView.FactRow> facts = new ArrayList<>();
            Category[] categories = Category.values();
            for (int i = 0; i < factCount; i++) {
                int id = in.readInt();
                String groupKey = readString(in);
                int category = in.readInt();
                if (category < 0 || category >= categories.length) {
                    throw new IllegalArgumentException("bad category " + category);
                }
                facts.add(new MemoryView.FactRow(id, groupKey, categories[category], readString(in), readString(in)));
            }
            Optional<MenuStatus> status = Optional.empty();
            if (in.readBoolean()) {
                boolean ok = in.readBoolean();
                String key = readString(in);
                int argCount = readCount(in, MAX_ARGS);
                List<Object> args = new ArrayList<>();
                for (int i = 0; i < argCount; i++) {
                    args.add(readString(in));
                }
                status = Optional.of(new MenuStatus(ok, new UserMessage(key, args)));
            }
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing bytes");
            }
            return new MemoryView(enabled, limits, List.copyOf(groups), List.copyOf(facts), status);
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed memory view", e);
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

    private static int readCount(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) {
            throw new IllegalArgumentException("bad count " + n);
        }
        return n;
    }
}
