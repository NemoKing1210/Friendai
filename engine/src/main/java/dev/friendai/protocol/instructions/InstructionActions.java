package dev.friendai.protocol.instructions;

import dev.friendai.features.instructions.SkillAction;
import dev.friendai.features.instructions.SkillDraft;
import dev.friendai.features.instructions.SkillTransfer;

/**
 * Maps an authenticated typed instruction request to the shared domain action.
 *
 * <p>Subscription and description requests are not skill mutations and return {@code null}; the caller
 * handles them separately. The actor is never derived here: it comes from the authenticated connection.
 */
public final class InstructionActions {
    private InstructionActions() {}

    public static SkillAction toAction(InstructionRequest request) {
        if (request instanceof InstructionRequest.Create create) {
            return draftAction(SkillAction.Operation.CREATE, "", 0, create.draft(), create.serverScope(), "");
        }
        if (request instanceof InstructionRequest.Edit edit) {
            return draftAction(
                    SkillAction.Operation.EDIT,
                    edit.skillId(),
                    edit.expectedVersion(),
                    edit.draft(),
                    edit.serverScope(),
                    "");
        }
        if (request instanceof InstructionRequest.Duplicate duplicate) {
            return versioned(
                    SkillAction.Operation.DUPLICATE,
                    duplicate.skillId(),
                    duplicate.expectedVersion(),
                    duplicate.serverScope());
        }
        if (request instanceof InstructionRequest.Delete delete) {
            return versioned(
                    SkillAction.Operation.DELETE, delete.skillId(), delete.expectedVersion(), delete.serverScope());
        }
        if (request instanceof InstructionRequest.Enable enable) {
            return new SkillAction(
                    SkillAction.Operation.TOGGLE,
                    enable.skillId(),
                    enable.expectedVersion(),
                    null,
                    enable.serverScope(),
                    "enable");
        }
        if (request instanceof InstructionRequest.Disable disable) {
            return new SkillAction(
                    SkillAction.Operation.TOGGLE,
                    disable.skillId(),
                    disable.expectedVersion(),
                    null,
                    disable.serverScope(),
                    "disable");
        }
        if (request instanceof InstructionRequest.Approve approve) {
            return versioned(
                    SkillAction.Operation.APPROVE, approve.skillId(), approve.expectedVersion(), approve.serverScope());
        }
        if (request instanceof InstructionRequest.Reject reject) {
            return versioned(
                    SkillAction.Operation.REJECT, reject.skillId(), reject.expectedVersion(), reject.serverScope());
        }
        if (request instanceof InstructionRequest.Export export) {
            return versioned(
                    SkillAction.Operation.EXPORT, export.skillId(), export.expectedVersion(), export.serverScope());
        }
        if (request instanceof InstructionRequest.Import imported) {
            SkillDraft draft = imported.draft();
            return draftAction(
                    SkillAction.Operation.IMPORT, "", 0, draft, imported.serverScope(), SkillTransfer.write(draft));
        }
        return null;
    }

    private static SkillAction draftAction(
            SkillAction.Operation operation,
            String id,
            int expectedVersion,
            SkillDraft draft,
            boolean server,
            String text) {
        return new SkillAction(operation, id, expectedVersion, draft, server, text);
    }

    private static SkillAction versioned(
            SkillAction.Operation operation, String id, int expectedVersion, boolean server) {
        return new SkillAction(operation, id, expectedVersion, null, server, "");
    }
}
