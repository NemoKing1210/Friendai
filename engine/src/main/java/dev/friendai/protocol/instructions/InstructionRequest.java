package dev.friendai.protocol.instructions;

import dev.friendai.features.instructions.SkillDraft;
import dev.friendai.protocol.ClientRequest;
import dev.friendai.protocol.ProtocolFamily;

public sealed interface InstructionRequest extends ClientRequest
        permits InstructionRequest.Open,
                InstructionRequest.Page,
                InstructionRequest.Detail,
                InstructionRequest.Close,
                InstructionRequest.Create,
                InstructionRequest.Edit,
                InstructionRequest.Duplicate,
                InstructionRequest.Delete,
                InstructionRequest.Enable,
                InstructionRequest.Disable,
                InstructionRequest.Approve,
                InstructionRequest.Reject,
                InstructionRequest.Export,
                InstructionRequest.Import,
                InstructionRequest.DescriptionSet {
    @Override
    default ProtocolFamily family() {
        return ProtocolFamily.INSTRUCTIONS;
    }

    record Open() implements InstructionRequest {
        @Override
        public int operationId() {
            return 1;
        }
    }

    record Page(int page) implements InstructionRequest {
        @Override
        public int operationId() {
            return 2;
        }
    }

    record Detail(String skillId) implements InstructionRequest {
        @Override
        public int operationId() {
            return 3;
        }
    }

    record Close() implements InstructionRequest {
        @Override
        public int operationId() {
            return 4;
        }
    }

    record Create(SkillDraft draft, boolean serverScope) implements InstructionRequest {
        @Override
        public int operationId() {
            return 16;
        }
    }

    record Edit(String skillId, int expectedVersion, SkillDraft draft, boolean serverScope)
            implements InstructionRequest {
        @Override
        public int operationId() {
            return 17;
        }
    }

    record Duplicate(String skillId, int expectedVersion, boolean serverScope) implements InstructionRequest {
        @Override
        public int operationId() {
            return 18;
        }
    }

    record Delete(String skillId, int expectedVersion, boolean serverScope) implements InstructionRequest {
        @Override
        public int operationId() {
            return 19;
        }
    }

    record Enable(String skillId, int expectedVersion, boolean serverScope) implements InstructionRequest {
        @Override
        public int operationId() {
            return 20;
        }
    }

    record Disable(String skillId, int expectedVersion, boolean serverScope) implements InstructionRequest {
        @Override
        public int operationId() {
            return 21;
        }
    }

    record Approve(String skillId, int expectedVersion, boolean serverScope) implements InstructionRequest {
        @Override
        public int operationId() {
            return 22;
        }
    }

    record Reject(String skillId, int expectedVersion, boolean serverScope) implements InstructionRequest {
        @Override
        public int operationId() {
            return 23;
        }
    }

    record Export(String skillId, int expectedVersion, boolean serverScope) implements InstructionRequest {
        @Override
        public int operationId() {
            return 24;
        }
    }

    record Import(SkillDraft draft, boolean serverScope) implements InstructionRequest {
        @Override
        public int operationId() {
            return 25;
        }
    }

    record DescriptionSet(String summary, String details, String languageTag, String address)
            implements InstructionRequest {
        @Override
        public int operationId() {
            return 26;
        }
    }
}
