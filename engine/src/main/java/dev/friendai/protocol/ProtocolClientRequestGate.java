package dev.friendai.protocol;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Binds client request creation and correlated response acceptance to one negotiated connection. */
public final class ProtocolClientRequestGate {
    public sealed interface HelloResult permits Ready, Rejected {}

    public record Ready(ProtocolAccept accept) implements HelloResult {
        public Ready {
            Objects.requireNonNull(accept, "accept");
        }
    }

    public record Rejected(DecodeResult.Reason reason) implements HelloResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record EncodedRequest(long requestId, byte[] bytes) {
        public EncodedRequest {
            if (requestId <= 0) throw new IllegalArgumentException("invalid request id");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private final ProtocolClientSession session;
    private final ProtocolRequestSequence sequence = new ProtocolRequestSequence();
    private final Map<ProtocolFamily, Long> latestRequestIds = new EnumMap<>(ProtocolFamily.class);

    public ProtocolClientRequestGate(ProtocolCapabilities supported, boolean canSendAccept) {
        session = new ProtocolClientSession(Objects.requireNonNull(supported, "supported"), canSendAccept);
    }

    public ProtocolClientSession.State state() {
        return session.state();
    }

    public HelloResult acceptHello(byte[] encoded) {
        DecodeResult<ProtocolHello> decoded = ProtocolBootstrapCodec.decodeHello(encoded);
        if (decoded instanceof DecodeResult.Malformed<ProtocolHello> malformed) {
            session.reject();
            return new Rejected(malformed.reason());
        }
        if (decoded instanceof DecodeResult.Unsupported<ProtocolHello> unsupported) {
            session.reject();
            return new Rejected(unsupported.reason());
        }

        ProtocolHello hello = ((DecodeResult.Valid<ProtocolHello>) decoded).value();
        Optional<ProtocolAccept> accepted = session.receiveHello(hello);
        if (accepted.isEmpty()) return new Rejected(DecodeResult.Reason.UNSUPPORTED_VERSION);
        return new Ready(accepted.orElseThrow());
    }

    public boolean allowsFamily(ProtocolFamily family) {
        return session.allowsFamily(family);
    }

    public boolean allowsAppearance() {
        return session.allowsAppearance();
    }

    public Optional<EncodedRequest> createRequest(ClientRequest request) {
        Objects.requireNonNull(request, "request");
        if (!session.allowsFamily(request.family())) return Optional.empty();
        long requestId = sequence.nextRequestId();
        byte[] encoded = ClientRequestCodec.encode(requestId, request);
        latestRequestIds.put(request.family(), requestId);
        return Optional.of(new EncodedRequest(requestId, encoded));
    }

    /** Unsolicited views use request id zero; correlated views must match the latest family request. */
    public boolean acceptsView(ProtocolFamily family, long requestId) {
        if (!session.allowsFamily(family) || requestId < 0) return false;
        if (requestId == 0) return true;
        return Objects.equals(latestRequestIds.get(family), requestId);
    }
}
