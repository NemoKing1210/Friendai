package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.RequestType;
import dev.friendai.memory.MemoryBook;
import dev.friendai.net.CompanionRequestC2SPayload;
import dev.friendai.net.CompanionRequestHandler;
import dev.friendai.net.RateLimiter;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Memory menu requests change only the sender's book, and opening the tab registers the viewer. */
public class MemoryMenuTests {
    static {
        // Tests fire several requests per player back to back.
        CompanionRequestHandler.setLimiter(new RateLimiter(0, System::currentTimeMillis));
    }

    @GameTest(maxTicks = 100)
    public void addAndTrustChangeSendersBook(GameTestHelper h) {
        ServerPlayer a = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer c = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        var server = h.getLevel().getServer();
        String text = "Menu fact " + UUID.randomUUID();
        CompanionRequestHandler.handle(
                a,
                new CompanionRequestC2SPayload(
                        RequestType.MEMORY_ADD, "world", "{\"category\":\"NOTE\",\"text\":\"" + text + "\"}"));
        CompanionRequestHandler.handle(
                a, new CompanionRequestC2SPayload(RequestType.MEMORY_TRUST, c.getUUID().toString(), "true"));
        MemoryBook book = FriendaiServerState.get(server).memory().book(a.getUUID());
        h.assertTrue(
                book.allFacts().stream().anyMatch(f -> f.text().equals(text)),
                "fact should be in the sender's book: " + book.allFacts());
        h.assertTrue(book.isTrusted(c.getUUID()), "the sender should trust C");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void requestsNeverTouchAnotherOwnersBook(GameTestHelper h) {
        ServerPlayer a = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer c = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        var server = h.getLevel().getServer();
        CompanionRequestHandler.handle(
                a, new CompanionRequestC2SPayload(RequestType.MEMORY_TRUST, c.getUUID().toString(), "true"));
        h.assertTrue(
                FriendaiServerState.get(server).memory().book(a.getUUID()).isTrusted(c.getUUID()),
                "A trusts C");
        MemoryBook cBook = FriendaiServerState.get(server).memory().book(c.getUUID());
        h.assertTrue(cBook.trusted().isEmpty(), "C's book must not trust anyone: " + cBook.trusted());
        h.assertTrue(cBook.player(a.getUUID()).isEmpty(), "C's book must not gain a record of A");
        h.assertTrue(cBook.allFacts().isEmpty(), "C's book must gain no facts");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void openRegistersViewer(GameTestHelper h) {
        ServerPlayer a = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var viewers = FriendaiServerState.get(h.getLevel().getServer()).memoryViewers();
        CompanionRequestHandler.handle(a, new CompanionRequestC2SPayload(RequestType.MEMORY_OPEN));
        h.assertTrue(viewers.viewers().contains(a.getUUID()), "MEMORY_OPEN should register the viewer");
        CompanionRequestHandler.handle(a, new CompanionRequestC2SPayload(RequestType.MEMORY_CLOSE));
        h.assertTrue(!viewers.viewers().contains(a.getUUID()), "MEMORY_CLOSE should drop the viewer");
        h.succeed();
    }
}
