package dev.friedai.client.ui;

import dev.friedai.debug.RequestType;
import dev.friedai.net.CompanionRequestC2SPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class CompanionRequests {
    private CompanionRequests() {}

    /** Whether the server we are connected to has Friendai. */
    public static boolean serverSupported() {
        return ClientPlayNetworking.canSend(CompanionRequestC2SPayload.TYPE);
    }

    public static void send(RequestType type) {
        send(type, "", "");
    }

    public static void send(RequestType type, String a, String b) {
        if (serverSupported()) {
            ClientPlayNetworking.send(new CompanionRequestC2SPayload(type, a, b));
        }
    }
}
