package dev.friendai.agent.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoordinationPolicyTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static Job job(long id, String tool, String item, JobState state) {
        JsonObject args = new JsonObject();
        if (item != null) args.addProperty("item", item);
        return new Job(
                id,
                0,
                tool,
                args,
                0,
                false,
                id,
                state,
                List.of(),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                0,
                List.of(),
                "",
                0);
    }

    @Test
    void onlyLiveJobsClaimWork() {
        assertTrue(CompanionClaim.of(A, job(1, "mine_blocks", "minecraft:iron_ore", JobState.DONE))
                .isEmpty());
        assertTrue(CompanionClaim.of(A, job(2, "mine_blocks", "minecraft:iron_ore", JobState.CANCELLED))
                .isEmpty());
        CompanionClaim claim = CompanionClaim.of(A, job(3, "mine_blocks", "minecraft:iron_ore", JobState.PENDING))
                .orElseThrow();
        assertEquals(A, claim.owner());
        assertEquals("minecraft:iron_ore", claim.target());
    }

    @Test
    void aClaimConflictsOnlyWithTheSameToolAndTarget() {
        CompanionClaim claim = CompanionClaim.of(A, job(1, "mine_blocks", "minecraft:iron_ore", JobState.PENDING))
                .orElseThrow();
        assertTrue(claim.conflictsWith(job(2, "mine_blocks", "minecraft:iron_ore", JobState.RUNNING)));
        assertFalse(claim.conflictsWith(job(3, "mine_blocks", "minecraft:gold_ore", JobState.PENDING)));
        assertFalse(claim.conflictsWith(job(4, "craft_item", "minecraft:iron_ore", JobState.PENDING)));
        assertFalse(claim.conflictsWith(job(5, "mine_blocks", "minecraft:iron_ore", JobState.DONE)));
    }

    @Test
    void duplicateFindsTheOtherOwnersLiveJob() {
        Job candidate = job(10, "store_items", "minecraft:cobblestone", JobState.PENDING);
        var duplicate = CompanionClaim.duplicate(
                B,
                List.of(
                        job(11, "store_items", "minecraft:dirt", JobState.PENDING),
                        job(12, "store_items", "minecraft:cobblestone", JobState.RUNNING)),
                candidate);
        assertTrue(duplicate.isPresent());
        assertEquals(12, duplicate.get().jobId());
        assertTrue(CompanionClaim.duplicate(
                        B, List.of(job(13, "store_items", "minecraft:cobblestone", JobState.DONE)), candidate)
                .isEmpty());
    }

    @Test
    void helpRequestsAreBoundedUniqueAndConsumedOnAccept() {
        HelpRequestBook book = new HelpRequestBook();
        HelpRequest request = new HelpRequest(A, B, "bring 4 oak logs", 100);
        assertTrue(book.request(request));
        assertFalse(book.request(new HelpRequest(A, B, "again", 200)), "one open request per pair");
        assertTrue(book.open(A, B, 50).isPresent());
        assertTrue(book.accept(A, B, 50).isPresent());
        assertFalse(book.open(A, B, 50).isPresent(), "accept consumes the request");
        assertFalse(book.accept(A, B, 50).isPresent());

        assertTrue(book.request(new HelpRequest(A, B, "second", 100)));
        assertTrue(book.decline(A, B));
        assertFalse(book.open(A, B, 50).isPresent());
    }

    @Test
    void requestsExpireAndTheBookIsBounded() {
        HelpRequestBook book = new HelpRequestBook();
        UUID third = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        UUID fourth = UUID.fromString("00000000-0000-0000-0000-00000000000d");
        UUID fifth = UUID.fromString("00000000-0000-0000-0000-00000000000e");
        assertTrue(book.request(new HelpRequest(A, B, "one", 10)));
        assertTrue(book.request(new HelpRequest(A, third, "two", 10)));
        assertTrue(book.request(new HelpRequest(A, fourth, "three", 10)));
        assertTrue(book.request(new HelpRequest(A, fifth, "four", 10)));
        assertFalse(book.request(new HelpRequest(B, third, "five", 10)), "cap 4");
        assertTrue(book.open(A, B, 9).isPresent());
        assertTrue(book.open(A, B, 10).isEmpty(), "expiry is inclusive at the tick");
        assertEquals(4, book.expire(10));
        assertEquals(0, book.size());
    }

    @Test
    void aHelpRequestCarriesNoAuthorityAndValidatorRejectsBadInput() {
        HelpRequest request = new HelpRequest(A, B, "bring 4 oak logs", 100);
        assertFalse(request.help().contains("access"));
        assertThrows(() -> new HelpRequest(A, A, "x", 10));
        assertThrows(() -> new HelpRequest(A, B, "", 10));
        assertThrows(() -> new HelpRequest(A, B, "x", 0));
    }

    private static void assertThrows(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
