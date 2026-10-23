package dev.friendai.brain;

import static dev.friendai.testutil.FakeGateway.call;
import static dev.friendai.testutil.FakeGateway.calls;
import static dev.friendai.testutil.FakeGateway.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.llm.model.LlmException;
import dev.friendai.llm.model.LlmErrorKind;
import dev.friendai.llm.model.LlmResponse;
import dev.friendai.llm.model.Message;
import dev.friendai.llm.model.Role;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import dev.friendai.text.UserMessage;
import dev.friendai.tool.AccessLevel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompanionBrainTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private FakeGateway gateway;
    private FakeToolExecutor tools;
    private RecordingSpeaker speaker;
    private ManualScheduler scheduler;
    private int maxSteps;
    private long cooldown;
    private long guestCooldown;
    private String perceptionText;
    private Supplier<LlmGateway> gatewaySupplier;
    private final Set<UUID> trusted = new HashSet<>();
    private CompanionBrain brain;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        tools = new FakeToolExecutor();
        speaker = new RecordingSpeaker();
        scheduler = new ManualScheduler();
        maxSteps = 8;
        cooldown = 0;
        guestCooldown = 0;
        perceptionText = "state-1";
        gatewaySupplier = () -> gateway;
        brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(maxSteps, 30, cooldown, guestCooldown),
                () -> gatewaySupplier.get(),
                tools,
                () -> perceptionText,
                speaker,
                scheduler,
                AccessResolver.of(OWNER, trusted::contains));
    }

    private static BrainEvent said(String text) {
        return new BrainEvent.PlayerSaid(OWNER, "Steve", text, BrainEvent.Source.CHAT);
    }

    private static BrainEvent guest(String text) {
        return new BrainEvent.PlayerSaid(GUEST, "Bob", text, BrainEvent.Source.CHAT);
    }

    private static Message lastUser(FakeGateway.Request r) {
        for (int i = r.messages().size() - 1; i >= 0; i--) {
            if (r.messages().get(i).role() == Role.USER) {
                return r.messages().get(i);
            }
        }
        throw new AssertionError("no user message");
    }

    @Test
    void textReplyIsSpokenAndStored() {
        gateway.enqueue(text("Hi"));
        brain.post(said("hi"));
        assertEquals(List.of("Hi"), speaker.said);
        assertEquals(List.of(true, false), speaker.thinkingLog);
        brain.post(said("again"));
        gateway.enqueue(text("Again"));
        List<Message> second = gateway.requests.get(gateway.requests.size() - 1).messages();
        assertEquals("[Steve (owner)]: hi", second.get(0).text());
        assertEquals(Role.ASSISTANT, second.get(1).role());
    }

    @Test
    void toolCallLoopsUntilText() {
        gateway.enqueue(calls("", call("c1", "stay")));
        gateway.enqueue(text("Staying"));
        brain.post(said("stay"));
        assertEquals(List.of("stay"), tools.executed);
        assertEquals(2, gateway.requests.size());
        Message toolMsg = gateway.requests.get(1).messages().stream()
                .filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow();
        assertEquals("c1", toolMsg.toolCallId());
        assertEquals(List.of("Staying"), speaker.said);
    }

    @Test
    void executesAllToolCallsInOrder() {
        gateway.enqueue(calls("", call("a", "first"), call("b", "second")));
        gateway.enqueue(text("ok"));
        brain.post(said("go"));
        assertEquals(List.of("first", "second"), tools.executed);
        List<Message> second = gateway.requests.get(1).messages();
        List<String> toolIds = second.stream().filter(m -> m.role() == Role.TOOL).map(Message::toolCallId).toList();
        assertEquals(List.of("a", "b"), toolIds);
    }

    @Test
    void stopsAtMaxToolSteps() {
        maxSteps = 3;
        for (int i = 0; i < 10; i++) {
            gateway.enqueue(calls("", call("c" + i, "stay")));
        }
        brain.post(said("loop"));
        assertEquals(3, gateway.requests.size());
        assertFalse(brain.isThinking());
    }

    @Test
    void eventsDuringThinkingAreBatched() {
        CompletableFuture<LlmResponse> first = gateway.enqueuePending();
        gateway.enqueue(text("both"));
        brain.post(said("one"));
        brain.post(said("two"));
        brain.post(new BrainEvent.TaskFinished(1, "mine_blocks", true, "done"));
        assertEquals(1, gateway.requests.size());
        first.complete(text("first"));
        assertEquals(2, gateway.requests.size());
        assertEquals(
                "[Steve (owner)]: two\n[event] task #1 mine_blocks finished: done",
                lastUser(gateway.requests.get(1)).text());
    }

    @Test
    void turnsAreGroupedByPrincipal() {
        CompletableFuture<LlmResponse> first = gateway.enqueuePending();
        gateway.enqueue(text("owner again"));
        gateway.enqueue(text("hi guest"));
        brain.post(said("one"));
        brain.post(guest("g1"));
        brain.post(said("two"));
        brain.post(new BrainEvent.TaskFinished(1, "mine_blocks", true, "done"));
        first.complete(text("first"));
        assertEquals("[Bob (guest)]: g1", lastUser(gateway.requests.get(1)).text());
        assertEquals(
                "[Steve (owner)]: two\n[event] task #1 mine_blocks finished: done",
                lastUser(gateway.requests.get(2)).text());
    }

    @Test
    void guestTurnUsesGuestAccessAndPrompt() {
        gateway.enqueue(calls("", call("c1", "stay")));
        gateway.enqueue(text("no"));
        brain.post(guest("stay"));
        assertEquals(AccessLevel.GUEST, tools.specAccess.get(0));
        assertEquals(List.of(AccessLevel.GUEST), tools.executeAccess);
        assertTrue(gateway.requests.get(0).system().contains("Current speaker: Bob (guest)."));
    }

    @Test
    void accessIsResolvedWhenTheTurnStarts() {
        CompletableFuture<LlmResponse> first = gateway.enqueuePending();
        gateway.enqueue(text("ok"));
        brain.post(said("one"));
        brain.post(guest("g1"));
        trusted.add(GUEST);
        first.complete(text("first"));
        assertEquals("[Bob (trusted)]: g1", lastUser(gateway.requests.get(1)).text());
    }

    @Test
    void worldNoticeStartsTurnForOwner() {
        gateway.enqueue(text("noted"));
        brain.post(new BrainEvent.WorldNotice("the furnace is done"));
        assertEquals(1, gateway.requests.size());
        assertEquals("[event] the furnace is done", lastUser(gateway.requests.get(0)).text());
        assertEquals(AccessLevel.OWNER, tools.specAccess.get(0));
    }

    @Test
    void taskEventsOnlyTurnActsAsOwner() {
        gateway.enqueue(text("noted"));
        brain.post(new BrainEvent.TaskFinished(1, "stay", true, "ok"));
        assertEquals(AccessLevel.OWNER, tools.specAccess.get(0));
    }

    @Test
    void guestCooldownDropsQuickRepeat() {
        guestCooldown = 5_000;
        gateway.enqueue(text("a"));
        gateway.enqueue(text("c"));
        brain.post(guest("a"));
        brain.post(guest("b"));
        assertEquals(1, gateway.requests.size());
        scheduler.advance(5_000);
        brain.post(guest("c"));
        assertEquals("[Bob (guest)]: c", lastUser(gateway.requests.get(1)).text());
    }

    @Test
    void guestPendingIsCapped() {
        CompletableFuture<LlmResponse> first = gateway.enqueuePending();
        gateway.enqueue(text("ok"));
        brain.post(said("busy"));
        brain.post(guest("g1"));
        brain.post(guest("g2"));
        brain.post(guest("g3"));
        first.complete(text("done"));
        assertEquals(
                "[Bob (guest)]: g1\n[Bob (guest)]: g2", lastUser(gateway.requests.get(1)).text());
    }

    @Test
    void ownerIsNeverThrottled() {
        guestCooldown = 60_000;
        CompletableFuture<LlmResponse> first = gateway.enqueuePending();
        gateway.enqueue(text("ok"));
        brain.post(said("1"));
        brain.post(said("2"));
        brain.post(said("3"));
        brain.post(said("4"));
        first.complete(text("done"));
        assertEquals(
                "[Steve (owner)]: 2\n[Steve (owner)]: 3\n[Steve (owner)]: 4",
                lastUser(gateway.requests.get(1)).text());
    }

    @Test
    void lateResponseAfterCancelIsIgnored() {
        CompletableFuture<LlmResponse> pending = gateway.enqueuePending();
        brain.post(said("hi"));
        brain.cancel();
        pending.complete(calls("late speech", call("c1", "stay")));
        assertTrue(speaker.said.isEmpty());
        assertTrue(tools.executed.isEmpty());
        assertFalse(brain.isThinking());
    }

    @Test
    void errorIsReportedAndBrainRecovers() {
        gateway.enqueueFailure(new LlmException(LlmErrorKind.AUTH, "bad key"));
        brain.post(said("hi"));
        assertEquals(List.of(UserMessage.of("friendai.error.llm.auth")), speaker.errors);
        gateway.enqueue(text("back"));
        brain.post(said("again"));
        assertEquals(List.of("back"), speaker.said);
    }

    @Test
    void cooldownDelaysNextTurn() {
        cooldown = 500;
        gateway.enqueue(text("one"));
        gateway.enqueue(text("two"));
        brain.post(said("a"));
        brain.post(said("b"));
        assertEquals(1, gateway.requests.size());
        scheduler.advance(499);
        assertEquals(1, gateway.requests.size());
        scheduler.advance(1);
        assertEquals(2, gateway.requests.size());
    }

    @Test
    void systemPromptHasFreshPerception() {
        gateway.enqueue(text("a"));
        gateway.enqueue(text("b"));
        brain.post(said("1"));
        perceptionText = "state-2";
        brain.post(said("2"));
        assertTrue(gateway.requests.get(0).system().contains("state-1"));
        assertTrue(gateway.requests.get(1).system().contains("state-2"));
    }

    @Test
    void toolCallsCarryTheTurnCaller() {
        gateway.enqueue(calls("", call("c1", "stay")));
        gateway.enqueue(text("ok"));
        brain.post(guest("stay"));
        assertEquals(List.of(AccessLevel.GUEST), tools.executeAccess);
    }

    @Test
    void memoryBlockFollowsTurnCaller() {
        brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(maxSteps, 30, cooldown, guestCooldown),
                () -> gatewaySupplier.get(),
                tools,
                () -> perceptionText,
                speaker,
                scheduler,
                AccessResolver.of(OWNER, trusted::contains),
                caller -> "MEM:" + caller.access().label());
        gateway.enqueue(text("guest reply"));
        gateway.enqueue(text("owner reply"));
        brain.post(guest("hi"));
        assertTrue(gateway.requests.get(0).system().contains("MEM:guest"));
        brain.post(said("hi"));
        assertTrue(gateway.requests.get(1).system().contains("MEM:owner"));
    }

    @Test
    void gatewaySupplierReadEachTurn() {
        FakeGateway other = new FakeGateway();
        gateway.enqueue(text("a"));
        other.enqueue(text("b"));
        brain.post(said("1"));
        gatewaySupplier = () -> other;
        brain.post(said("2"));
        assertEquals(1, gateway.requests.size());
        assertEquals(1, other.requests.size());
    }
}
