package dev.friendai.skill;
import static org.junit.jupiter.api.Assertions.*;
import dev.friendai.memory.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SkillViewCodecTest {
    @Test void longUnicodeBodyAndProfileRoundTrip() {
        UUID owner=UUID.randomUUID();var book=new SkillBook(SkillLimits.defaults(),s->s,InstructionSkill.Scope.COMPANION);
        var record=book.create(SkillDraft.simple("Long", "😀".repeat(2000)),InstructionSkill.Source.OWNER,owner,1,false);
        var view=new SkillView(true,1,0,1,List.of(SkillView.Row.of(record,true)),record,new CompanionDescription("Guide","Details"),
                new OwnerProfile("ru","Alex"),"",true,"");
        var data=SkillViewCodec.encode(view);
        assertTrue(data.length<=65536);
        assertEquals(view,SkillViewCodec.decode(data).orElseThrow());
        assertTrue(SkillViewCodec.decode(new byte[65537]).isEmpty());
    }
    @Test void editorKeepsUnsavedDraftAcrossAConflict() {
        var model=new SkillEditorModel();
        var view=new SkillView(true,2,0,0,List.of(),null,CompanionDescription.EMPTY,OwnerProfile.EMPTY,"",true,"");
        model.onView(view);model.edit(SkillDraft.simple("Draft","Inspect materials."));
        model.onView(new SkillView(true,3,0,0,List.of(),null,CompanionDescription.EMPTY,OwnerProfile.EMPTY,"friendai.menu.skills.invalid",false,""));
        assertTrue(model.dirty());assertEquals("Draft",model.draft().orElseThrow().name());
        model.onView(view);assertEquals(3,model.view().orElseThrow().revision());
    }
}
