package dev.friendai.brain;

import static dev.friendai.testutil.FakeGateway.call;
import static dev.friendai.testutil.FakeGateway.calls;
import static dev.friendai.testutil.FakeGateway.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.llm.model.LlmException;
import dev.friendai.llm.model.LlmErrorKind;
import dev.friendai.llm.model.LlmResponse;
import dev.friendai.llm.model.Message;
import dev.friendai.llm.model.Role;
import dev.friendai.llm.model.ToolSpec;
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
    void aMessageFromFarAwayIsAnsweredOnTheSpot() {
        perceptionText = "Owner: 40.0 blocks away, out of sight (I can't tell where they stand or look)";
        gateway.enqueue(text("Doing fine, thanks"));
        brain.post(said("how are you?"));
        assertEquals(List.of("Doing fine, thanks"), speaker.said);
        assertTrue(tools.executed.isEmpty(), "a plain message must not start a task such as a walk");
        assertTrue(gateway.requests.get(0).system().contains("Answering is never a reason to move"));
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
    void perceptionReceivesTheTurnCaller() {
        java.util.List<dev.friendai.tool.Caller> seen = new java.util.ArrayList<>();
        brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(maxSteps, 30, cooldown, guestCooldown),
                () -> gatewaySupplier.get(),
                tools,
                new PerceptionSource() {
                    @Override
                    public String describe() {
                        return "no-caller";
                    }

                    @Override
                    public String describe(dev.friendai.tool.Caller caller) {
                        seen.add(caller);
                        return "VIEW-FOR-" + caller.name();
                    }
                },
                speaker,
                scheduler,
                AccessResolver.of(OWNER, trusted::contains));
        trusted.add(GUEST);
        gateway.enqueue(text("ok"));
        brain.post(guest("look"));
        assertFalse(seen.isEmpty());
        assertEquals(new dev.friendai.tool.Caller(GUEST, "Bob", AccessLevel.TRUSTED), seen.get(seen.size() - 1));
        assertTrue(gateway.requests.get(0).system().contains("VIEW-FOR-Bob"));
        assertFalse(gateway.requests.get(0).system().contains("no-caller"));
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

    @Test
    void replyIsAddressedToTheTurnCaller() {
        gateway.enqueue(text("Hi"));
        brain.post(said("hi"));
        assertEquals(List.of(OWNER), speaker.addressees);
    }

    @Test
    void guestReplyIsAddressedToTheGuest() {
        gateway.enqueue(text("Hello"));
        brain.post(guest("hi"));
        assertEquals(List.of(GUEST), speaker.addressees);
    }

    @Test
    void eventTurnCanStaySilent() {
        gateway.enqueue(text(ReplyGuard.SILENT));
        brain.post(new BrainEvent.TaskFinished(1, "mine_blocks", true, "mined 3 logs"));
        assertTrue(speaker.said.isEmpty());
        assertTrue(gateway.requests.get(0).system().contains("This turn: task events or world notices"));
        assertFalse(brain.isThinking());
    }

    @Test
    void silentTurnIsRememberedAsSentinel() {
        gateway.enqueue(text(ReplyGuard.SILENT));
        brain.post(new BrainEvent.TaskFinished(1, "mine_blocks", true, "mined 3 logs"));
        gateway.enqueue(text("Hi"));
        brain.post(said("hello"));
        Message assistant = gateway.requests.get(1).messages().stream()
                .filter(m -> m.role() == Role.ASSISTANT)
                .findFirst()
                .orElseThrow();
        assertEquals(ReplyGuard.SILENT, assistant.text());
    }

    @Test
    void playerTurnKeepsCoordinatesWhenAsked() {
        gateway.enqueue(text("The hive is at 1279 68 358."));
        brain.post(said("where is the hive?"));
        assertEquals(List.of("The hive is at 1279 68 358."), speaker.said);
    }

    @Test
    void playerTurnWithoutQuestionStripsCoordinates() {
        gateway.enqueue(text("The hive is nearby (1279 68 358)."));
        brain.post(said("hello"));
        assertEquals(List.of("The hive is nearby."), speaker.said);
    }

    @Test
    void guestQuestionDoesNotUnlockOwnerEventCoordinates() {
        gateway.enqueue(text("I don't know."));
        brain.post(guest("where is the hive?"));
        gateway.enqueue(text("The hive is nearby (1279 68 358)."));
        brain.post(new BrainEvent.TaskFinished(1, "explore", true, "found the hive"));
        assertEquals(List.of("I don't know.", "The hive is nearby."), speaker.said);
    }

    @Test
    void eventTurnBeforeAnyPlayerMessageHasNoCoordinates() {
        gateway.enqueue(text("A hive appeared at 1279 68 358."));
        brain.post(new BrainEvent.WorldNotice("a hive appeared"));
        assertEquals(List.of("A hive appeared."), speaker.said);
    }

    @Test
    void ownerWhereQuestionUnlocksCoordinatesInALaterEventTurn() {
        gateway.enqueue(text("ok"));
        brain.post(said("where is the hive?"));
        gateway.enqueue(text("The hive is at 1279 68 358."));
        brain.post(new BrainEvent.TaskFinished(1, "explore", true, "found the hive"));
        assertEquals(List.of("ok", "The hive is at 1279 68 358."), speaker.said);
    }

    @Test
    void ownerWhereQuestionExpiresAfterTheWindow() {
        gateway.enqueue(text("ok"));
        brain.post(said("where is the hive?"));
        scheduler.advance(121_000);
        gateway.enqueue(text("The hive is at 1279 68 358."));
        brain.post(new BrainEvent.TaskFinished(1, "explore", true, "found the hive"));
        assertEquals(List.of("ok", "The hive is."), speaker.said);
    }

    @Test
    void directAnswerIsNeverSuppressedAsARepeat() {
        gateway.enqueue(text("Done."));
        brain.post(said("do it"));
        gateway.enqueue(text("Done."));
        brain.post(said("do it again"));
        assertEquals(List.of("Done.", "Done."), speaker.said);
    }

    @Test
    void repeatedEventReplyIsSpokenOnce() {
        gateway.enqueue(text("Done."));
        brain.post(new BrainEvent.TaskFinished(1, "mine_blocks", true, "done"));
        gateway.enqueue(text("Done."));
        brain.post(new BrainEvent.TaskFinished(2, "mine_blocks", true, "done"));
        assertEquals(List.of("Done."), speaker.said);
    }

    @Test
    void eventReplyThatRestatesTheLastAnswerInOtherWordsIsDropped() {
        gateway.enqueue(text("Даю тебе три жареные котлеты — три оставлю себе."));
        brain.post(said("дай мне мяса"));
        gateway.enqueue(text("Держи, три котлеты твои."));
        brain.post(new BrainEvent.TaskFinished(1, "give_items", true, "gave 3 minecraft:cooked_porkchop"));
        assertEquals(List.of("Даю тебе три жареные котлеты — три оставлю себе."), speaker.said);
    }

    @Test
    void eventReplyWithNewsIsStillSpokenAfterAParaphrase() {
        gateway.enqueue(text("Жарю пять котлет на досках."));
        brain.post(said("приготовь мяса"));
        gateway.enqueue(text("Топливо закончилось, пять котлет остались в печи."));
        brain.post(new BrainEvent.TaskFinished(1, "smelt_items", false, "ran out of fuel"));
        assertEquals(
                List.of("Жарю пять котлет на досках.", "Топливо закончилось, пять котлет остались в печи."),
                speaker.said);
    }

    @Test
    void eventRepeatIsAllowedAfterTheWindow() {
        gateway.enqueue(text("Done."));
        brain.post(new BrainEvent.TaskFinished(1, "mine_blocks", true, "done"));
        scheduler.advance(121_000);
        gateway.enqueue(text("Done."));
        brain.post(new BrainEvent.TaskFinished(2, "mine_blocks", true, "done"));
        assertEquals(List.of("Done.", "Done."), speaker.said);
    }

    @Test
    void filteredTextIsWhatHistoryRemembers() {
        gateway.enqueue(text("The hive is nearby (1279 68 358)."));
        brain.post(said("hello"));
        gateway.enqueue(text("ok"));
        brain.post(said("and?"));
        List<Message> messages = gateway.requests.get(1).messages();
        Message assistant = messages.stream()
                .filter(m -> m.role() == Role.ASSISTANT)
                .findFirst()
                .orElseThrow();
        assertEquals("The hive is nearby.", assistant.text());
        assertFalse(messages.stream().anyMatch(m -> m.text().contains("1279")));
    }

    @Test
    void specFailureIsReportedAndTheBrainRecovers() {
        tools.failSpecs = true;
        brain.post(said("go"));
        assertFalse(brain.isThinking());
        assertEquals(1, speaker.errors.size());
        tools.failSpecs = false;
        gateway.enqueue(text("back"));
        brain.post(said("again"));
        assertEquals(List.of("back"), speaker.said);
    }

    @Test
    void underscoredToolNamesAreNotSpoken() {
        tools.available.add(new ToolSpec("run_plan", "Run a plan", new JsonObject()));
        gateway.enqueue(text("Calling run_plan now."));
        brain.post(said("go"));
        assertEquals(List.of("Calling now."), speaker.said);
    }
}
