package dev.friendai.server.net;

import com.google.gson.*;
import dev.friendai.execution.tool.*;
import dev.friendai.features.instructions.*;
import dev.friendai.features.memory.*;
import dev.friendai.protocol.instructions.InstructionRequest;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** The sender selects the owner book. Submitted owner IDs can never redirect a request. */
public final class SkillRequestHandler {
    private SkillRequestHandler() {}

    public static SkillActor actor(ServerPlayer p) {
        return new SkillActor(
                p.getUUID(),
                new Caller(p.getUUID(), p.getName().getString(), AccessLevel.OWNER),
                Operators.isOperator(p));
    }

    public static SkillActionResult apply(ServerRuntime runtime, ServerPlayer p, String operation, String json) {
        if (operation == null || operation.length() > 32 || json == null || json.length() > 32768)
            return SkillActionResult.error();
        try {
            if (operation.equals("description")) {
                var o = JsonParser.parseString(json).getAsJsonObject();
                var description = new CompanionDescription(
                        o.get("summary").getAsString(), o.get("details").getAsString());
                var profile = new OwnerProfile(
                        o.get("language").getAsString(), o.get("address").getAsString());
                var current = runtime.profiles().profile(p.getUUID());
                runtime.profiles().save(p.getUUID(), new CompanionProfile(current.name(), description, profile));
                return SkillActionResult.ok(null);
            }
            if (!runtime.config().skills.enabled
                    && !Set.of("export", "delete", "reject").contains(operation)) return SkillActionResult.error();
            return new SkillMenuActions(runtime.skills().actions(), actor(p)).apply(operation, json);
        } catch (RuntimeException | StackOverflowError e) {
            return SkillActionResult.error();
        }
    }

    public static void handle(AuthenticatedRequestContext context, InstructionRequest request) {
        ServerPlayer p = context.player();
        ServerRuntime runtime = context.runtime();
        long requestId = context.requestId();
        if (request instanceof InstructionRequest.Close) {
            SkillsViewStream.close(p);
            return;
        }
        boolean limited = !runtime.protocolConnections().allowSkillRequest(p.getUUID());
        try {
            if (request instanceof InstructionRequest.Open
                    || request instanceof InstructionRequest.Page
                    || request instanceof InstructionRequest.Detail) {
                int page = request instanceof InstructionRequest.Page pageRequest ? pageRequest.page() : 0;
                String id = request instanceof InstructionRequest.Detail detail ? detail.skillId() : "";
                SkillsViewStream.subscribe(p, page, id);
                if (!limited) SkillsViewStream.sendNow(runtime, p, null, requestId);
                return;
            }
            if (limited) {
                SkillsViewStream.sendNow(runtime, p, SkillActionResult.error(), requestId);
                return;
            }
            ActionRequest action = action(request);
            if (action == null) return;
            SkillActionResult result = apply(runtime, p, action.operation(), action.json());
            SkillsViewStream.sendNow(runtime, p, result, requestId);
        } catch (RuntimeException | StackOverflowError e) {
            SkillsViewStream.sendNow(runtime, p, SkillActionResult.error(), requestId);
        }
    }

    private static ActionRequest action(InstructionRequest request) {
        JsonObject body = new JsonObject();
        String operation;
        if (request instanceof InstructionRequest.Create create) {
            operation = "create";
            body.add("draft", new Gson().toJsonTree(create.draft()));
            body.addProperty("serverScope", create.serverScope());
        } else if (request instanceof InstructionRequest.Edit edit) {
            operation = "edit";
            action(body, edit.skillId(), edit.expectedVersion(), edit.serverScope());
            body.add("draft", new Gson().toJsonTree(edit.draft()));
        } else if (request instanceof InstructionRequest.Duplicate duplicate) {
            operation = "duplicate";
            action(body, duplicate.skillId(), duplicate.expectedVersion(), duplicate.serverScope());
        } else if (request instanceof InstructionRequest.Delete delete) {
            operation = "delete";
            action(body, delete.skillId(), delete.expectedVersion(), delete.serverScope());
        } else if (request instanceof InstructionRequest.Enable enable) {
            operation = "enable";
            action(body, enable.skillId(), enable.expectedVersion(), enable.serverScope());
        } else if (request instanceof InstructionRequest.Disable disable) {
            operation = "disable";
            action(body, disable.skillId(), disable.expectedVersion(), disable.serverScope());
        } else if (request instanceof InstructionRequest.Approve approve) {
            operation = "approve";
            action(body, approve.skillId(), approve.expectedVersion(), approve.serverScope());
        } else if (request instanceof InstructionRequest.Reject reject) {
            operation = "reject";
            action(body, reject.skillId(), reject.expectedVersion(), reject.serverScope());
        } else if (request instanceof InstructionRequest.Export export) {
            operation = "export";
            action(body, export.skillId(), export.expectedVersion(), export.serverScope());
        } else if (request instanceof InstructionRequest.Import imported) {
            operation = "import";
            body.add("draft", new Gson().toJsonTree(imported.draft()));
            body.addProperty("serverScope", imported.serverScope());
        } else if (request instanceof InstructionRequest.DescriptionSet description) {
            operation = "description";
            body.addProperty("summary", description.summary());
            body.addProperty("details", description.details());
            body.addProperty("language", description.languageTag());
            body.addProperty("address", description.address());
        } else {
            return null;
        }
        return new ActionRequest(operation, new Gson().toJson(body));
    }

    private static void action(JsonObject body, String id, int expectedVersion, boolean serverScope) {
        body.addProperty("id", id);
        body.addProperty("expectedVersion", expectedVersion);
        body.addProperty("serverScope", serverScope);
    }

    private record ActionRequest(String operation, String json) {}
}
