package dev.friendai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.protocol.control.ControlRequest;
import dev.friendai.protocol.memory.MemoryRequest;
import org.junit.jupiter.api.Test;

final class ProtocolServerRequestGateTest {
    private static final ProtocolCapabilities SERVER_CAPABILITIES = ProtocolCapabilities.of(
            "friendai:control",
            "friendai:memory",
            "friendai:places",
            "friendai:instructions",
            "friendai:trading",
            "friendai:debug",
            "friendai:speech",
            "friendai:appearance");

    @Test
    void requiresAcceptanceNegotiatedFamilyAndFreshRequestIdsBeforeAdmission() {
        ProtocolServerRequestGate gate = new ProtocolServerRequestGate(10, SERVER_CAPABILITIES, true, true);
        byte[] subscribe = ClientRequestCodec.encode(1, new ControlRequest.Subscribe());

        ProtocolServerRequestGate.Rejected pending =
                assertInstanceOf(ProtocolServerRequestGate.Rejected.class, gate.admit(subscribe));
        assertEquals(ProtocolServerRequestGate.Rejection.SESSION_NOT_ACCEPTED, pending.reason());

        assertEquals(
                ProtocolServerSession.State.ACCEPTED,
                gate.accept(new ProtocolAccept(1, ProtocolCapabilities.of("friendai:control")), 11));
        ProtocolServerRequestGate.Accepted accepted =
                assertInstanceOf(ProtocolServerRequestGate.Accepted.class, gate.admit(subscribe));
        assertEquals(1, accepted.request().requestId());
        assertInstanceOf(ControlRequest.Subscribe.class, accepted.request().request());

        ProtocolServerRequestGate.Rejected duplicate =
                assertInstanceOf(ProtocolServerRequestGate.Rejected.class, gate.admit(subscribe));
        assertEquals(ProtocolServerRequestGate.Rejection.STALE_REQUEST_ID, duplicate.reason());

        byte[] memoryOpen = ClientRequestCodec.encode(2, new MemoryRequest.Open());
        ProtocolServerRequestGate.Rejected unnegotiated =
                assertInstanceOf(ProtocolServerRequestGate.Rejected.class, gate.admit(memoryOpen));
        assertEquals(ProtocolServerRequestGate.Rejection.FAMILY_NOT_NEGOTIATED, unnegotiated.reason());
        assertTrue(gate.allowsFamily(ProtocolFamily.CONTROL));
    }

    @Test
    void timeoutAndMissingBootstrapChannelsNeverAdmitFeatureRequests() {
        byte[] subscribe = ClientRequestCodec.encode(1, new ControlRequest.Subscribe());
        ProtocolServerRequestGate timedOut = new ProtocolServerRequestGate(0, SERVER_CAPABILITIES, true, true);

        assertEquals(ProtocolServerSession.State.TIMED_OUT, timedOut.tick(100));
        ProtocolServerRequestGate.Rejected timeout =
                assertInstanceOf(ProtocolServerRequestGate.Rejected.class, timedOut.admit(subscribe));
        assertEquals(ProtocolServerRequestGate.Rejection.SESSION_NOT_ACCEPTED, timeout.reason());

        ProtocolServerRequestGate missingChannel = new ProtocolServerRequestGate(0, SERVER_CAPABILITIES, false, true);
        assertEquals(ProtocolServerSession.State.REJECTED, missingChannel.state());
        assertInstanceOf(ProtocolServerRequestGate.Rejected.class, missingChannel.admit(subscribe));
    }
}
