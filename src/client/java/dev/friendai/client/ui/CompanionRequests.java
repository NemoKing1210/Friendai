package dev.friendai.client.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.client.FriendaiClient;
import dev.friendai.features.instructions.SkillDraft;
import dev.friendai.presentation.companion.ClientSessionId;
import dev.friendai.protocol.ClientRequest;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.instructions.InstructionRequest;
import dev.friendai.protocol.trading.TradeRequest;
import dev.friendai.transport.payload.SkinUploadC2SPayload;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Creates bounded, versioned requests from the active accepted client connection. */
public final class CompanionRequests {
    private static final Gson GSON = new Gson();

    private CompanionRequests() {}

    /** Whether the accepted peer offers the companion control family. */
    public static boolean serverSupported() {
        ClientSessionId session = FriendaiClient.runtime().sessionId();
        return session != null && FriendaiClient.runtime().allowsProtocol(session, ProtocolFamily.CONTROL);
    }

    public static long send(ClientRequest request) {
        return send(FriendaiClient.runtime().sessionId(), request);
    }

    /** Uses the caller's captured connection id so late voice/UI work cannot cross a reconnect. */
    public static long send(ClientSessionId expectedSession, ClientRequest request) {
        Objects.requireNonNull(request, "request");
        if (expectedSession == null
                || !ClientPlayNetworking.canSend(dev.friendai.transport.payload.ProtocolRequestC2SPayload.TYPE)
                || !FriendaiClient.runtime().allowsProtocol(expectedSession, request.family())) return -1;

        try {
            var encoded = FriendaiClient.runtime().createProtocolRequest(expectedSession, request);
            if (encoded.isEmpty() || !FriendaiClient.runtime().allowsProtocol(expectedSession, request.family()))
                return -1;
            ClientPlayNetworking.send(new dev.friendai.transport.payload.ProtocolRequestC2SPayload(
                    encoded.orElseThrow().bytes()));
            return encoded.orElseThrow().requestId();
        } catch (IllegalArgumentException invalidRequest) {
            return -1;
        }
    }

    public static long skills(InstructionRequest request) {
        return send(request);
    }

    /** Adapts the existing skill editor models to typed instruction operations. */
    public static long skills(String operation, String json) {
        try {
            JsonObject body = json == null || json.isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(json).getAsJsonObject();
            String id = string(body, "id", "");
            int version = integer(body, "expectedVersion", 1);
            boolean serverScope =
                    body.has("serverScope") && body.get("serverScope").getAsBoolean();
            InstructionRequest request = switch (operation) {
                case "open" -> new InstructionRequest.Open();
                case "page" -> new InstructionRequest.Page(integer(body, "page", 0));
                case "detail" -> {
                    if (body.has("page")) send(new InstructionRequest.Page(integer(body, "page", 0)));
                    yield new InstructionRequest.Detail(id);
                }
                case "close" -> new InstructionRequest.Close();
                case "create" -> new InstructionRequest.Create(GSON.fromJson(json, SkillDraft.class), serverScope);
                case "edit" ->
                    new InstructionRequest.Edit(id, version, GSON.fromJson(json, SkillDraft.class), serverScope);
                case "duplicate" -> new InstructionRequest.Duplicate(id, version, serverScope);
                case "delete" -> new InstructionRequest.Delete(id, version, serverScope);
                case "enable" -> new InstructionRequest.Enable(id, version, serverScope);
                case "disable" -> new InstructionRequest.Disable(id, version, serverScope);
                case "approve" -> new InstructionRequest.Approve(id, version, serverScope);
                case "reject" -> new InstructionRequest.Reject(id, version, serverScope);
                case "export" -> new InstructionRequest.Export(id, version, serverScope);
                case "import" -> new InstructionRequest.Import(GSON.fromJson(json, SkillDraft.class), serverScope);
                case "description" ->
                    new InstructionRequest.DescriptionSet(
                            string(body, "summary", ""),
                            string(body, "details", ""),
                            string(body, "language", ""),
                            string(body, "address", ""));
                default -> null;
            };
            return request == null ? -1 : send(request);
        } catch (RuntimeException invalid) {
            return -1;
        }
    }

    /** Trading UI adapter that keeps query UUIDs typed before they reach the frame codec. */
    public static long trade(
            TradeAction action,
            String rawQueryId,
            String itemId,
            String componentsJson,
            int direction,
            int page,
            String rawTraderId) {
        try {
            UUID queryId = UUID.fromString(rawQueryId);
            UUID traderId =
                    rawTraderId == null || rawTraderId.isBlank() ? new UUID(0, 0) : UUID.fromString(rawTraderId);
            TradeRequest.Direction typedDirection =
                    direction == 0 ? TradeRequest.Direction.BUY : TradeRequest.Direction.SELL;
            TradeRequest request = switch (action) {
                case SUBSCRIBE -> new TradeRequest.Subscribe(queryId);
                case QUERY -> new TradeRequest.Query(queryId, itemId, componentsJson, typedDirection, page);
                case CLOSE -> new TradeRequest.Close(queryId);
                case INSPECT -> new TradeRequest.Inspect(queryId, traderId);
                case REFRESH -> new TradeRequest.Refresh(queryId, parseIds(rawTraderId));
                case FORGET -> new TradeRequest.Forget(queryId, traderId, page);
                case PREVIEW -> new TradeRequest.Preview(queryId, componentsJson);
                case SUBMIT -> new TradeRequest.Submit(queryId, componentsJson);
                case WAIT -> new TradeRequest.Wait(queryId, componentsJson);
                case BARTER -> new TradeRequest.Barter(queryId, componentsJson);
            };
            return send(request);
        } catch (RuntimeException invalid) {
            return -1;
        }
    }

    public static void rename(String name) {
        send(new dev.friendai.protocol.control.ControlRequest.Rename(name));
    }

    /** Sends a validated skin; an empty array resets the companion to the default look. */
    public static void uploadSkin(byte[] png, boolean slim) {
        if (skinsSupported()) ClientPlayNetworking.send(new SkinUploadC2SPayload(slim, png));
    }

    public static boolean skinsSupported() {
        ClientSessionId session = FriendaiClient.runtime().sessionId();
        return session != null
                && FriendaiClient.runtime().allowsAppearance(session)
                && ClientPlayNetworking.canSend(SkinUploadC2SPayload.TYPE);
    }

    public static boolean tradingSupported() {
        ClientSessionId session = FriendaiClient.runtime().sessionId();
        return session != null && FriendaiClient.runtime().allowsProtocol(session, ProtocolFamily.TRADING);
    }

    private static String string(JsonObject body, String field, String fallback) {
        return body.has(field) && body.get(field).isJsonPrimitive()
                ? body.get(field).getAsString()
                : fallback;
    }

    private static int integer(JsonObject body, String field, int fallback) {
        return body.has(field) ? body.get(field).getAsInt() : fallback;
    }

    private static java.util.List<UUID> parseIds(String rawIds) {
        if (rawIds == null || rawIds.isBlank()) return java.util.List.of();
        java.util.List<UUID> ids = new ArrayList<>();
        for (String raw : rawIds.split(",")) ids.add(UUID.fromString(raw));
        return java.util.List.copyOf(ids);
    }
}
