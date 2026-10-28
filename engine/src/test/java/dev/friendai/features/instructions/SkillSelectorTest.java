package dev.friendai.features.instructions;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.agent.prompt.SkillPromptSection;
import dev.friendai.execution.tool.*;
import dev.friendai.features.memory.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SkillSelectorTest {
    @Test
    void learnedLanguageSurvivesProfileAssemblyAndExplicitPreferenceWins() {
        var context = context(AccessLevel.OWNER, "hello");
        var session = new SkillPromptSession(List.of(), context, 24000, 2400);
        var base = new dev.friendai.agent.turn.Persona("F", "Guide", "auto", "Russian");
        assertEquals("Russian", session.persona(base).ownerLanguage());
        var explicit = new SkillContext(
                context.caller(),
                "hello",
                "",
                context.kinds(),
                false,
                CompanionDescription.EMPTY,
                new OwnerProfile("en", ""));
        var overridden = new SkillPromptSession(List.of(), explicit, 24000, 2400).persona(base);
        assertEquals("en", overridden.language());
        assertEquals("Russian", overridden.ownerLanguage());
        String guest = dev.friendai.agent.turn.PromptBuilder.system(
                base, "", context(AccessLevel.GUEST, "").caller());
        assertFalse(guest.contains("The owner usually writes in Russian"));
    }

    final UUID owner = UUID.randomUUID();

    SkillContext context(AccessLevel access, String message) {
        return new SkillContext(
                new Caller(owner, "Owner", access),
                message,
                "",
                Set.of(InstructionSkill.Kind.CHAT),
                false,
                CompanionDescription.EMPTY,
                OwnerProfile.EMPTY);
    }

    InstructionSkill skill(String id, boolean enabled, boolean always, String body) {
        return new InstructionSkill(
                id,
                id,
                "",
                body,
                enabled,
                InstructionSkill.Scope.COMPANION,
                InstructionSkill.Source.OWNER,
                owner,
                1,
                1,
                1,
                InstructionSkill.Activation.ACTIVE,
                always,
                Set.of(InstructionSkill.Kind.CHAT),
                List.of("stone"),
                List.of(),
                false);
    }

    @Test
    void disabledAndPrivateEntriesNeverReachGuestsOrExplicitRequests() {
        var s = skill("owner:one", false, true, "Do this.");
        assertTrue(SkillSelector.select(List.of(s), context(AccessLevel.OWNER, "stone"), Set.of(s.id()), 24000, 2400)
                .skills()
                .isEmpty());
        assertTrue(SkillSelector.select(
                        List.of(skill("owner:two", true, true, "Private.")),
                        context(AccessLevel.GUEST, "stone"),
                        Set.of(),
                        24000,
                        2400)
                .skills()
                .isEmpty());
    }

    @Test
    void rankingAndSmallBudgetAreDeterministic() {
        var a = skill("owner:a", true, false, "A".repeat(500));
        var z = skill("owner:z", true, true, "Small instruction.");
        var selected = SkillSelector.select(List.of(a, z), context(AccessLevel.OWNER, "ｓｔｏｎｅ"), Set.of(), 2000, 2400);
        assertEquals(
                List.of("owner:z"),
                selected.skills().stream().map(InstructionSkill::id).toList());
        assertTrue(selected.renderedChars() <= 400);
        assertEquals(
                selected,
                SkillSelector.select(List.of(z, a), context(AccessLevel.OWNER, "stone"), Set.of(), 2000, 2400));
        var explicit =
                SkillSelector.select(List.of(z, a), context(AccessLevel.OWNER, "stone"), Set.of(a.id()), 0, 2400);
        assertEquals("owner:a", explicit.skills().getFirst().id());
    }

    @Test
    void explicitToolDoesNotDuplicateBodyAcrossSteps() {
        var s = skill("owner:one", true, true, "Unique instruction.");
        var session = new SkillPromptSession(List.of(s), context(AccessLevel.OWNER, "hello"), 24000, 2400);
        session.recordAssembly(session.sections().stream()
                .map(dev.friendai.agent.prompt.PromptSection::id)
                .toList());
        assertFalse(session.request(s.id()).error());
        assertFalse(session.request(s.id()).content().contains("Unique instruction."));
        assertEquals(1, session.sections().size());
    }

    @Test
    void explicitSkillDisplacesAutomaticSelectionAndCutSkillIsNotCalledActive() {
        var automatic = skill("owner:a", true, true, "A".repeat(100));
        var explicit = new InstructionSkill(
                "owner:z",
                "Other",
                "",
                "EXPLICIT_SENTINEL",
                true,
                automatic.scope(),
                automatic.source(),
                owner,
                1,
                1,
                1,
                automatic.activation(),
                false,
                Set.of(InstructionSkill.Kind.COMBAT),
                List.of(),
                List.of(),
                false);
        var session = new SkillPromptSession(List.of(automatic, explicit), context(AccessLevel.OWNER, "hello"), 0, 250);
        session.recordAssembly(session.sections().stream()
                .map(dev.friendai.agent.prompt.PromptSection::id)
                .toList());
        var result = session.request(explicit.id());
        assertFalse(result.error());
        assertTrue(result.content().contains("EXPLICIT_SENTINEL"));
        assertTrue(
                session.sections().isEmpty(), "the explicit result consumes the slice and evicts the automatic entry");
        var cut = new SkillPromptSession(List.of(automatic), context(AccessLevel.OWNER, "hello"), 0, 250);
        cut.recordAssembly(List.of());
        assertTrue(
                cut.request(automatic.id()).content().contains("A".repeat(100)),
                "a preselected but cut entry must still be deliverable");
    }

    @Test
    void mandatorySectionsWinAndReportFinalCuts() {
        var mandatory = new SkillPromptSection("mandatory", "M".repeat(300), Integer.MAX_VALUE);
        var instruction = new SkillPromptSection("skill:one", "Advice", 30);
        var ctx = new dev.friendai.agent.prompt.TurnContext(
                new dev.friendai.agent.turn.Persona("A", "B", "auto"),
                "",
                context(AccessLevel.OWNER, "").caller(),
                "",
                dev.friendai.agent.turn.TurnKind.PLAYER,
                "",
                List.of(),
                "",
                "",
                "",
                List.of(instruction));
        var fits = new dev.friendai.agent.prompt.PromptAssembler(List.of(mandatory), 302).assembleDetailed(ctx);
        assertEquals(List.of("mandatory"), fits.keptSectionIds());
        assertFalse(fits.mandatoryOverrun());
        var over = new dev.friendai.agent.prompt.PromptAssembler(List.of(mandatory), 200).assembleDetailed(ctx);
        assertTrue(over.mandatoryOverrun());
        assertEquals("M".repeat(300), over.text());
    }
}
