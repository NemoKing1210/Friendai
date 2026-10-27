package dev.friendai.skill;

import static org.junit.jupiter.api.Assertions.*;
import dev.friendai.memory.*;
import dev.friendai.tool.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SkillSelectorTest {
    final UUID owner=UUID.randomUUID();
    SkillContext context(AccessLevel access,String message) {
        return new SkillContext(new Caller(owner,"Owner",access),message,"",Set.of(InstructionSkill.Kind.CHAT),
                false,CompanionDescription.EMPTY,OwnerProfile.EMPTY);
    }
    InstructionSkill skill(String id,boolean enabled,boolean always,String body) {
        return new InstructionSkill(id,id,"",body,enabled,InstructionSkill.Scope.COMPANION,
                InstructionSkill.Source.OWNER,owner,1,1,1,InstructionSkill.Activation.ACTIVE,always,
                Set.of(InstructionSkill.Kind.CHAT),List.of("stone"),List.of(),false);
    }
    @Test void disabledAndPrivateEntriesNeverReachGuestsOrExplicitRequests() {
        var s=skill("owner:one",false,true,"Do this.");
        assertTrue(SkillSelector.select(List.of(s),context(AccessLevel.OWNER,"stone"),Set.of(s.id()),24000,2400).skills().isEmpty());
        assertTrue(SkillSelector.select(List.of(skill("owner:two",true,true,"Private.")),context(AccessLevel.GUEST,"stone"),Set.of(),24000,2400).skills().isEmpty());
    }
    @Test void rankingAndSmallBudgetAreDeterministic() {
        var a=skill("owner:a",true,false,"A".repeat(500));
        var z=skill("owner:z",true,true,"Small instruction.");
        var selected=SkillSelector.select(List.of(a,z),context(AccessLevel.OWNER,"ｓｔｏｎｅ"),Set.of(),2000,2400);
        assertEquals(List.of("owner:z"),selected.skills().stream().map(InstructionSkill::id).toList());
        assertTrue(selected.renderedChars()<=400);
        assertEquals(selected,SkillSelector.select(List.of(z,a),context(AccessLevel.OWNER,"stone"),Set.of(),2000,2400));
        var explicit=SkillSelector.select(List.of(z,a),context(AccessLevel.OWNER,"stone"),Set.of(a.id()),0,2400);
        assertEquals("owner:a",explicit.skills().getFirst().id());
    }
    @Test void explicitToolDoesNotDuplicateBodyAcrossSteps() {
        var s=skill("owner:one",true,true,"Unique instruction.");
        var session=new SkillPromptSession(List.of(s),context(AccessLevel.OWNER,"hello"),24000,2400);
        assertFalse(session.request(s.id()).error());
        assertFalse(session.request(s.id()).content().contains("Unique instruction."));
        assertEquals(1,session.sections().size());
    }
    @Test void mandatorySectionsWinAndReportFinalCuts() {
        var mandatory=new SkillPromptSection("mandatory","M".repeat(300),Integer.MAX_VALUE);
        var instruction=new SkillPromptSection("skill:one","Advice",30);
        var ctx=new dev.friendai.agent.prompt.TurnContext(new dev.friendai.brain.Persona("A","B","auto"),"",
                context(AccessLevel.OWNER,"").caller(),"",dev.friendai.brain.TurnKind.PLAYER,"",List.of(),"","",List.of(instruction));
        var fits=new dev.friendai.agent.prompt.PromptAssembler(List.of(mandatory),302).assembleDetailed(ctx);
        assertEquals(List.of("mandatory"),fits.keptSectionIds());
        assertFalse(fits.mandatoryOverrun());
        var over=new dev.friendai.agent.prompt.PromptAssembler(List.of(mandatory),200).assembleDetailed(ctx);
        assertTrue(over.mandatoryOverrun());
        assertEquals("M".repeat(300),over.text());
    }
}
