package dev.friendai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.protocol.control.ControlRequest;
import dev.friendai.protocol.memory.MemoryRequest;
import org.junit.jupiter.api.Test;

final class ProtocolClientRequestGateTest {
    @Test
    void sendsOnlyAfterHelloAndDropsSupersededCorrelatedViews() {
        ProtocolClientRequestGate gate = new ProtocolClientRequestGate(ProtocolCapabilities.friendai(), true);

        assertTrue(gate.createRequest(new ControlRequest.Subscribe()).isEmpty());
        ProtocolClientRequestGate.Ready ready = assertInstanceOf(
                ProtocolClientRequestGate.Ready.class,
                gate.acceptHello(
                        ProtocolBootstrapCodec.encodeHello(new ProtocolHello(1, ProtocolCapabilities.friendai()))));
        assertEquals(ProtocolCapabilities.friendai(), ready.accept().capabilities());

        ProtocolClientRequestGate.EncodedRequest first =
                gate.createRequest(new ControlRequest.Subscribe()).orElseThrow();
        assertEquals(1, first.requestId());
        assertTrue(gate.acceptsView(ProtocolFamily.CONTROL, first.requestId()));
        assertTrue(gate.acceptsView(ProtocolFamily.CONTROL, 0));

        ProtocolClientRequestGate.EncodedRequest second =
                gate.createRequest(new ControlRequest.Stop()).orElseThrow();
        assertEquals(2, second.requestId());
        assertFalse(gate.acceptsView(ProtocolFamily.CONTROL, first.requestId()));
        assertTrue(gate.acceptsView(ProtocolFamily.CONTROL, second.requestId()));
    }

    @Test
    void negotiatedCapabilitiesAndBootstrapFailuresGateClientTraffic() {
        ProtocolClientRequestGate gate = new ProtocolClientRequestGate(ProtocolCapabilities.friendai(), true);
        ProtocolClientRequestGate.Ready ready = assertInstanceOf(
                ProtocolClientRequestGate.Ready.class,
                gate.acceptHello(ProtocolBootstrapCodec.encodeHello(
                        new ProtocolHello(1, ProtocolCapabilities.of("friendai:control")))));

        assertTrue(ready.accept().capabilities().supportsFamily(ProtocolFamily.CONTROL));
        assertFalse(gate.allowsFamily(ProtocolFamily.MEMORY));
        assertTrue(gate.createRequest(new MemoryRequest.Open()).isEmpty());

        ProtocolClientRequestGate incompatible = new ProtocolClientRequestGate(ProtocolCapabilities.friendai(), true);
        ProtocolClientRequestGate.Rejected rejected =
                assertInstanceOf(ProtocolClientRequestGate.Rejected.class, incompatible.acceptHello(new byte[] {2, 0}));
        assertEquals(DecodeResult.Reason.UNSUPPORTED_VERSION, rejected.reason());
        assertTrue(incompatible.createRequest(new ControlRequest.Subscribe()).isEmpty());
    }
}
