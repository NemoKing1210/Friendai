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
                ControlRequest.InvokeTool {
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
}
