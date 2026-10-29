package dev.friendai.protocol.instructions;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.execution.tool.*;
import dev.friendai.features.instructions.*;
import dev.friendai.infrastructure.persistence.instructions.SkillStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstructionActionsTest {
    @TempDir
    Path dir;

    final UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static SkillDraft fullDraft(String name) {
        return new SkillDraft(
                name,
                "short description",
                "bounded body",
                false,
                true,
                Set.of(InstructionSkill.Kind.CHAT),
                List.of("when asked"),
                List.of("build"),
                true);
    }

    @Test
    void createKeepsEveryDraftFieldAndGlobalScope() {
        SkillDraft draft = fullDraft("Builder");

        SkillAction action = InstructionActions.toAction(new InstructionRequest.Create(draft, true));

        assertNotNull(action);
        assertEquals(SkillAction.Operation.CREATE, action.operation());
        assertEquals(draft, action.draft());
        assertTrue(action.server());
        assertEquals("", action.id());
    }

    @Test
    void editKeepsIdentityVersionDraftAndScope() {
        SkillDraft draft = fullDraft("Editor");

        SkillAction action = InstructionActions.toAction(new InstructionRequest.Edit("owner:one", 4, draft, false));

        assertEquals(SkillAction.Operation.EDIT, action.operation());
        assertEquals("owner:one", action.id());
        assertEquals(4, action.expectedVersion());
        assertEquals(draft, action.draft());
        assertFalse(action.server());
    }

    @Test
    void importCarriesAFlatPortableDraftInsteadOfANestedEnvelope() {
        SkillDraft draft = fullDraft("Imported");

        SkillAction action = InstructionActions.toAction(new InstructionRequest.Import(draft, true));

        assertEquals(SkillAction.Operation.IMPORT, action.operation());
        assertTrue(action.server());
        assertFalse(action.text().contains("\"draft\""));
        assertEquals(draft, SkillTransfer.read(action.text()));
    }

    @Test
    void enableAndDisableBecomeExplicitDesiredToggleText() {
        SkillAction enable = InstructionActions.toAction(new InstructionRequest.Enable("owner:one", 2, false));
        SkillAction disable = InstructionActions.toAction(new InstructionRequest.Disable("owner:one", 2, false));

        assertEquals(SkillAction.Operation.TOGGLE, enable.operation());
        assertEquals("enable", enable.text());
        assertEquals(SkillAction.Operation.TOGGLE, disable.operation());
        assertEquals("disable", disable.text());
    }

    @Test
    void subscriptionAndDescriptionRequestsAreNotSkillActions() {
        assertNull(InstructionActions.toAction(new InstructionRequest.Open()));
        assertNull(InstructionActions.toAction(new InstructionRequest.Page(1)));
        assertNull(InstructionActions.toAction(new InstructionRequest.Detail("owner:one")));
        assertNull(InstructionActions.toAction(new InstructionRequest.Close()));
        assertNull(InstructionActions.toAction(new InstructionRequest.DescriptionSet("a", "b", "en", "")));
    }

    @Test
    void typedWritesReachTheDomainAndGlobalScopeRequiresAnOperator() {
        try (var store = new SkillStore(dir, SkillLimits.defaults(), s -> s)) {
            var actions = new SkillActions(store, List.of(), Clock.systemUTC());
            var ownerActor = new SkillActor(owner, new Caller(owner, "Owner", AccessLevel.OWNER), false);
            var operatorActor = new SkillActor(owner, new Caller(owner, "Owner", AccessLevel.OWNER), true);
            SkillDraft draft = fullDraft("Builder");

            var created =
                    actions.apply(ownerActor, InstructionActions.toAction(new InstructionRequest.Create(draft, false)));
            assertTrue(created.status().ok());
            assertEquals(
                    draft,
                    store.book(owner)
                            .find(created.skill().orElseThrow().id())
                            .orElseThrow()
                            .draft());

            var imported = actions.apply(
                    operatorActor, InstructionActions.toAction(new InstructionRequest.Import(draft, true)));
            assertTrue(imported.status().ok());
            assertEquals(
                    draft,
                    store.global()
                            .find(imported.skill().orElseThrow().id())
                            .orElseThrow()
                            .draft());
            assertTrue(store.book(owner).snapshot().size() == 1, "global import must not write the local book");

            int localVersion = store.book(owner).snapshot().getFirst().version();
            String localId = store.book(owner).snapshot().getFirst().id();
            var denied = actions.apply(
                    ownerActor,
                    InstructionActions.toAction(new InstructionRequest.Delete(localId, localVersion, true)));
            assertFalse(denied.status().ok(), "nonoperator global mutation must be rejected");
            assertEquals(1, store.global().snapshot().size(), "rejected global delete must not touch the global book");
            assertEquals(
                    1, store.book(owner).snapshot().size(), "rejected global delete must not touch the local book");

            var localDelete = actions.apply(
                    ownerActor,
                    InstructionActions.toAction(new InstructionRequest.Delete(localId, localVersion, false)));
            assertTrue(localDelete.status().ok());
            assertTrue(store.book(owner).snapshot().isEmpty());
        }
    }
}
