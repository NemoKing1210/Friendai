package dev.friendai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.features.instructions.InstructionSkill;
import dev.friendai.features.instructions.SkillDraft;
import dev.friendai.protocol.instructions.InstructionRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class InstructionsRequestCodecTest {
    @Test
    void encodesSkillDraftFieldsAsTypedInstructionRequest() {
        SkillDraft draft = new SkillDraft(
                "Builder",
                "short description",
                "bounded body",
                true,
                false,
                Set.of(InstructionSkill.Kind.CHAT, InstructionSkill.Kind.COMBAT),
                List.of("when asked"),
                List.of("build"),
                true);
        InstructionRequest.Create request = new InstructionRequest.Create(draft, true);

        byte[] encoded = ClientRequestCodec.encode(7, request);
        DecodeResult<DecodedClientRequest> result = ClientRequestCodec.decode(encoded);

        assertEquals(1, Byte.toUnsignedInt(encoded[0]));
        assertEquals(4, Byte.toUnsignedInt(encoded[1]));
        assertEquals(16, Byte.toUnsignedInt(encoded[2]));
        assertInstanceOf(DecodeResult.Valid.class, result);
        assertEquals(
                7, ((DecodeResult.Valid<DecodedClientRequest>) result).value().requestId());
        assertEquals(
                request,
                ((DecodeResult.Valid<DecodedClientRequest>) result).value().request());
    }

    @Test
    void rejectsInstructionDraftValuesOutsideCharacterAndCollectionLimits() {
        SkillDraft tooLongBody = draft("x".repeat(4_001), List.of(), Set.of());
        SkillDraft tooManyTriggers =
                draft("body", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m"), Set.of());
        SkillDraft valid = draft("body", List.of(), Set.of());

        assertInstanceOf(
                DecodeResult.Valid.class,
                ClientRequestCodec.decode(ClientRequestCodec.encode(5, new InstructionRequest.Create(valid, false))));

        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(1, new InstructionRequest.Create(tooLongBody, false)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(2, new InstructionRequest.Create(tooManyTriggers, false)));
        assertThrows(
                IllegalArgumentException.class, () -> ClientRequestCodec.encode(3, new InstructionRequest.Page(256)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(
                        4, new InstructionRequest.Edit("owner:sample", 0, draft("body", List.of(), Set.of()), false)));
    }

    @Test
    void boundsProfileDescriptionByEncodedUtf8Bytes() {
        InstructionRequest.DescriptionSet max =
                new InstructionRequest.DescriptionSet("é".repeat(300), "ö".repeat(4_000), "ru-RU", "address");
        DecodeResult<DecodedClientRequest> decoded = ClientRequestCodec.decode(ClientRequestCodec.encode(1, max));

        assertEquals(
                1, ((DecodeResult.Valid<DecodedClientRequest>) decoded).value().requestId());
        assertEquals(
                max,
                ((DecodeResult.Valid<DecodedClientRequest>) decoded).value().request());
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientRequestCodec.encode(
                        2, new InstructionRequest.DescriptionSet("é".repeat(301), "details", "ru", "home")));
    }

    private static SkillDraft draft(String body, List<String> triggers, Set<InstructionSkill.Kind> kinds) {
        return new SkillDraft("Skill", "description", body, true, false, kinds, triggers, List.of(), false);
    }
}
