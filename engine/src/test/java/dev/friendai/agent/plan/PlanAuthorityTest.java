package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanAuthorityTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static Job job(long id, String tool, JobState state, UUID principal) {
        return new Job(
                id,
                7,
                tool,
                new JsonObject(),
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
                0,
                principal);
    }

    @Test
    void theFailedJobsPrincipalOwnsThePlan() {
        List<Job> jobs = List.of(
                job(1, "mine_blocks", JobState.DONE, OWNER),
                job(2, "mine_blocks", JobState.FAILED, GUEST),
                job(3, "go_to", JobState.PENDING, OWNER));
        assertEquals(GUEST, PlanAuthority.principal(jobs, OWNER));
    }

    @Test
    void aPlanWithNoJobsBelongsToTheOwner() {
        assertEquals(OWNER, PlanAuthority.principal(List.of(), OWNER));
    }

    @Test
    void anOwnerHoldsAPlanOfTrustedWork() {
        List<Job> jobs = List.of(job(1, "mine_blocks", JobState.PENDING, OWNER));
        assertTrue(PlanAuthority.holds(
                jobs,
                new Caller(OWNER, "Steve", AccessLevel.OWNER),
                tool -> Map.of("mine_blocks", AccessLevel.TRUSTED).get(tool)));
    }

    @Test
    void aGuestDoesNotHoldAPlanThatNeedsTrustedWork() {
        List<Job> jobs = List.of(job(1, "mine_blocks", JobState.PENDING, GUEST));
        assertFalse(PlanAuthority.holds(
                jobs,
                new Caller(GUEST, "Bob", AccessLevel.GUEST),
                tool -> Map.of("mine_blocks", AccessLevel.TRUSTED).get(tool)));
    }

    @Test
    void aStepWhoseAccessCannotBeCheckedIsNotHeld() {
        List<Job> jobs = List.of(job(1, "no_such_tool", JobState.PENDING, OWNER));
        assertFalse(PlanAuthority.holds(jobs, new Caller(OWNER, "Steve", AccessLevel.OWNER), tool -> null));
    }
}
