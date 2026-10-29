package dev.friendai.protocol.instructions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.instructions.InstructionSkill;
import dev.friendai.features.instructions.SkillBook;
import dev.friendai.features.instructions.SkillDraft;
import dev.friendai.features.instructions.SkillLimits;
import dev.friendai.features.instructions.SkillView;
import dev.friendai.features.memory.CompanionDescription;
import dev.friendai.features.memory.OwnerProfile;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolVersion;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SkillProtocolViewCodecTest {
    @Test
    void framesSkillViewsAndUsesExplicitKindIds() {
        UUID owner = UUID.randomUUID();
        var book = new SkillBook(SkillLimits.defaults(), value -> value, InstructionSkill.Scope.COMPANION);
        var skill = book.create(
                new SkillDraft(
                        "Builder",
                        "Build carefully",
                        "Use safe blocks.",
                        true,
                        false,
                        Set.of(InstructionSkill.Kind.COMBAT),
                        List.of(),
                        List.of("building"),
                        true),
                InstructionSkill.Source.OWNER,
                owner,
                1,
                false);
        SkillView view = new SkillView(
                true,
                3,
                0,
                1,
                List.of(SkillView.Row.of(skill, true)),
                skill,
                new CompanionDescription("summary", "details"),
                new OwnerProfile("ru", "Alex"),
                "",
                true,
                "",
                55);

        byte[] encoded = SkillProtocolViewCodec.encode(view);
        String json = new String(
                encoded,
                ProtocolVersion.HEADER_BYTES,
                encoded.length - ProtocolVersion.HEADER_BYTES,
                StandardCharsets.UTF_8);
        assertTrue(json.contains("\"turnKinds\":[6]"));
        DecodeResult.Valid<ProtocolView<SkillView>> decoded =
                assertInstanceOf(DecodeResult.Valid.class, SkillProtocolViewCodec.decode(encoded));
        assertEquals(55, decoded.value().requestId());
        assertEquals(view, decoded.value().value());

        byte[] invalidKind = ProtocolViewCodec.encode(
                ProtocolFamily.INSTRUCTIONS,
                128,
                55,
                json.replace("\"turnKinds\":[6]", "\"turnKinds\":[9]").getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(DecodeResult.Malformed.class, SkillProtocolViewCodec.decode(invalidKind));
    }
}
