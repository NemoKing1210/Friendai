package dev.friendai.features.instructions;

import com.google.gson.Gson;
import java.nio.*;
import java.nio.charset.*;
import java.util.Optional;

/** Strict byte and collection bounds before a decoded view reaches client widgets. */
public final class SkillViewCodec {
    public static final int MAX_BYTES = 65536;
    private static final Gson GSON = new Gson();

    private SkillViewCodec() {}

    public static byte[] encode(SkillView view) {
        byte[] bytes = GSON.toJson(view).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Skill view too large.");
        return bytes;
    }

    public static Optional<SkillView> decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) return Optional.empty();
        try {
            String json = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            SkillView v = GSON.fromJson(json, SkillView.class);
            if (v == null
                    || v.rows().size() > 16
                    || v.total() < 0
                    || v.total() > 128
                    || v.page() < 0
                    || v.revision() < 0
                    || v.statusKey() == null
                    || v.statusKey().length() > 100
                    || v.exportText() == null
                    || v.exportText().length() > 32768
                    || v.description() == null
                    || v.ownerProfile() == null) return Optional.empty();
            for (var r : v.rows())
                if (r.id() == null
                        || r.id().length() > 100
                        || r.name() == null
                        || r.name().length() > 80
                        || r.description() == null
                        || r.description().length() > 200
                        || r.source() == null
                        || r.scope() == null
                        || r.author() == null
                        || r.author().length() > 36
                        || r.activation() == null) return Optional.empty();
            if (v.detail() != null) SkillText.validate(v.detail().draft(), s -> s);
            return Optional.of(v);
        } catch (RuntimeException | CharacterCodingException | StackOverflowError e) {
            return Optional.empty();
        }
    }
}
