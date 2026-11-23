package dev.friendai.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutcomeReviewTest {
    private static OutcomeReview.Observation ok(String tool, String target, String evidence) {
        return new OutcomeReview.Observation(tool, target, true, evidence);
    }

    private static OutcomeReview.Observation failed(String tool, String target, String evidence) {
        return new OutcomeReview.Observation(tool, target, false, evidence);
    }

    @Test
    void aDifferentTargetMakesARepeatNecessary() {
        OutcomeReview.Review review = OutcomeReview.review(List.of(new OutcomeReview.Turn(
                List.of(),
                List.of(
                        ok("mine_blocks", "{\"block\":\"stone\",\"x\":1}", "mined 1"),
                        ok("mine_blocks", "{\"block\":\"stone\",\"x\":2}", "mined 1")))));

        assertEquals(1, review.repeats());
        assertEquals(1, review.necessaryRepeats());
        assertEquals(0, review.redundantRetries());
    }

    @Test
    void theSameFailedCallAtUnchangedEvidenceIsARedundantRetry() {
        OutcomeReview.Review review = OutcomeReview.review(List.of(new OutcomeReview.Turn(
                List.of(),
                List.of(
                        failed("obtain_item", "{\"item\":\"stone_pickaxe\"}", "no reachable stone"),
                        failed("obtain_item", "{\"item\":\"stone_pickaxe\"}", "no reachable stone")))));

        assertEquals(1, review.redundantRetries());
        assertTrue(
                review.redundantKeys().getFirst().contains("stone_pickaxe"),
                review.redundantKeys().toString());
    }

    @Test
    void changedEvidenceMakesAFailedRepeatNecessary() {
        OutcomeReview.Review review = OutcomeReview.review(List.of(new OutcomeReview.Turn(
                List.of(),
                List.of(
                        failed("obtain_item", "{\"item\":\"stone_pickaxe\"}", "no reachable stone"),
                        failed("obtain_item", "{\"item\":\"stone_pickaxe\"}", "stone found at 3 12 4")))));

        assertEquals(1, review.necessaryRepeats());
        assertEquals(0, review.redundantRetries());
    }

    @Test
    void anUnchangedSuccessfulRepeatIsNeitherNecessaryNorARetry() {
        OutcomeReview.Review review = OutcomeReview.review(List.of(new OutcomeReview.Turn(
                List.of(), List.of(ok("get_inventory", "{}", "3 coal"), ok("get_inventory", "{}", "3 coal")))));

        assertEquals(1, review.repeats());
        assertEquals(0, review.necessaryRepeats());
        assertEquals(0, review.redundantRetries());
        assertEquals(1, review.unchangedRepeats());
    }

    @Test
    void anUnfamiliarDeclarativeLineAfterAFailedActionIsAClaim() {
        OutcomeReview.Review review = OutcomeReview.review(List.of(new OutcomeReview.Turn(
                List.of("All squared away, boss."),
                List.of(failed("mine_blocks", "{\"block\":\"stone\"}", "target unreachable")))));

        assertEquals(1, review.failedActionClaims());
    }

    @Test
    void aRefusalOrAQuestionAfterAFailedActionIsNotAClaim() {
        OutcomeReview.Review review = OutcomeReview.review(List.of(
                new OutcomeReview.Turn(
                        List.of("I couldn't reach the stone."),
                        List.of(failed("mine_blocks", "{\"block\":\"stone\"}", "target unreachable"))),
                new OutcomeReview.Turn(
                        List.of("Should I dig from the other side?"),
                        List.of(failed("mine_blocks", "{\"block\":\"stone\"}", "target unreachable"))),
                new OutcomeReview.Turn(
                        List.of("There is no reachable stone from here."),
                        List.of(failed("mine_blocks", "{\"block\":\"stone\"}", "target unreachable")))));

        assertEquals(0, review.failedActionClaims());
    }

    @Test
    void aSuccessfulActionSupportsADeclarativeLine() {
        OutcomeReview.Review review = OutcomeReview.review(List.of(new OutcomeReview.Turn(
                List.of("All squared away, boss."), List.of(ok("mine_blocks", "{\"block\":\"stone\"}", "mined 1")))));

        assertEquals(0, review.failedActionClaims());
    }

    @Test
    void anOrdinaryAcknowledgementIsNotACompletionClaim() {
        OutcomeReview.Review review = OutcomeReview.review(List.of(new OutcomeReview.Turn(
                List.of("On it."), List.of(failed("mine_blocks", "{\"block\":\"stone\"}", "target unreachable")))));

        assertEquals(0, review.failedActionClaims());
    }

    @Test
    void russianRefusalsAndAcknowledgementsAreNotClaims() {
        OutcomeReview.Review review = OutcomeReview.review(List.of(
                new OutcomeReview.Turn(
                        List.of("Не смог достать камень."),
                        List.of(failed("mine_blocks", "{\"block\":\"stone\"}", "target unreachable"))),
                new OutcomeReview.Turn(
                        List.of("Сейчас попробую."),
                        List.of(failed("mine_blocks", "{\"block\":\"stone\"}", "target unreachable")))));

        assertEquals(0, review.failedActionClaims());
    }

    @Test
    void aRussianDeclarativeLineAfterAFailedActionIsAClaim() {
        OutcomeReview.Review review = OutcomeReview.review(List.of(new OutcomeReview.Turn(
                List.of("Готово, три камня."),
                List.of(failed("mine_blocks", "{\"block\":\"stone\"}", "target unreachable")))));

        assertEquals(1, review.failedActionClaims());
    }

    @Test
    void argumentFieldOrderDoesNotCreateANewTarget() {
        var first = JsonParser.parseString("{\"block\":\"stone\",\"count\":1}").getAsJsonObject();
        var second = JsonParser.parseString("{\"count\":1,\"block\":\"stone\"}").getAsJsonObject();

        assertEquals(OutcomeReview.targetKey(first), OutcomeReview.targetKey(second));
    }

    @Test
    void anObserverCallLineKeepsItsTargetButNotItsFormatting() {
        String first = OutcomeReview.callTargetKey("mine_blocks {\"block\":\"stone\",\"count\":1}");
        String second = OutcomeReview.callTargetKey("mine_blocks {\"count\":1,\"block\":\"stone\"}");

        assertEquals(first, second);
        assertTrue(first.contains("stone"), first);
    }

    @Test
    void aCallLineWithoutArgumentsOrWithTruncatedJsonHasNoTarget() {
        assertEquals("", OutcomeReview.callTargetKey("get_inventory"));
        assertEquals("", OutcomeReview.callTargetKey("mine_blocks {\"block\":\"sto…"));
    }

    @Test
    void aDebugLogPrefixIsNotMistakenForAToolName() {
        String projected = "trace=mwsln11h job=7 mine_blocks {\"block\":\"stone\",\"count\":1}";

        assertEquals("mine_blocks", OutcomeReview.logMessage(projected).split(" ")[0]);
        assertTrue(
                OutcomeReview.callTargetKey(OutcomeReview.logMessage(projected)).contains("stone"));
        assertEquals("mine_blocks: ok", OutcomeReview.logMessage("mine_blocks: ok"));
    }

    @Test
    void aMessageThatMerelyLooksLikeAPrefixIsKept() {
        assertEquals("failed", OutcomeReview.logMessage("execution=2 failed"));
        assertEquals("done=true", OutcomeReview.logMessage("done=true"));
        assertEquals("", OutcomeReview.logMessage("trace=abc"));
    }

    @Test
    void nonToolRecordsUnderTheResultKindAreDropped() {
        List<OutcomeReview.Entry> entries = List.of(
                new OutcomeReview.Entry(OutcomeReview.Entry.Kind.CALL, "trace=a mine_blocks {\"block\":\"stone\"}"),
                new OutcomeReview.Entry(OutcomeReview.Entry.Kind.RESULT, "instructions: skills=one; chars=10"),
                new OutcomeReview.Entry(OutcomeReview.Entry.Kind.RESULT, "provider usage: Usage[in=1]"),
                new OutcomeReview.Entry(OutcomeReview.Entry.Kind.RESULT, "mine_blocks: mined 1"),
                new OutcomeReview.Entry(OutcomeReview.Entry.Kind.RESULT, "mine_blocks: stray result"));

        List<OutcomeReview.Observation> observations = OutcomeReview.pair(entries);

        assertEquals(1, observations.size(), observations.toString());
        assertEquals("mine_blocks", observations.getFirst().tool());
        assertTrue(observations.getFirst().ok());
        assertTrue(observations.getFirst().target().contains("stone"));
    }
}
