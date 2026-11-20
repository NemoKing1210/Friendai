package dev.friendai.test;

import dev.friendai.entity.CompanionManager;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.limits.RateLimiter;
import dev.friendai.protocol.ClientRequestCodec;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolCapabilities;
import dev.friendai.protocol.ProtocolServerRequestGate;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.control.CompanionSnapshotProtocolCodec;
import dev.friendai.protocol.control.CompanionSnapshotView;
import dev.friendai.protocol.control.ControlRequest;
import dev.friendai.protocol.memory.MemoryRequest;
import dev.friendai.server.net.AuthenticatedRequestContext;
import dev.friendai.server.net.Operators;
import dev.friendai.server.net.ServerRequestDispatcher;
import dev.friendai.server.net.SnapshotBuilder;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import dev.friendai.transport.protocol.CompanionSnapshotProtocolAdapter;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Exercises connection admission and typed actor scoping at the server adapter boundary. */
public class ProtocolAccessTests {
    @GameTest(maxTicks = 100)
    public void requestBeforeHandshakeCannotSummon(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ProtocolServerRequestGate gate = new ProtocolServerRequestGate(
                h.getLevel().getServer().getTickCount(), ProtocolCapabilities.friendai(), true, true);
        byte[] summon = ClientRequestCodec.encode(1, new ControlRequest.Summon());

        ProtocolServerRequestGate.Admission admission = gate.admit(summon);
        h.assertTrue(
                admission instanceof ProtocolServerRequestGate.Rejected rejected
                        && rejected.reason() == ProtocolServerRequestGate.Rejection.SESSION_NOT_ACCEPTED,
                "a pending connection must reject feature requests");
        h.assertTrue(
                CompanionManager.get(h.getLevel().getServer())
                        .find(player.getUUID())
                        .isEmpty(),
                "a pre-handshake request must not summon a companion");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void typedMemoryRequestUsesAuthenticatedSenderForItsBook(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        UUID connectionSession = UUID.randomUUID();
        var context = new AuthenticatedRequestContext(
                guest,
                connectionSession,
                1,
                ServerRuntimeRegistry.getOrCreate(h.getLevel().getServer()));

        ServerRequestDispatcher.handle(context, new MemoryRequest.TrustSet(owner.getUUID(), true));

        var memory = GameTestSupport.runtime(h.getLevel().getServer()).memory();
        h.assertTrue(
                memory.book(guest.getUUID()).isTrusted(owner.getUUID()), "requester trust state should be updated");
        h.assertTrue(memory.book(owner.getUUID()).trusted().isEmpty(), "target player's book must remain unchanged");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void sameTickMenuReopenStillRegistersWhenTheSnapshotIsCoalesced(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var runtime = GameTestSupport.runtime(h.getLevel().getServer());
        var debug = runtime.sessions().debug();
        try {
            TestMenuRequests.handle(player, new ControlRequest.Subscribe(), 1);
            h.assertTrue(debug.subscribers().contains(player.getUUID()), "the first open subscribes");
            TestMenuRequests.handle(player, new ControlRequest.Unsubscribe(), 2);
            h.assertFalse(debug.subscribers().contains(player.getUUID()), "the close removes the first subscription");
            TestMenuRequests.handle(player, new ControlRequest.Subscribe(), 3);
            h.assertTrue(debug.subscribers().contains(player.getUUID()), "a coalesced reopen still subscribes");
        } finally {
            runtime.debugStream().closeMenu(runtime, player.getUUID());
        }
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void throttledMenuOpenStillRegistersTheSubscription(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var runtime = GameTestSupport.runtime(h.getLevel().getServer());
        var connections = runtime.protocolConnections();
        RateLimiter original = connections.controlLimiter();
        RateLimiter exhausted = new RateLimiter(300, () -> 0);
        connections.setControlLimiterForTest(exhausted);
        try {
            h.assertTrue(exhausted.tryAcquire(player.getUUID()), "the first request acquires the throttle");
            var context = new AuthenticatedRequestContext(player, UUID.randomUUID(), 11, runtime);
            ServerRequestDispatcher.handle(context, new ControlRequest.Subscribe());
            h.assertTrue(
                    runtime.sessions().debug().subscribers().contains(player.getUUID()),
                    "throttle defers only delivery");
        } finally {
            connections.setControlLimiterForTest(original);
            runtime.debugStream().closeMenu(runtime, player.getUUID());
        }
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void typedSnapshotCodecRemovesDebugFieldsForGuest(GameTestHelper h) {
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        h.assertTrue(!Operators.isOperator(guest), "mock player should be a guest");
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        state.sessions().debug().logOf(guest.getUUID()).add(LogKind.ERROR, "private debug detail");

        var runtime = ServerRuntimeRegistry.getOrCreate(h.getLevel().getServer());
        var snapshot = SnapshotBuilder.build(runtime, guest, 0);
        byte[] encoded = CompanionSnapshotProtocolCodec.encodeForRecipient(
                CompanionSnapshotProtocolAdapter.toProtocol(snapshot), false, 1);
        DecodeResult<ProtocolView<CompanionSnapshotView>> decoded = CompanionSnapshotProtocolCodec.decode(encoded);

        h.assertTrue(decoded instanceof DecodeResult.Valid<?>, "snapshot frame should decode");
        if (decoded instanceof DecodeResult.Valid<ProtocolView<CompanionSnapshotView>> valid) {
            var view = valid.value().value();
            h.assertTrue(!view.operator(), "guest view must not claim operator access");
            h.assertTrue(view.newLog().isEmpty(), "guest view must omit operator log entries");
            h.assertTrue(view.perception().isEmpty(), "guest view must omit detailed perception");
            h.assertTrue(view.toolNames().isEmpty(), "guest view must omit operator tool names");
        }
        h.succeed();
    }
}
