package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanPatchPolicyTest {
    private static QueuedStep step() {
        return new QueuedStep("wait", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of());
    }

    @Test
    void aFlatPendingStepIsPatchable() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long plan = queue.addPlan("p", List.of(step()), PlanOptions.DEFAULT).planId();
        long job = queue.jobsOfPlan(plan).getFirst().id();
        assertTrue(PlanPatchPolicy.reject(queue, job).isEmpty());
    }

    @Test
    void aFlatDoneStepIsRejected() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long plan = queue.addPlan("p", List.of(step()), PlanOptions.DEFAULT).planId();
        long job = queue.jobsOfPlan(plan).getFirst().id();
        queue.finish(job, "done");
        assertTrue(PlanPatchPolicy.reject(queue, job).orElseThrow().contains("terminal"));
    }

    @Test
    void aFlatFailedStepIsPatchable() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long plan = queue.addPlan("p", List.of(step()), PlanOptions.DEFAULT).planId();
        long job = queue.jobsOfPlan(plan).getFirst().id();
        queue.fail(job, "no route");
        assertTrue(PlanPatchPolicy.reject(queue, job).isEmpty());
        var replaced = queue.replace(job, List.of(step()));
        assertTrue(replaced.ok(), replaced.text());
        assertTrue(queue.plans().stream().anyMatch(p -> p.id() == plan), "the plan must stay active");
        assertTrue(
                queue.jobsOfPlan(plan).stream().anyMatch(j -> j.state() == JobState.PENDING),
                "the replacement must be pending");
    }

    @Test
    void aFailedStructuredStepStaysProtected() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        var definition = new PlanDefinition(List.of(new PlanNode.Action(step())));
        long plan = queue.addStructuredPlan("p", definition, PlanOptions.DEFAULT);
        long job = queue.addJob(plan, step()).id();
        queue.fail(job, "no route");
        assertTrue(PlanPatchPolicy.reject(queue, job).orElseThrow().contains("terminal"));
    }

    @Test
    void anActiveStructuredFrameIsNotRewrittenBehindTheCursor() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        var definition = new PlanDefinition(List.of(new PlanNode.Action(step())));
        long plan = queue.addStructuredPlan("p", definition, PlanOptions.DEFAULT);
        long job = queue.addJob(plan, step()).id();
        assertTrue(PlanPatchPolicy.reject(queue, job).orElseThrow().contains("active structured frame"));
    }

    @Test
    void aPlanPreflightAcceptsWhatARepairCanStillApply() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long plan = queue.addPlan("p", List.of(step()), PlanOptions.DEFAULT).planId();
        assertTrue(PlanPatchPolicy.rejectPlan(queue, plan).isEmpty(), "a pending flat step is repairable");

        long failed = queue.addPlan("f", List.of(step()), PlanOptions.DEFAULT).planId();
        queue.fail(queue.jobsOfPlan(failed).getFirst().id(), "no route");
        assertTrue(
                PlanPatchPolicy.rejectPlan(queue, failed).isEmpty(),
                "a failed flat step is still replaced by the legacy repair");

        long structured = queue.addStructuredPlan(
                "s", new PlanDefinition(List.of(new PlanNode.Action(step()))), PlanOptions.DEFAULT);
        assertTrue(
                PlanPatchPolicy.rejectPlan(queue, structured).isEmpty(),
                "a structured plan with no job yet is repaired as a whole tree");
    }

    @Test
    void aPlanPreflightRefusesAStartedFrameAndAFinishedPlan() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long done = queue.addPlan("p", List.of(step()), PlanOptions.DEFAULT).planId();
        queue.finish(queue.jobsOfPlan(done).getFirst().id(), "ok");
        assertTrue(
                PlanPatchPolicy.rejectPlan(queue, done).orElseThrow().contains("terminal"),
                "a proposal for a plan whose only step already succeeded cannot be applied");

        long structured = queue.addStructuredPlan(
                "s", new PlanDefinition(List.of(new PlanNode.Action(step()))), PlanOptions.DEFAULT);
        queue.addJob(structured, step());
        assertTrue(
                PlanPatchPolicy.rejectPlan(queue, structured).orElseThrow().contains("active structured frame"),
                "a started structured frame is never rewritten behind its cursor");

        assertTrue(
                PlanPatchPolicy.rejectPlan(queue, 999).orElseThrow().contains("unknown"), "an unknown plan is refused");
    }

    @Test
    void terminalAndUnknownJobsAreRejected() {
        JobQueue queue = new JobQueue(QueueLimits.DEFAULTS);
        long job = queue.addDirect(step()).id();
        queue.finish(job, "done");
        assertTrue(PlanPatchPolicy.reject(queue, job).orElseThrow().contains("terminal"));
        assertTrue(PlanPatchPolicy.reject(queue, 999).orElseThrow().contains("unknown"));
    }
}
