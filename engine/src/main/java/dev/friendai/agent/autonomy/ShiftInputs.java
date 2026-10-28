package dev.friendai.agent.autonomy;

import dev.friendai.agent.turn.AgentSettings.Autonomy;

/**
 * Everything one shift decision looks at, read by the server adapter.
 *
 * @param ceiling the server's {@code agent.autonomy}; {@code PASSIVE} turns free time off
 * @param ownerGoalActive the owner has an active primary goal, which comes first
 * @param shiftPlanExists the current shift's queue plan is still in the queue
 * @param planningInFlight a planner request for the current shift has not answered yet
 * @param ownerWorkQueued the queue holds a job or plan that is not free time's
 * @param lastOwnerActivityMillis the owner's last message or owner-started task
 * @param movementOrder a movement order (come here, wait here, walk there) is running: not work, but a deliberate hold
 */
public record ShiftInputs(
        FreeTime state,
        AutonomyFacts facts,
        AutonomySettings settings,
        Autonomy ceiling,
        boolean ownerGoalActive,
        boolean shiftPlanExists,
        boolean planningInFlight,
        boolean ownerWorkQueued,
        boolean brainThinking,
        long lastOwnerActivityMillis,
        boolean movementOrder) {}
