package dev.friendai.protocol.instructions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.features.instructions.InstructionSkill;
import dev.friendai.features.instructions.SkillText;
import dev.friendai.features.instructions.SkillView;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolDataException;
import dev.friendai.protocol.ProtocolEnumAdapter;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolJsonViewCodec;
import dev.friendai.protocol.ProtocolVersion;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewCodec;
import java.lang.reflect.Type;
import java.util.Map;

/** Bounded framed skills/profile view codec with explicit IDs for instruction enums. */
public final class SkillProtocolViewCodec {
    public static final int MAX_FRAME_BYTES = 65_536;
    private static final Gson GSON = gson();
    private static final Type TYPE = SkillView.class;

    private SkillProtocolViewCodec() {}

    public static byte[] encode(SkillView view) {
        return encode(view, view.responseId());
    }

    public static byte[] encode(SkillView view, long requestId) {
        validate(view);
        byte[] body =
                ProtocolJsonViewCodec.encodeBody(view, TYPE, GSON, MAX_FRAME_BYTES - ProtocolVersion.HEADER_BYTES);
        return ProtocolViewCodec.encode(ProtocolFamily.INSTRUCTIONS, 128, requestId, body);
    }

    public static DecodeResult<ProtocolView<SkillView>> decode(byte[] encoded) {
        return ProtocolJsonViewCodec.decode(
                encoded, ProtocolFamily.INSTRUCTIONS, 128, TYPE, GSON, SkillProtocolViewCodec::validate);
    }

    private static Gson gson() {
        return new GsonBuilder()
                .registerTypeAdapter(
                        InstructionSkill.Scope.class,
                        new ProtocolEnumAdapter<>(
                                        Map.of(InstructionSkill.Scope.COMPANION, 1, InstructionSkill.Scope.SERVER, 2),
                                        id -> switch (id) {
                                            case 1 -> InstructionSkill.Scope.COMPANION;
                                            case 2 -> InstructionSkill.Scope.SERVER;
                                            default -> null;
                                        })
                                .nullSafe())
                .registerTypeAdapter(
                        InstructionSkill.Source.class,
                        new ProtocolEnumAdapter<>(
                                        Map.of(
                                                InstructionSkill.Source.BUILTIN,
                                                1,
                                                InstructionSkill.Source.OWNER,
                                                2,
                                                InstructionSkill.Source.MODEL,
                                                3),
                                        id -> switch (id) {
                                            case 1 -> InstructionSkill.Source.BUILTIN;
                                            case 2 -> InstructionSkill.Source.OWNER;
                                            case 3 -> InstructionSkill.Source.MODEL;
                                            default -> null;
                                        })
                                .nullSafe())
                .registerTypeAdapter(
                        InstructionSkill.Activation.class,
                        new ProtocolEnumAdapter<>(
                                        Map.of(
                                                InstructionSkill.Activation.ACTIVE,
                                                1,
                                                InstructionSkill.Activation.PENDING,
                                                2),
                                        id -> switch (id) {
                                            case 1 -> InstructionSkill.Activation.ACTIVE;
                                            case 2 -> InstructionSkill.Activation.PENDING;
                                            default -> null;
                                        })
                                .nullSafe())
                .registerTypeAdapter(
                        InstructionSkill.Kind.class,
                        new ProtocolEnumAdapter<>(
                                        Map.of(
                                                InstructionSkill.Kind.CHAT,
                                                1,
                                                InstructionSkill.Kind.EVENT,
                                                2,
                                                InstructionSkill.Kind.PLANNING,
                                                3,
                                                InstructionSkill.Kind.FREE_TIME,
                                                4,
                                                InstructionSkill.Kind.BUILDING,
                                                5,
                                                InstructionSkill.Kind.COMBAT,
                                                6),
                                        id -> switch (id) {
                                            case 1 -> InstructionSkill.Kind.CHAT;
                                            case 2 -> InstructionSkill.Kind.EVENT;
                                            case 3 -> InstructionSkill.Kind.PLANNING;
                                            case 4 -> InstructionSkill.Kind.FREE_TIME;
                                            case 5 -> InstructionSkill.Kind.BUILDING;
                                            case 6 -> InstructionSkill.Kind.COMBAT;
                                            default -> null;
                                        })
                                .nullSafe())
                .create();
    }

    private static void validate(SkillView view) {
        if (view.rows() == null
                || view.rows().size() > 16
                || view.total() < 0
                || view.total() > 128
                || view.page() < 0
                || view.revision() < 0
                || view.statusKey() == null
                || view.exportText() == null
                || view.description() == null
                || view.ownerProfile() == null) {
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        }
        ProtocolJsonViewCodec.requireUtf8(view.statusKey(), 100);
        ProtocolJsonViewCodec.requireUtf8(view.exportText(), 32_768);
        ProtocolJsonViewCodec.requireUtf8(view.description().summary(), 1_200);
        ProtocolJsonViewCodec.requireUtf8(view.description().details(), 16_000);
        ProtocolJsonViewCodec.requireUtf8(view.ownerProfile().language(), 35);
        ProtocolJsonViewCodec.requireUtf8(view.ownerProfile().address(), 320);
        for (SkillView.Row row : view.rows()) {
            if (row == null
                    || row.id() == null
                    || row.source() == null
                    || row.scope() == null
                    || row.activation() == null) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
            ProtocolJsonViewCodec.requireUtf8(row.id(), 128);
            ProtocolJsonViewCodec.requireUtf8(row.name(), 320);
            ProtocolJsonViewCodec.requireUtf8(row.description(), 800);
            ProtocolJsonViewCodec.requireUtf8(row.source(), 32);
            ProtocolJsonViewCodec.requireUtf8(row.scope(), 32);
            ProtocolJsonViewCodec.requireUtf8(row.author(), 36);
            ProtocolJsonViewCodec.requireUtf8(row.activation(), 32);
            if (!switch (row.source()) {
                        case "BUILTIN", "OWNER", "MODEL" -> true;
                        default -> false;
                    }
                    || !switch (row.scope()) {
                        case "COMPANION", "SERVER" -> true;
                        default -> false;
                    }
                    || !switch (row.activation()) {
                        case "ACTIVE", "PENDING" -> true;
                        default -> false;
                    }) throw new ProtocolDataException(DecodeResult.Reason.INVALID_ENUM);
            if (row.version() < 1 || row.createdAt() < 0 || row.updatedAt() < row.createdAt())
                throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        }
        if (view.detail() != null) {
            if (view.detail().turnKinds().size() > 6
                    || view.detail().triggers().size() > 12
                    || view.detail().tags().size() > 12) {
                throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
            }
            SkillText.validate(view.detail().draft(), ignored -> ignored);
        }
    }
}
