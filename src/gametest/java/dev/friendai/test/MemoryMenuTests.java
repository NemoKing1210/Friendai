package dev.friendai.test;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.foundation.limits.RateLimiter;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Memory menu requests change only the sender's book, and opening the tab registers the viewer. */
public class MemoryMenuTests {
    private static void noControlRateLimit(GameTestHelper h) {
        ServerRuntimeRegistry.getOrCreate(h.getLevel().getServer())
                .protocolConnections()
                .setControlLimiterForTest(new RateLimiter(0, System::currentTimeMillis));
    }

    @GameTest(maxTicks = 100)
    public void addAndTrustChangeSendersBook(GameTestHelper h) {
        noControlRateLimit(h);
        ServerPlayer a = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer c = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        var server = h.getLevel().getServer();
        String text = "Menu fact " + UUID.randomUUID();
        TestMenuRequests.handle(
                a,
                TestMenuRequests.menuRequest(
                        TestMenuRequests.Action.MEMORY_ADD,
                        "world",
                        "{\"category\":\"NOTE\",\"text\":\"" + text + "\"}"));
        TestMenuRequests.handle(
                a,
                TestMenuRequests.menuRequest(
                        TestMenuRequests.Action.MEMORY_TRUST, c.getUUID().toString(), "true"));
        MemoryBook book = GameTestSupport.serverState(server).memory().book(a.getUUID());
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
        TestMenuRequests.handle(
                a,
                TestMenuRequests.menuRequest(
                        TestMenuRequests.Action.MEMORY_TRUST, c.getUUID().toString(), "true"));
        h.assertTrue(
                GameTestSupport.serverState(server).memory().book(a.getUUID()).isTrusted(c.getUUID()), "A trusts C");
        MemoryBook cBook = GameTestSupport.serverState(server).memory().book(c.getUUID());
        h.assertTrue(cBook.trusted().isEmpty(), "C's book must not trust anyone: " + cBook.trusted());
        h.assertTrue(cBook.player(a.getUUID()).isEmpty(), "C's book must not gain a record of A");
        h.assertTrue(cBook.allFacts().isEmpty(), "C's book must gain no facts");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void personaSetChangesSendersBookAndPrompt(GameTestHelper h) {
        ServerPlayer a = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        FriendaiServerState state = GameTestSupport.serverState(server);
        CompanionManager.get(server).summon(a);
        String text = "Persona " + UUID.randomUUID();
        TestMenuRequests.handle(a, TestMenuRequests.menuRequest(TestMenuRequests.Action.PERSONA_SET, text, ""));
        h.assertTrue(
                state.profiles().profile(a.getUUID()).description().summary().equals(text),
                "the persona should be in the sender's profile");
        var prompt = state.sessions().currentPrompt(a.getUUID());
        h.assertTrue(prompt.isPresent() && prompt.get().contains(text), "prompt: " + prompt);
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void blankPersonaRestoresServerDefaultInPrompt(GameTestHelper h) {
        noControlRateLimit(h);
        ServerPlayer a = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        FriendaiServerState state = GameTestSupport.serverState(server);
        CompanionManager.get(server).summon(a);
        String text = "Persona " + UUID.randomUUID();
        TestMenuRequests.handle(a, TestMenuRequests.menuRequest(TestMenuRequests.Action.PERSONA_SET, text, ""));
        TestMenuRequests.handle(a, TestMenuRequests.menuRequest(TestMenuRequests.Action.PERSONA_SET, "  ", ""));
        h.assertTrue(
                state.profiles().profile(a.getUUID()).description().summary().isEmpty(),
                "the persona should be cleared");
        String prompt = state.sessions().currentPrompt(a.getUUID()).orElse("");
        h.assertTrue(prompt.contains(state.config().companion.description), "prompt: " + prompt);
        h.assertTrue(!prompt.contains(text), "the custom text should be gone: " + prompt);
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void badPersonaIsRefused(GameTestHelper h) {
        noControlRateLimit(h);
        ServerPlayer a = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var state = GameTestSupport.serverState(h.getLevel().getServer());
        String good = "Persona " + UUID.randomUUID();
        TestMenuRequests.handle(a, TestMenuRequests.menuRequest(TestMenuRequests.Action.PERSONA_SET, good, ""));
        TestMenuRequests.handle(a, TestMenuRequests.menuRequest(TestMenuRequests.Action.PERSONA_SET, "§bad", ""));
        h.assertTrue(
                state.profiles().profile(a.getUUID()).description().summary().equals(good),
                "a refused text must keep the old persona");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void personaRequestNeverTouchesAnotherOwnersBook(GameTestHelper h) {
        ServerPlayer a = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer c = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        var state = GameTestSupport.serverState(h.getLevel().getServer());
        String text = "Persona " + UUID.randomUUID();
        TestMenuRequests.handle(a, TestMenuRequests.menuRequest(TestMenuRequests.Action.PERSONA_SET, text, ""));
        h.assertTrue(
                state.profiles().profile(a.getUUID()).description().summary().equals(text), "A's profile holds it");
        h.assertTrue(
                state.profiles().profile(c.getUUID()).description().summary().isEmpty(),
                "C's profile must stay without one");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void openRegistersViewer(GameTestHelper h) {
        noControlRateLimit(h);
        ServerPlayer a = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var viewers = GameTestSupport.serverState(h.getLevel().getServer()).memoryViewers();
        TestMenuRequests.handle(a, TestMenuRequests.menuRequest(TestMenuRequests.Action.MEMORY_OPEN));
        h.assertTrue(viewers.viewers().contains(a.getUUID()), "MEMORY_OPEN should register the viewer");
        TestMenuRequests.handle(a, TestMenuRequests.menuRequest(TestMenuRequests.Action.MEMORY_CLOSE));
        h.assertTrue(!viewers.viewers().contains(a.getUUID()), "MEMORY_CLOSE should drop the viewer");
        h.succeed();
    }
}
