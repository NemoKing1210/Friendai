package dev.friendai.protocol;

import java.util.Objects;
import java.util.Optional;

public final class ProtocolServerSession {
    public static final int ACCEPT_TIMEOUT_TICKS = 100;

    public enum State {
        PENDING,
        ACCEPTED,
        REJECTED,
        TIMED_OUT,
        DISCONNECTED
    }

    private final long joinedAtTick;
    private final ProtocolCapabilities supported;
    private State state;
    private ProtocolCapabilities negotiated;

    public static ProtocolServerSession start(
            long joinedAtTick, ProtocolCapabilities supported, boolean canSendHello, boolean canReceiveAccept) {
        if (joinedAtTick < 0) throw new IllegalArgumentException("negative server tick");
        return new ProtocolServerSession(joinedAtTick, supported, canSendHello, canReceiveAccept);
    }

    private ProtocolServerSession(
            long joinedAtTick, ProtocolCapabilities supported, boolean canSendHello, boolean canReceiveAccept) {
        this.joinedAtTick = joinedAtTick;
        this.supported = Objects.requireNonNull(supported, "supported");
        this.state = canSendHello && canReceiveAccept ? State.PENDING : State.REJECTED;
    }

    public State state() {
        return state;
    }

    public Optional<ProtocolHello> hello() {
        return state == State.PENDING
                ? Optional.of(new ProtocolHello(ProtocolVersion.CURRENT, supported))
                : Optional.empty();
    }

    public State tick(long serverTick) {
        if (serverTick < 0) throw new IllegalArgumentException("negative server tick");
        if (state == State.PENDING && serverTick - joinedAtTick >= ACCEPT_TIMEOUT_TICKS) state = State.TIMED_OUT;
        return state;
    }

    public State accept(ProtocolAccept accept, long serverTick) {
        if (state != State.PENDING) return state;
        if (tick(serverTick) != State.PENDING) return state;
        if (accept == null || accept.version() != ProtocolVersion.CURRENT) {
            state = State.REJECTED;
            return state;
        }
        negotiated = supported.negotiatedWith(accept.capabilities());
        state = State.ACCEPTED;
        return state;
    }

    public State reject() {
        if (state == State.PENDING) state = State.REJECTED;
        return state;
    }

    public State disconnect() {
        state = State.DISCONNECTED;
        negotiated = null;
        return state;
    }

    public boolean allowsFamily(ProtocolFamily family) {
        return state == State.ACCEPTED && negotiated.supportsFamily(family);
    }

    public boolean allowsAppearance() {
        return state == State.ACCEPTED && negotiated.supports("friendai:appearance");
    }

    public Optional<ProtocolCapabilities> negotiatedCapabilities() {
        return Optional.ofNullable(negotiated);
    }
}
