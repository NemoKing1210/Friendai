package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.protocol.ClientRequest;
import dev.friendai.protocol.control.ControlRequest;
import dev.friendai.protocol.debug.DebugRequest;
import dev.friendai.protocol.memory.MemoryRequest;
import dev.friendai.protocol.places.PlaceRequest;
import dev.friendai.protocol.speech.SpeechRequest;
import dev.friendai.server.net.AuthenticatedRequestContext;
import dev.friendai.server.net.ServerRequestDispatcher;
import dev.friendai.server.net.SkillRequestHandler;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerPlayer;

/** Builds typed operations for existing GameTests without recreating the removed generic network envelope. */
final class TestMenuRequests {
    private static final AtomicLong REQUEST_IDS = new AtomicLong(1);

    enum Action {
        SUBSCRIBE,
        UNSUBSCRIBE,
        SUMMON,
        DISMISS,
        STOP,
        REQUEST_PROMPT,
        SEND_MESSAGE,
        RUN_TOOL,
        MEMORY_OPEN,
        MEMORY_CLOSE,
        MEMORY_ADD,
        MEMORY_EDIT,
        MEMORY_FORGET,
        MEMORY_CLEAR,
        MEMORY_TRUST,
        TELEPORT,
        MOVE_DEBUG_ON,
        MOVE_DEBUG_OFF,
        RENAME,
        PERSONA_SET,
        TRANSCRIPT_CLEAR,
        SET_AUTO_RESPAWN,
        PLACES_OPEN,
        PLACES_CLOSE,
        PLACES_RENAME,
        PLACES_FORGET,
        INVALID,
        SET_CREATIVE_MODE,
        FREE_TIME_TOGGLE,
        SET_FOLLOW_MODE
    }

    private TestMenuRequests() {}

    static void handle(ServerPlayer player, ClientRequest request) {
        handle(player, request, REQUEST_IDS.getAndIncrement());
    }

    static void handle(ServerPlayer player, ClientRequest request, long requestId) {
        var runtime = runtime(player);
        // A test sends several requests inside one tick, which the production throttle would drop after the first;
        // each request here stands for a separate click, so the sender's history starts empty every time.
        runtime.protocolConnections().controlLimiter().forget(player.getUUID());
        var context = new AuthenticatedRequestContext(player, UUID.randomUUID(), requestId, runtime);
        ServerRequestDispatcher.handle(context, request);
    }

    static dev.friendai.features.instructions.SkillActionResult applySkill(
            ServerPlayer player, String operation, String json) {
        return SkillRequestHandler.apply(runtime(player), player, operation, json);
    }

    static dev.friendai.server.runtime.ServerRuntime runtime(ServerPlayer player) {
        return ServerRuntimeRegistry.getOrCreate(player.level().getServer());
    }

    static void voice(ServerPlayer player, String text) {
        handle(player, new SpeechRequest.Transcript(text));
    }

    static ClientRequest menuRequest(Action type) {
        return menuRequest(type, "", "");
    }

    static ClientRequest menuRequest(Action type, String a, String b) {
        return switch (type) {
            case SUBSCRIBE -> new ControlRequest.Subscribe();
            case UNSUBSCRIBE -> new ControlRequest.Unsubscribe();
            case SUMMON -> new ControlRequest.Summon();
            case DISMISS -> new ControlRequest.Dismiss();
            case STOP -> new ControlRequest.Stop();
            case TELEPORT -> new ControlRequest.Teleport();
            case RENAME -> new ControlRequest.Rename(a);
            case SET_AUTO_RESPAWN -> bool(a) == null ? null : new ControlRequest.SetAutoRespawn(bool(a));
            case SET_CREATIVE_MODE -> bool(a) == null ? null : new ControlRequest.SetCreativeMode(bool(a));
            case SET_FOLLOW_MODE -> bool(a) == null ? null : new ControlRequest.SetFollowMode(bool(a));
            case FREE_TIME_TOGGLE -> new ControlRequest.FreeTimeToggle();
            case SEND_MESSAGE -> new ControlRequest.SendChat(a);
            case RUN_TOOL -> new ControlRequest.InvokeTool(a, b);
            case REQUEST_PROMPT -> new DebugRequest.PromptGet();
            case MOVE_DEBUG_ON -> new DebugRequest.MovementStreamSet(true);
            case MOVE_DEBUG_OFF -> new DebugRequest.MovementStreamSet(false);
            case MEMORY_OPEN -> new MemoryRequest.Open();
            case MEMORY_CLOSE -> new MemoryRequest.Close();
            case MEMORY_ADD -> {
                var body = JsonParser.parseString(b).getAsJsonObject();
                yield new MemoryRequest.FactAdd(
                        a,
                        category(body.get("category").getAsString()),
                        body.get("text").getAsString());
            }
            case MEMORY_EDIT -> {
                var body = JsonParser.parseString(b).getAsJsonObject();
                yield new MemoryRequest.FactEdit(
                        Integer.parseInt(a),
                        category(body.get("category").getAsString()),
                        body.get("text").getAsString());
            }
            case MEMORY_FORGET -> new MemoryRequest.FactForget(Integer.parseInt(a));
            case MEMORY_CLEAR -> new MemoryRequest.FactClear(a);
            case MEMORY_TRUST -> bool(b) == null ? null : new MemoryRequest.TrustSet(UUID.fromString(a), bool(b));
            case PERSONA_SET -> new MemoryRequest.PersonaSet(a);
            case TRANSCRIPT_CLEAR -> new MemoryRequest.TranscriptClear();
            case PLACES_OPEN -> new PlaceRequest.Open();
            case PLACES_CLOSE -> new PlaceRequest.Close();
            case PLACES_RENAME -> new PlaceRequest.Rename(a, b);
            case PLACES_FORGET -> new PlaceRequest.Forget(a);
            case INVALID -> null;
        };
    }

    private static int category(String name) {
        return switch (name) {
            case "PROFILE" -> 1;
            case "PREFERENCE" -> 2;
            case "PLACE" -> 3;
            case "PROMISE" -> 4;
            case "NOTE" -> 5;
            default -> throw new IllegalArgumentException("unknown memory category");
        };
    }

    private static Boolean bool(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
    }
}
