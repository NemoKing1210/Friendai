package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.autonomy.NeedKind;
import dev.friendai.agent.autonomy.Shift;
import dev.friendai.agent.autonomy.ShiftRecord;
import dev.friendai.agent.autonomy.ShiftStopWhen;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompanionSpeechPolicyTest {
    @Test
    void outcomeOnlyTurnStaysSilentButAnOwedReplyCanSpeak() {
        BrainEvent.SkillOutcome outcome = new BrainEvent.SkillOutcome(
                "shift:1", dev.friendai.foundation.teaching.TeachingContext.OutcomeKind.SHIFT_FAILURE, "failed");

        assertFalse(CompanionSpeechPolicy.maySpeak(List.of(outcome), false));
        assertTrue(CompanionSpeechPolicy.maySpeak(List.of(outcome), true));
        assertTrue(CompanionSpeechPolicy.maySpeak(List.of(new BrainEvent.IdleNudge()), false));
    }

    @Test
    void cancelledTaskKeepsItsTraceWithoutStartingAModelTurn() {
        BrainEvent event = new BrainEvent.TaskCancelled(3, "mine_blocks", "stopped");
        assertFalse(CompanionSpeechPolicy.needsModelTurn(event));
        BrainEvent failure = new BrainEvent.TaskFinished(3, "mine_blocks", false, "blocked");
        assertTrue(CompanionSpeechPolicy.needsModelTurn(failure));
    }

    @Test
    void authorityQuestionsUseACooldownAndDoNotReopenOnClockRollback() {
        BrainEvent.PlanBlocked blocker = new BrainEvent.PlanBlocked(8, "shelter", 2, "build", "missing materials", 3);

        assertFalse(CompanionSpeechPolicy.mayAskAuthorityQuestion(blocker, 1_000, 1));
        assertTrue(CompanionSpeechPolicy.mayAskAuthorityQuestion(
                blocker, 1 + CompanionSpeechPolicy.AUTHORITY_QUESTION_COOLDOWN_MILLIS, 1));
        assertFalse(CompanionSpeechPolicy.mayAskAuthorityQuestion(blocker, 0, 1));
    }

    @Test
    void shiftEndReportIsOnlyForANewFailure() {
        Shift current = shift(2, NeedKind.GEAR);
        List<ShiftRecord> history = List.of(
                record(2, NeedKind.GEAR, false, "missing scaffold"),
                record(1, NeedKind.GEAR, false, "  MISSING   scaffold "));

        assertFalse(CompanionSpeechPolicy.shouldReportShiftFailure(
                current, "missing scaffold", ShiftStopWhen.DONE, history));
        assertTrue(
                CompanionSpeechPolicy.shouldReportShiftFailure(current, "no food nearby", ShiftStopWhen.DONE, history));
        assertFalse(CompanionSpeechPolicy.shouldReportShiftFailure(
                shift(3, NeedKind.FOOD), "missing scaffold", ShiftStopWhen.DONE, history));
        assertTrue(CompanionSpeechPolicy.shouldReportShiftFailure(
                current,
                "missing scaffold",
                ShiftStopWhen.DONE,
                List.of(record(2, NeedKind.GEAR, true, "missing scaffold"))));
        assertFalse(CompanionSpeechPolicy.shouldReportShiftFailure(
                shift(3, NeedKind.FOOD), "the owner came back", ShiftStopWhen.OWNER_BACK, history));
    }

    private static Shift shift(long id, NeedKind need) {
        return new Shift(id, "activity", List.of(), "", 10, 0, 100, false, need);
    }

    private static ShiftRecord record(long id, NeedKind need, boolean success, String summary) {
        return new ShiftRecord(id, "activity", need, success, summary, id);
    }
}
