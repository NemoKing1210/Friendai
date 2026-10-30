package dev.friendai.presentation.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.history.TaskRecord;
import dev.friendai.features.instructions.InstructionSkill;
import dev.friendai.features.instructions.SkillView;
import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.CompanionDescription;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.MemoryView;
import dev.friendai.features.memory.OwnerProfile;
import dev.friendai.features.places.PlaceView;
import dev.friendai.features.trading.TradeView;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.foundation.diagnostics.LogKind;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanionViewStateTest {
    private static final ClientSessionId FIRST =
            new ClientSessionId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final ClientSessionId SECOND =
            new ClientSessionId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Test
    void lateListResponseCannotReplaceNewerSkillDetail() {
        CompanionViewState state = new CompanionViewState(FIRST);
        SkillView.Row row = skillRow("owner:mine", "Mine");
        InstructionSkill detail = skill("owner:mine");
        state.onSkillsView(FIRST, skillView(4, 0, List.of(row), null, true, 10));
        state.onSkillsView(FIRST, skillView(4, 0, List.of(), detail, true, 12));

        state.onSkillsView(FIRST, skillView(4, 0, List.of(skillRow("owner:other", "Other")), null, true, 11));

        SkillView current = state.skillsView().orElseThrow();
        assertEquals("owner:mine", current.detail().id());
        assertEquals(
                List.of("owner:mine"),
                current.rows().stream().map(SkillView.Row::id).toList());
        assertEquals(12, state.skillsResponse().orElseThrow().responseId());
    }

    @Test
    void staleRevisionDoesNotReplaceCurrentSkillsButStillCorrelatesLatestResponse() {
        CompanionViewState state = new CompanionViewState(FIRST);
        state.onSkillsView(FIRST, skillView(9, 0, List.of(skillRow("owner:mine", "Mine")), null, true, 20));

        state.onSkillsView(FIRST, skillView(8, 0, List.of(skillRow("owner:old", "Old")), null, true, 21));

        assertEquals(9, state.skillsView().orElseThrow().revision());
        assertEquals(
                "owner:mine", state.skillsView().orElseThrow().rows().getFirst().id());
        assertEquals(21, state.skillsResponse().orElseThrow().responseId());
    }

    @Test
    void errorResponseIsCorrelatedWithoutReplacingLastGoodSkillView() {
        CompanionViewState state = new CompanionViewState(FIRST);
        SkillView good = skillView(9, 0, List.of(skillRow("owner:mine", "Mine")), null, true, 30);
        SkillView error = skillView(9, 0, List.of(), null, false, 31);
        state.onSkillsView(FIRST, good);

        state.onSkillsView(FIRST, error);

        assertEquals(good, state.skillsView().orElseThrow());
        assertEquals(31, state.skillsResponse().orElseThrow().responseId());
        assertFalse(state.skillsResponse().orElseThrow().ok());
    }

    @Test
    void deletedMemoryFactAndPlaceClearTheirSelection() {
        CompanionViewState state = new CompanionViewState(FIRST);
        String owner = "00000000-0000-0000-0000-0000000000e0";
        MemoryView memory = memoryView(List.of(new MemoryView.FactRow(7, owner, Category.PROFILE, "Home", "")));
        state.onMemoryView(FIRST, memory);
        state.memoryTabModel().select(7);
        state.onMemoryView(FIRST, memoryView(List.of()));

        assertTrue(state.memoryTabModel().selected().isEmpty());

        state.onPlaceView(FIRST, placeView(List.of(place(5, "Home"))));
        state.placesTabModel().select(5);
        state.onPlaceView(FIRST, placeView(List.of()));

        assertEquals(-1, state.placesTabModel().selected());
    }

    @Test
    void reconnectUsesNewSessionAndRejectsLateOldSessionResponses() {
        CompanionViewState oldServer = new CompanionViewState(FIRST);
        CompanionViewState newServer = new CompanionViewState(SECOND);
        assertTrue(oldServer.onPrompt(FIRST, "old server"));
        oldServer.close();

        assertFalse(newServer.onPrompt(FIRST, "late old response"));
        assertTrue(newServer.onPrompt(SECOND, "new server"));

        assertFalse(oldServer.accepts(FIRST));
        assertEquals("new server", newServer.prompt());
        assertEquals("", oldServer.prompt());
    }

    @Test
    void resetClearsEveryWorldBoundViewAndAccumulatedLog() {
        CompanionViewState state = new CompanionViewState(FIRST);
        state.onSnapshot(FIRST, snapshot(), 100);
        state.onPrompt(FIRST, "prompt");
        state.onMemoryView(FIRST, memoryView(List.of()));
        state.onPlaceView(FIRST, placeView(List.of()));
        state.onTradeView(FIRST, tradeView());
        state.onSkillsView(FIRST, skillView(2, 0, List.of(skillRow("owner:mine", "Mine")), null, true, 1));

        state.reset();

        assertTrue(state.snapshot().isEmpty());
        assertTrue(state.memoryView().isEmpty());
        assertTrue(state.placeView().isEmpty());
        assertTrue(state.tradeView().isEmpty());
        assertTrue(state.skillsView().isEmpty());
        assertTrue(state.skillsResponse().isEmpty());
        assertEquals("", state.prompt());
        assertTrue(state.log().view(Set.of(LogKind.ASSISTANT)).isEmpty());
        assertTrue(state.memoryTabModel().view().isEmpty());
        assertTrue(state.placesTabModel().view().isEmpty());
        assertTrue(state.isStale(10_000));
        assertTrue(state.accepts(FIRST));
    }

    private static SkillView skillView(
            long revision, int page, List<SkillView.Row> rows, InstructionSkill detail, boolean ok, long responseId) {
        return new SkillView(
                true,
                revision,
                page,
                1,
                rows,
                detail,
                CompanionDescription.EMPTY,
                OwnerProfile.EMPTY,
                ok ? "" : "friendai.menu.skills.status.error",
                ok,
                "",
                responseId);
    }

    private static SkillView.Row skillRow(String id, String name) {
        return new SkillView.Row(id, name, "", true, "OWNER", "COMPANION", "", 0, 0, 1, "ACTIVE");
    }

    private static InstructionSkill skill(String id) {
        return new InstructionSkill(
                id,
                "Mine",
                "",
                "",
                true,
                InstructionSkill.Scope.COMPANION,
                InstructionSkill.Source.OWNER,
                UUID.fromString("00000000-0000-0000-0000-0000000000e0"),
                0,
                0,
                1,
                InstructionSkill.Activation.ACTIVE,
                false,
                Set.of(),
                List.of(),
                List.of(),
                false);
    }

    private static MemoryView memoryView(List<MemoryView.FactRow> facts) {
        String owner = "00000000-0000-0000-0000-0000000000e0";
        int count = facts.size();
        return new MemoryView(
                true,
                MemoryLimits.DEFAULTS,
                List.of(new MemoryView.Group(owner, "Owner", MemoryView.Role.OWNER, count, true)),
                facts,
                Optional.empty());
    }

    private static PlaceView.Row place(long id, String name) {
        return new PlaceView.Row(id, name, "", "", "minecraft:overworld", 0, 64, 0, 8, 0, 0, "", "");
    }

    private static PlaceView placeView(List<PlaceView.Row> places) {
        return new PlaceView(true, places, Optional.empty());
    }

    private static TradeView tradeView() {
        return new TradeView(UUID.randomUUID(), 0, 0, 1, List.of(), List.of(), 0, 1_200);
    }

    private static CompanionSnapshot snapshot() {
        return new CompanionSnapshot(
                false,
                false,
                "",
                "",
                "",
                false,
                "",
                List.<TaskRecord>of(),
                List.of(new LogEntry(1, 0, LogKind.ASSISTANT, "hello")),
                "",
                List.of(),
                CompanionVitals.NONE,
                List.of(),
                false,
                false,
                false,
                false,
                false,
                "",
                false,
                true,
                "",
                "",
                "",
                "");
    }
}
