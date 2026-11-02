package dev.friendai.protocol.control;

import dev.friendai.protocol.ClientRequest;
import dev.friendai.protocol.ProtocolFamily;

public sealed interface ControlRequest extends ClientRequest
        permits ControlRequest.Subscribe,
                ControlRequest.Unsubscribe,
                ControlRequest.Summon,
                ControlRequest.Dismiss,
                ControlRequest.Stop,
                ControlRequest.Teleport,
                ControlRequest.Rename,
                ControlRequest.SetAutoRespawn,
                ControlRequest.SetCreativeMode,
                ControlRequest.SetFollowMode,
                ControlRequest.FreeTimeToggle,
                ControlRequest.SendChat,
                ControlRequest.InvokeTool,
                ControlRequest.SetPaused,
                ControlRequest.RetryLast,
                ControlRequest.UpdateGoal,
                ControlRequest.QueueEdit {
    @Override
    default ProtocolFamily family() {
        return ProtocolFamily.CONTROL;
    }

    record Subscribe() implements ControlRequest {
        @Override
        public int operationId() {
            return 1;
        }
    }

    record Unsubscribe() implements ControlRequest {
        @Override
        public int operationId() {
            return 2;
        }
    }

    record Summon() implements ControlRequest {
        @Override
        public int operationId() {
            return 3;
        }
    }

    record Dismiss() implements ControlRequest {
        @Override
        public int operationId() {
            return 4;
        }
    }

    record Stop() implements ControlRequest {
        @Override
        public int operationId() {
            return 5;
        }
    }

    record Teleport() implements ControlRequest {
        @Override
        public int operationId() {
            return 6;
        }
    }

    record SetAutoRespawn(boolean enabled) implements ControlRequest {
        @Override
        public int operationId() {
            return 8;
        }
    }

    record SetCreativeMode(boolean enabled) implements ControlRequest {
        @Override
        public int operationId() {
            return 9;
        }
    }

    record SetFollowMode(boolean enabled) implements ControlRequest {
        @Override
        public int operationId() {
            return 10;
        }
    }

    record FreeTimeToggle() implements ControlRequest {
        @Override
        public int operationId() {
            return 11;
        }
    }

    record SendChat(String text) implements ControlRequest {
        @Override
        public int operationId() {
            return 12;
        }
    }

    record Rename(String name) implements ControlRequest {
        @Override
        public int operationId() {
            return 7;
        }
    }

    record InvokeTool(String name, String argumentsJson) implements ControlRequest {
        @Override
        public int operationId() {
            return 13;
        }
    }

    /** The owner holds the companion's work or lets it continue. */
    record SetPaused(boolean paused) implements ControlRequest {
        @Override
        public int operationId() {
            return 14;
        }
    }

    /** The owner edits the wording of a goal from the menu; the criteria and status stay as they are. */
    record UpdateGoal(long goalId, String text) implements ControlRequest {
        @Override
        public int operationId() {
            return 16;
        }

        public UpdateGoal {
            text = text == null ? "" : text;
        }
    }

    /** The owner pauses, resumes or defers one plan or job from the menu. */
    record QueueEdit(String action, long planId, long jobId, long delay, String reason) implements ControlRequest {
        public QueueEdit {
            action = action == null ? "" : action.strip();
            if (!action.equals("pause") && !action.equals("resume") && !action.equals("defer")) {
                throw new IllegalArgumentException("queue edit action");
            }
            if ((planId <= 0) == (jobId <= 0)) throw new IllegalArgumentException("name one plan or one job");
            if (delay < 0 || delay > 72_000) throw new IllegalArgumentException("delay");
            reason = reason == null ? "" : reason.strip();
            if (reason.length() > 160) throw new IllegalArgumentException("reason is too long");
        }

        @Override
        public int operationId() {
            return 17;
        }
    }

    /** The owner asks for the last direct job again, after it failed or was stopped. */
    record RetryLast() implements ControlRequest {
        @Override
        public int operationId() {
            return 15;
        }
    }
}
