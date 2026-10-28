package dev.friendai.net;

import com.google.gson.*;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.execution.tool.*;
import dev.friendai.memory.*;
import dev.friendai.skill.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** The sender selects the owner book. Submitted owner IDs can never redirect a request. */
public final class SkillRequestHandler {
    private static final Map<UUID, Long> LAST = new HashMap<>();

    private SkillRequestHandler() {}

    public static void disconnect(UUID id) {
        LAST.remove(id);
    }

    public static SkillActor actor(ServerPlayer p) {
        return new SkillActor(
                p.getUUID(),
                new Caller(p.getUUID(), p.getName().getString(), AccessLevel.OWNER),
                Operators.isOperator(p));
    }

    public static SkillActionResult apply(ServerPlayer p, String operation, String json) {
        if (operation == null || operation.length() > 32 || json == null || json.length() > 32768)
            return SkillActionResult.error();
        var state = FriendaiServerState.get(p.level().getServer());
        try {
            if (operation.equals("description")) {
                var o = JsonParser.parseString(json).getAsJsonObject();
                var description = new CompanionDescription(
                        o.get("summary").getAsString(), o.get("details").getAsString());
                var profile = new OwnerProfile(
                        o.get("language").getAsString(), o.get("address").getAsString());
                state.memory().book(p.getUUID()).setDescription(description);
                state.memory().book(p.getUUID()).setOwnerProfile(profile);
                return SkillActionResult.ok(null);
            }
            if (!state.config().skills.enabled
                    && !Set.of("export", "delete", "reject").contains(operation)) return SkillActionResult.error();
            return new SkillMenuActions(state.skills().actions(), actor(p)).apply(operation, json);
        } catch (RuntimeException | StackOverflowError e) {
            return SkillActionResult.error();
        }
    }

    public static void handle(ServerPlayer p, SkillsRequestC2SPayload payload) {
        if (payload.operation().equals("close")) {
            SkillsViewStream.close(p);
            return;
        }
        long now = p.level().getServer().getTickCount();
        Long old = LAST.get(p.getUUID());
        boolean limited = old != null && now - old < 2;
        if (!limited) LAST.put(p.getUUID(), now);
        try {
            if (Set.of("open", "page", "detail").contains(payload.operation())) {
                var o = JsonParser.parseString(payload.json()).getAsJsonObject();
                SkillsViewStream.subscribe(
                        p,
                        o.has("page") ? o.get("page").getAsInt() : 0,
                        o.has("id") ? o.get("id").getAsString() : "");
                if (!limited) SkillsViewStream.sendNow(p, null, payload.requestId());
                return;
            }
            if (limited) {
                SkillsViewStream.sendNow(p, SkillActionResult.error(), payload.requestId());
                return;
            }
            SkillActionResult result = apply(p, payload.operation(), payload.json());
            SkillsViewStream.sendNow(p, result, payload.requestId());
        } catch (RuntimeException | StackOverflowError e) {
            SkillsViewStream.sendNow(p, SkillActionResult.error(), payload.requestId());
        }
    }
}
