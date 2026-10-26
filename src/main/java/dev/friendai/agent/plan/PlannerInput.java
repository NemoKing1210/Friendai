package dev.friendai.agent.plan;

import dev.friendai.agent.goal.Goal;
import java.util.List;
import java.util.Optional;

/**
 * Everything one planner call may see. All fields are plain text built by the caller from the server thread.
 *
 * @param world the companion's surroundings and inventory, as in the prompt's state block
 * @param toolDigest the plannable tools with their schemas, already restricted to what the requester may use
 * @param knowledge what the companion remembers about resources and places, possibly empty
 * @param queueState the current queue, when a replan has to work around it
 * @param failure what went wrong last time, with facts and diagnosis; empty for a first plan
 */
public record PlannerInput(
        Goal goal,
        String world,
        String toolDigest,
        String knowledge,
        Optional<String> queueState,
        List<String> lessons,
        String failure) {
    public PlannerInput {
        world = world == null ? "" : world;
        toolDigest = toolDigest == null ? "" : toolDigest;
        knowledge = knowledge == null ? "" : knowledge;
        queueState = queueState == null ? Optional.empty() : queueState;
        lessons = lessons == null ? List.of() : List.copyOf(lessons);
        failure = failure == null ? "" : failure;
    }
}
