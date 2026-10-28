package dev.friendai.skill;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.memory.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SkillViewCodecTest {
    @Test
    void maximumEscapedRecordsFitListDetailAndExportResponses() {
        UUID owner = UUID.randomUUID();
        var book = new SkillBook(SkillLimits.defaults(), s -> s, InstructionSkill.Scope.COMPANION);
        for (int i = 0; i < 16; i++)
            book.create(
                    new SkillDraft(
                            "<".repeat(80),
                            "<".repeat(200),
                            "<".repeat(4000),
                            true,
                            false,
                            Set.of(),
                            List.of(),
                            List.of(),
                            false),
                    InstructionSkill.Source.OWNER,
                    owner,
                    1,
                    false);
        var all = book.snapshot();
        var description = new CompanionDescription("<".repeat(300), "<".repeat(4000));
        var list = SkillViewBuilder.build(true, 1, all, Set.of(), 0, "", description, OwnerProfile.EMPTY, null);
        assertTrue(SkillViewCodec.encode(list).length <= 65536);
        var detail = SkillViewBuilder.build(
                true, 1, all, Set.of(), 0, all.getFirst().id(), description, OwnerProfile.EMPTY, null);
        assertTrue(SkillViewCodec.encode(detail).length <= 65536);
        var exported = new SkillActionResult(
                dev.friendai.memory.MenuStatus.ok("friendai.menu.skills.exported"),
                Optional.empty(),
                SkillTransfer.write(all.getFirst()));
        var transfer = SkillViewBuilder.build(
                true, 1, all, Set.of(), 0, all.getFirst().id(), description, OwnerProfile.EMPTY, exported);
        assertTrue(SkillViewCodec.encode(transfer).length <= 65536);
    }

    @Test
    void longUnicodeBodyAndProfileRoundTrip() {
        UUID owner = UUID.randomUUID();
        var book = new SkillBook(SkillLimits.defaults(), s -> s, InstructionSkill.Scope.COMPANION);
        var record = book.create(
                SkillDraft.simple("Long", "😀".repeat(2000)), InstructionSkill.Source.OWNER, owner, 1, false);
        var view = new SkillView(
                true,
                1,
                0,
                1,
                List.of(SkillView.Row.of(record, true)),
                record,
                new CompanionDescription("Guide", "Details"),
                new OwnerProfile("ru", "Alex"),
                "",
                true,
                "");
        var data = SkillViewCodec.encode(view);
        assertTrue(data.length <= 65536);
        assertEquals(view, SkillViewCodec.decode(data).orElseThrow());
        assertTrue(SkillViewCodec.decode(new byte[65537]).isEmpty());
    }

    @Test
    void editorKeepsUnsavedDraftAcrossAConflict() {
        var model = new SkillEditorModel();
        var view = new SkillView(
                true, 2, 0, 0, List.of(), null, CompanionDescription.EMPTY, OwnerProfile.EMPTY, "", true, "");
        model.onView(view);
        model.edit(SkillDraft.simple("Draft", "Inspect materials."));
        model.onView(new SkillView(
                true,
                3,
                0,
                0,
                List.of(),
                null,
                CompanionDescription.EMPTY,
                OwnerProfile.EMPTY,
                "friendai.menu.skills.invalid",
                false,
                ""));
        assertTrue(model.dirty());
        assertEquals("Draft", model.draft().orElseThrow().name());
        model.onView(view);
        assertEquals(3, model.view().orElseThrow().revision());
    }

    @Test
    void cachedSuccessCannotAcknowledgeANewerSave() {
        var model = new SkillEditorModel();
        model.edit(SkillDraft.simple("New draft", "Inspect facts."));
        model.awaitResponse(42);
        var old = new SkillView(
                        true,
                        3,
                        0,
                        0,
                        List.of(),
                        null,
                        CompanionDescription.EMPTY,
                        OwnerProfile.EMPTY,
                        "friendai.menu.skills.saved",
                        true,
                        "")
                .withResponseId(41);
        assertFalse(model.acceptResponse(old));
        assertTrue(model.dirty());
        assertTrue(model.acceptResponse(old.withResponseId(42)));
        assertFalse(model.dirty());
        model.edit(SkillDraft.simple("Next draft", "Inspect outcomes."));
        model.awaitResponse(43);
        assertFalse(model.acceptResponse(old.withResponseId(42)));
        assertTrue(model.dirty());
    }
}
