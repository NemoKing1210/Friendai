package dev.friendai.client.ui;

import dev.friendai.debug.RequestType;
import dev.friendai.net.CompanionRequestC2SPayload;
import dev.friendai.net.SkinUploadC2SPayload;
import dev.friendai.net.TradeRequestC2SPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class CompanionRequests {
    private CompanionRequests() {}

    /** Whether the server we are connected to has Friendai. */
    public static boolean serverSupported() {
        return ClientPlayNetworking.canSend(CompanionRequestC2SPayload.TYPE);
    }

    private static long skillSequence;
    public static long skills(String operation,String json) {
        if(operation==null||operation.length()>32||json==null||json.length()>32768
                ||!ClientPlayNetworking.canSend(dev.friendai.net.SkillsRequestC2SPayload.TYPE))return -1;
        long id=++skillSequence;
        ClientPlayNetworking.send(new dev.friendai.net.SkillsRequestC2SPayload(operation,json,id));
        return id;
    }
    public static void rename(String name) {
        send(RequestType.RENAME, name, "");
    }

    /** Sends a validated skin; an empty array resets the companion to the default look. */
    public static void uploadSkin(byte[] png, boolean slim) {
        if (skinsSupported()) {
            ClientPlayNetworking.send(new SkinUploadC2SPayload(slim, png));
        }
    }

    public static boolean skinsSupported() {
        return ClientPlayNetworking.canSend(SkinUploadC2SPayload.TYPE);
    }

    public static void send(RequestType type) {
        send(type, "", "");
    }

    public static void send(RequestType type, String a, String b) {
        if (serverSupported()) {
            ClientPlayNetworking.send(new CompanionRequestC2SPayload(type, a, b));
        }
    }

    public static boolean tradingSupported() { return ClientPlayNetworking.canSend(TradeRequestC2SPayload.TYPE); }
    public static void trade(TradeRequestC2SPayload.Action action, String queryId, String item, String components,
            int direction, int page, String trader) {
        if (tradingSupported()) ClientPlayNetworking.send(new TradeRequestC2SPayload(action, queryId, item,
                components, direction, page, trader));
    }
}
