package dev.friendai.server.net;

import com.google.gson.*;
import dev.friendai.execution.tool.*;
import dev.friendai.features.instructions.*;
import dev.friendai.features.memory.*;
import dev.friendai.protocol.instructions.InstructionActions;
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
            if (request instanceof InstructionRequest.Open) {
                SkillsViewStream.subscribe(p, 0, "");
                if (!limited) SkillsViewStream.sendNow(runtime, p, null, requestId);
                return;
            }
            if (request instanceof InstructionRequest.Page page) {
                SkillsViewStream.page(p, page.page());
                if (!limited) SkillsViewStream.sendNow(runtime, p, null, requestId);
                return;
            }
            if (request instanceof InstructionRequest.Detail detail) {
                SkillsViewStream.select(p, detail.skillId());
                if (!limited) SkillsViewStream.sendNow(runtime, p, null, requestId);
                return;
            }
            if (limited) {
                SkillsViewStream.sendNow(runtime, p, SkillActionResult.error(), requestId);
                return;
            }
            if (request instanceof InstructionRequest.DescriptionSet description) {
                saveDescription(
                        runtime,
                        p,
                        new CompanionDescription(description.summary(), description.details()),
                        new OwnerProfile(description.languageTag(), description.address()));
                SkillsViewStream.sendNow(runtime, p, SkillActionResult.ok(null), requestId);
                return;
            }
            SkillAction action = InstructionActions.toAction(request);
            if (action == null) return;
            SkillActionResult result = apply(runtime, p, action);
            SkillsViewStream.sendNow(runtime, p, result, requestId);
        } catch (RuntimeException | StackOverflowError e) {
            SkillsViewStream.sendNow(runtime, p, SkillActionResult.error(), requestId);
        }
    }

    /** Applies a typed action for the authenticated sender; global authority stays in the domain check. */
    public static SkillActionResult apply(ServerRuntime runtime, ServerPlayer p, SkillAction action) {
        if (!runtime.config().skills.enabled && !allowedWhileDisabled(action.operation()))
            return SkillActionResult.error();
        return runtime.skills().actions().apply(actor(p), action);
    }

    private static boolean allowedWhileDisabled(SkillAction.Operation operation) {
        return operation == SkillAction.Operation.EXPORT
                || operation == SkillAction.Operation.DELETE
                || operation == SkillAction.Operation.REJECT;
    }

    private static void saveDescription(
            ServerRuntime runtime, ServerPlayer p, CompanionDescription description, OwnerProfile profile) {
        var current = runtime.profiles().profile(p.getUUID());
        runtime.profiles().save(p.getUUID(), new CompanionProfile(current.name(), description, profile));
    }
}
