package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionManager;
import dev.friendai.foundation.diagnostics.LogKind;
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

        var memory = FriendaiServerState.get(h.getLevel().getServer()).memory();
        h.assertTrue(
                memory.book(guest.getUUID()).isTrusted(owner.getUUID()), "requester trust state should be updated");
        h.assertTrue(memory.book(owner.getUUID()).trusted().isEmpty(), "target player's book must remain unchanged");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void typedSnapshotCodecRemovesDebugFieldsForGuest(GameTestHelper h) {
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        h.assertTrue(!Operators.isOperator(guest), "mock player should be a guest");
        var state = FriendaiServerState.get(h.getLevel().getServer());
        state.sessions().debug().logOf(guest.getUUID()).add(LogKind.ERROR, "private debug detail");

        var snapshot = SnapshotBuilder.build(guest, 0);
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
