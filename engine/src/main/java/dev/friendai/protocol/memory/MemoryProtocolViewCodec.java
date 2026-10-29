package dev.friendai.protocol.memory;

import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.MemoryView;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolBodyReader;
import dev.friendai.protocol.ProtocolBodyWriter;
import dev.friendai.protocol.ProtocolDataException;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolVersion;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewBodies;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Versioned, bounded memory view codec. Role and category IDs are fixed protocol values. */
public final class MemoryProtocolViewCodec {
    public static final int MAX_FACTS = 2_000;
    public static final int MAX_GROUPS = 500;
    public static final int MAX_FRAME_BYTES = 1 << 20;
    private static final int MAX_GROUP_KEY_BYTES = 36;
    private static final int MAX_NAME_BYTES = 128;
    private static final int MAX_FACT_TEXT_BYTES = 2_000;
    private static final int MAX_AUTHOR_BYTES = 128;
    private static final int MAX_PERSONA_BYTES = 1_200;

    private MemoryProtocolViewCodec() {}

    public static byte[] encode(MemoryView view) {
        return encode(view, 0);
    }

    public static byte[] encode(MemoryView view, long requestId) {
        byte[] body = encodeBody(view, MAX_FRAME_BYTES - ProtocolVersion.HEADER_BYTES);
        return ProtocolViewBodies.encode(ProtocolFamily.MEMORY, 128, requestId, body);
    }

    public static byte[] encodeFitting(MemoryView view, long requestId) {
        return encodeFitting(view, requestId, MAX_FRAME_BYTES);
    }

    public static byte[] encodeFitting(MemoryView view, long requestId, int maxFrameBytes) {
        if (maxFrameBytes < ProtocolVersion.HEADER_BYTES || maxFrameBytes > MAX_FRAME_BYTES)
            throw new IllegalArgumentException("invalid memory frame limit");
        List<MemoryView.FactRow> facts = view.facts();
        int keep = facts.size();
        while (true) {
            MemoryView candidate = withFacts(view, facts.subList(0, keep));
            try {
                byte[] body = encodeBody(candidate, maxFrameBytes - ProtocolVersion.HEADER_BYTES);
                return ProtocolViewBodies.encode(ProtocolFamily.MEMORY, 128, requestId, body);
            } catch (ProtocolDataException tooLarge) {
                if (tooLarge.reason() != DecodeResult.Reason.OVERSIZED_FRAME || keep == 0) throw tooLarge;
                keep /= 2;
            }
        }
    }

    public static DecodeResult<ProtocolView<MemoryView>> decode(byte[] encoded) {
        return ProtocolViewBodies.decode(encoded, ProtocolFamily.MEMORY, 128, MemoryProtocolViewCodec::decodeBody);
    }

    private static byte[] encodeBody(MemoryView view, int maxBytes) {
        if (view.groups().size() > MAX_GROUPS || view.facts().size() > MAX_FACTS)
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        ProtocolBodyWriter out = new ProtocolBodyWriter(maxBytes);
        out.writeBoolean(view.enabled());
        out.writeInt(view.limits().maxFactChars());
        out.writeInt(view.limits().maxFactsPerPlayer());
        out.writeInt(view.limits().maxWorldFacts());
        ProtocolViewBodies.writeCount(out, view.groups().size(), MAX_GROUPS);
        for (MemoryView.Group group : view.groups()) {
            out.writeString(group.key(), MAX_GROUP_KEY_BYTES);
            out.writeString(group.name(), MAX_NAME_BYTES);
            out.writeUnsignedByte(roleId(group.role()));
            out.writeInt(group.count());
            out.writeBoolean(group.online());
        }
        ProtocolViewBodies.writeCount(out, view.facts().size(), MAX_FACTS);
        for (MemoryView.FactRow fact : view.facts()) {
            out.writeInt(fact.id());
            out.writeString(fact.groupKey(), MAX_GROUP_KEY_BYTES);
            out.writeUnsignedByte(categoryId(fact.category()));
            out.writeString(fact.text(), MAX_FACT_TEXT_BYTES);
            out.writeString(fact.authorName(), MAX_AUTHOR_BYTES);
        }
        ProtocolViewBodies.writeStatus(out, view.status());
        out.writeString(view.persona().text(), MAX_PERSONA_BYTES);
        out.writeBoolean(view.persona().custom());
        return out.toByteArray();
    }

    private static MemoryView decodeBody(ProtocolBodyReader in) {
        boolean enabled = in.readBoolean();
        MemoryLimits limits = new MemoryLimits(in.readInt(), in.readInt(), in.readInt());
        int groupCount = ProtocolViewBodies.readCount(in, MAX_GROUPS);
        List<MemoryView.Group> groups = new ArrayList<>(groupCount);
        for (int i = 0; i < groupCount; i++) {
            String key = in.readString(MAX_GROUP_KEY_BYTES);
            String name = in.readString(MAX_NAME_BYTES);
            MemoryView.Role role = role(in.readUnsignedByte());
            int count = in.readInt();
            boolean online = in.readBoolean();
            groups.add(new MemoryView.Group(key, name, role, count, online));
        }
        int factCount = ProtocolViewBodies.readCount(in, MAX_FACTS);
        List<MemoryView.FactRow> facts = new ArrayList<>(factCount);
        for (int i = 0; i < factCount; i++) {
            int id = in.readInt();
            String groupKey = in.readString(MAX_GROUP_KEY_BYTES);
            Category category = category(in.readUnsignedByte());
            String text = in.readString(MAX_FACT_TEXT_BYTES);
            String author = in.readString(MAX_AUTHOR_BYTES);
            facts.add(new MemoryView.FactRow(id, groupKey, category, text, author));
        }
        Optional<dev.friendai.features.memory.MenuStatus> status = ProtocolViewBodies.readStatus(in);
        MemoryView.PersonaView persona = new MemoryView.PersonaView(in.readString(MAX_PERSONA_BYTES), in.readBoolean());
        return new MemoryView(enabled, limits, List.copyOf(groups), List.copyOf(facts), status, persona);
    }

    private static MemoryView withFacts(MemoryView view, List<MemoryView.FactRow> facts) {
        return new MemoryView(
                view.enabled(), view.limits(), view.groups(), List.copyOf(facts), view.status(), view.persona());
    }

    private static int roleId(MemoryView.Role role) {
        return switch (role) {
            case OWNER -> 1;
            case WORLD -> 2;
            case TRUSTED -> 3;
            case GUEST -> 4;
        };
    }

    private static MemoryView.Role role(int id) {
        return switch (id) {
            case 1 -> MemoryView.Role.OWNER;
            case 2 -> MemoryView.Role.WORLD;
            case 3 -> MemoryView.Role.TRUSTED;
            case 4 -> MemoryView.Role.GUEST;
            default -> throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        };
    }

    private static int categoryId(Category category) {
        return switch (category) {
            case PROFILE -> 1;
            case PREFERENCE -> 2;
            case PLACE -> 3;
            case PROMISE -> 4;
            case NOTE -> 5;
        };
    }

    private static Category category(int id) {
        return switch (id) {
            case 1 -> Category.PROFILE;
            case 2 -> Category.PREFERENCE;
            case 3 -> Category.PLACE;
            case 4 -> Category.PROMISE;
            case 5 -> Category.NOTE;
            default -> throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
        };
    }
}
