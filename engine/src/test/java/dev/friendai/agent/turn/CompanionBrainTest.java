package dev.friendai.agent.turn;

import static dev.friendai.testutil.FakeGateway.call;
import static dev.friendai.testutil.FakeGateway.calls;
import static dev.friendai.testutil.FakeGateway.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.goal.RiskClass;
import dev.friendai.agent.llm.model.LlmErrorKind;
import dev.friendai.agent.llm.model.LlmException;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.execution.TaskWorkResult;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.features.conversation.Audience;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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

    @Test
    void systemPromptCarriesTheMissionForTheOwner() {
        brain.setGoalSource(caller -> new dev.friendai.agent.goal.GoalView(
                "gather 12 logs", List.of("12 logs"), "plan 4 running", "", RiskClass.CAREFUL));
        gateway.enqueue(text("ok"));
        brain.post(said("hi"));
        String system = gateway.requests.get(0).system();
        assertTrue(system.contains("Mission:"), system);
        assertTrue(system.contains("gather 12 logs"), system);
    }

    @Test
    void queuedCooldownCountsAsPendingWork() {
        cooldown = 100;
        CompletableFuture<LlmResponse> first = gateway.enqueuePending();
        gateway.enqueue(text("second"));

        brain.post(said("hi"));
        brain.post(said("another turn"));
        first.complete(text("ok"));

        assertFalse(brain.isThinking());
        assertTrue(brain.hasPendingWork());
        scheduler.advance(100);
        assertFalse(brain.hasPendingWork());
    }

    @Test
    void cancelledTaskIsLoggedWithoutCallingTheModel() {
        List<String> dropped = new ArrayList<>();
        brain.setObserver(new BrainObserver() {
            @Override
            public void onEventDropped(BrainEvent event, String reason) {
                dropped.add(reason);
            }
        });

        brain.post(new BrainEvent.TaskCancelled(7, "mine_blocks", "owner stopped it"));

        assertTrue(gateway.requests.isEmpty());
        assertFalse(brain.hasPendingWork());
        assertEquals(List.of("cancellation retained in task history"), dropped);
    }

    @Test
    void repeatedAuthorityBlockerIsLoggedAndAskedAgainAfterCooldown() {
        List<String> dropped = new ArrayList<>();
        brain.setObserver(new BrainObserver() {
            @Override
            public void onEventDropped(BrainEvent event, String reason) {
                dropped.add(reason);
            }
        });
        BrainEvent.PlanBlocked blocker = new BrainEvent.PlanBlocked(8, "shelter", 2, "build", "missing materials", 3);
        gateway.enqueue(text("I need your decision."));
        gateway.enqueue(text("I'll answer your question."));
        gateway.enqueue(text("A new blocker needs attention."));
        gateway.enqueue(text("I still need your decision."));

        brain.post(blocker);
        scheduler.advance(1_000);
        brain.post(new BrainEvent.PlanBlocked(9, "another plan", 4, " BUILD ", " MISSING  MATERIALS ", 1));

        assertEquals(1, gateway.requests.size());
        assertEquals(List.of("authority blocker is still pending; question cooldown"), dropped);

        brain.post(said("Please help me with this."));
        assertEquals(2, gateway.requests.size(), "an owed player reply bypasses the blocker cooldown");

        brain.post(new BrainEvent.PlanBlocked(9, "another plan", 4, "build", "protection denied", 1));
        assertEquals(3, gateway.requests.size(), "a new hard blocker is not hidden by the previous cooldown");

        scheduler.advance(CompanionSpeechPolicy.AUTHORITY_QUESTION_COOLDOWN_MILLIS - 1_000);
        brain.post(new BrainEvent.PlanBlocked(10, "shelter", 5, "build", "missing materials", 2));

        assertEquals(4, gateway.requests.size());
        assertEquals(
                4, speaker.said.size(), "the repeat is quiet while new blockers and the player reply remain audible");
    }

    @Test
    void systemPromptCarriesKnownPlaces() {
        brain.setPlaceSource(caller -> "Known places: home at 1 2 3");
        gateway.enqueue(text("ok"));
        brain.post(said("hi"));
        String system = gateway.requests.get(0).system();
        assertTrue(system.contains("home at 1 2 3"), system);
    }

    private static ToolSpec spec(String name) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return new ToolSpec(name, "d", schema);
    }

    private static dev.friendai.agent.llm.model.ToolCall requestTools(String id, String group) {
        JsonObject args = new JsonObject();
        args.addProperty("group", group);
        return new dev.friendai.agent.llm.model.ToolCall(id, "request_tools", args, args.toString(), null);
    }

    private static List<String> toolNames(FakeGateway.Request request) {
        return request.tools().stream().map(ToolSpec::name).toList();
    }

    private void routedToolbox(boolean routing) {
        tools.available.clear();
        tools.available.addAll(List.of(spec("stop"), spec("stay"), spec("mine_blocks"), spec("request_tools")));
        brain.setAgentSettings(() -> new AgentSettings(
                routing, AgentSettings.Autonomy.ASSISTIVE, 3, 30, 0, 20, dev.friendai.agent.route.PlannerMode.AUTO));
    }

    @Test
    void theRequestNamesThePrefixAProviderMayCache() {
        gateway.enqueue(text("Right here."));
        brain.post(said("where are you?"));
        FakeGateway.Request request = gateway.requests.get(0);
        assertTrue(request.cacheableChars() > 0, "the brain sent no cacheable prefix");
        assertTrue(request.system().startsWith("You are Friendai"), request.system());
        assertTrue(
                request.system().substring(0, request.cacheableChars()).contains("Rules:"),
                "the cacheable prefix does not reach the rules");
        assertFalse(
                request.system().substring(0, request.cacheableChars()).contains(perceptionText),
                "the perception must not sit inside the cacheable prefix");
    }

    @Test
    void routingOffSendsEveryToolSpec() {
        routedToolbox(false);
        gateway.enqueue(text("Right here."));
        brain.post(said("where are you?"));
        assertEquals(List.of("stop", "stay", "mine_blocks", "request_tools"), toolNames(gateway.requests.get(0)));
    }

    @Test
    void chatTurnHidesActionGroups() {
        routedToolbox(true);
        gateway.enqueue(text("Right here."));
        brain.post(said("where are you?"));
        List<String> sent = toolNames(gateway.requests.get(0));
        assertFalse(sent.contains("mine_blocks"), sent.toString());
        assertTrue(sent.contains("stop") && sent.contains("request_tools"), sent.toString());
    }

    @Test
    void actionTurnKeepsTheMatchedGroup() {
        routedToolbox(true);
        gateway.enqueue(text("On it."));
        brain.post(said("mine some stone"));
        assertTrue(toolNames(gateway.requests.get(0)).contains("mine_blocks"));
    }

    @Test
    void requestToolsAddsTheGroupOnTheNextStep() {
        routedToolbox(true);
        gateway.enqueue(calls("", requestTools("r1", "gathering")));
        gateway.enqueue(text("Unlocked."));
        brain.post(said("where are you?"));
        assertFalse(toolNames(gateway.requests.get(0)).contains("mine_blocks"));
        assertTrue(toolNames(gateway.requests.get(1)).contains("mine_blocks"));
        assertFalse(tools.executed.contains("request_tools"), "the brain answers request_tools itself");
        Message result = gateway.requests.get(1).messages().stream()
                .filter(m -> m.role() == Role.TOOL)
                .findFirst()
                .orElseThrow();
        assertTrue(result.text().contains("tools unlocked: gathering"), result.text());
        assertTrue(result.text().contains("mine_blocks"), result.text());
    }

    @Test
    void unlockedGroupsDoNotLeakIntoTheNextTurn() {
        routedToolbox(true);
        gateway.enqueue(calls("", requestTools("r1", "gathering")));
        gateway.enqueue(text("Unlocked."));
        gateway.enqueue(text("Still here."));
        brain.post(said("where are you?"));
        brain.post(said("where are you now?"));
        assertFalse(toolNames(gateway.requests.get(2)).contains("mine_blocks"));
    }

    @Test
    void unknownGroupReturnsAnErrorListingGroups() {
        routedToolbox(true);
        gateway.enqueue(calls("", requestTools("r1", "teleportation")));
        gateway.enqueue(text("Hm."));
        brain.post(said("where are you?"));
        Message result = gateway.requests.get(1).messages().stream()
                .filter(m -> m.role() == Role.TOOL)
                .findFirst()
                .orElseThrow();
        assertTrue(result.text().contains("Unknown tool group"), result.text());
        assertTrue(result.text().contains("gathering"), result.text());
        assertFalse(toolNames(gateway.requests.get(1)).contains("mine_blocks"));
    }

    /** The ordered tool results a request carried, newest request last. */
    private static List<Message> toolResults(FakeGateway.Request request) {
        return request.messages().stream().filter(m -> m.role() == Role.TOOL).toList();
    }

    /** The one tool result a request carried for this provider call id. */
    private static Message toolResult(FakeGateway.Request request, String id) {
        return toolResults(request).stream()
                .filter(m -> id.equals(m.toolCallId()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aHiddenToolCallIsRefusedWithoutEffect() {
        routedToolbox(true);
        gateway.enqueue(calls("", call("m1", "mine_blocks")));
        gateway.enqueue(text("I did not mine anything."));

        brain.post(said("where are you?"));

        assertFalse(toolNames(gateway.requests.get(0)).contains("mine_blocks"), "the chat turn starts without it");
        assertTrue(tools.executed.isEmpty(), "a call the request never offered must not run: " + tools.executed);
        Message refused = toolResults(gateway.requests.get(1)).getFirst();
        assertEquals("m1", refused.toolCallId(), "the refusal keeps the provider's id");
        assertTrue(refused.text().contains("not offered"), refused.text());
        assertTrue(refused.text().contains("request_tools"), "the model is told how to unlock it: " + refused.text());
        assertFalse(
                toolNames(gateway.requests.get(1)).contains("mine_blocks"),
                "a guessed call does not reveal its own schema");
    }

    @Test
    void aTeachingOnlyToolIsRefusedOutsideATeachingTurn() {
        routedToolbox(true);
        tools.available.add(spec("manage_skill"));
        gateway.enqueue(calls("", call("s1", "manage_skill")));
        gateway.enqueue(text("Nothing was saved."));

        brain.post(said("mine some stone"));

        assertFalse(toolNames(gateway.requests.get(0)).contains("manage_skill"), "no teaching context, no schema");
        assertTrue(tools.executed.isEmpty(), tools.executed.toString());
        Message refused = toolResults(gateway.requests.get(1)).getFirst();
        assertTrue(refused.text().contains("not offered"), refused.text());
        assertFalse(
                refused.text().contains("request_tools"),
                "a teaching turn is not unlocked by a group: " + refused.text());
    }

    @Test
    void aHugeToolResultIsBoundedInTheHistoryButWholeForTheObserver() {
        String huge = "HEAD" + "z".repeat(20_000) + "TAIL";
        tools.results
                .computeIfAbsent("stay", name -> new java.util.ArrayDeque<>())
                .add(dev.friendai.execution.tool.ToolResult.ok(huge));
        List<String> observed = new ArrayList<>();
        brain.setObserver(new BrainObserver() {
            @Override
            public void onToolResult(
                    dev.friendai.agent.llm.model.ToolCall call, dev.friendai.execution.tool.ToolResult result) {
                observed.add(result.content());
            }
        });
        gateway.enqueue(calls("", call("s1", "stay")));
        gateway.enqueue(text("Waiting."));

        brain.post(said("wait here"));

        String history = toolResults(gateway.requests.get(1)).getFirst().text();
        assertTrue(history.length() < ToolResultText.MAX_CHARS + 120, "history carried " + history.length());
        assertTrue(history.contains("characters omitted"), history);
        assertEquals(huge, observed.get(0), "the observer must see the whole result");
    }

    @Test
    void aMixedBatchKeepsOrderAndIdsAndRunsOnlyTheOfferedCalls() {
        routedToolbox(true);
        gateway.enqueue(calls("", call("a", "mine_blocks"), call("b", "stop"), call("c", "mine_blocks")));
        gateway.enqueue(text("Standing by."));

        brain.post(said("where are you?"));

        assertEquals(List.of("stop"), tools.executed, "only the offered call runs");
        List<Message> results = toolResults(gateway.requests.get(1));
        assertEquals(
                List.of("a", "b", "c"),
                results.stream().map(Message::toolCallId).toList());
        assertTrue(results.get(0).text().contains("not offered"), results.get(0).text());
        assertEquals("ok stop", results.get(1).text());
        assertTrue(results.get(2).text().contains("not offered"), results.get(2).text());
    }

    @Test
    void routingOffRunsEveryCallBecauseEverySchemaWasOffered() {
        routedToolbox(false);
        gateway.enqueue(calls("", call("m1", "mine_blocks")));
        gateway.enqueue(text("Done."));

        brain.post(said("where are you?"));

        assertEquals(List.of("mine_blocks"), tools.executed);
        assertEquals("ok mine_blocks", toolResult(gateway.requests.get(1), "m1").text());
    }

    @Test
    void requestToolsMakesTheNextCallEligible() {
        routedToolbox(true);
        gateway.enqueue(calls("", requestTools("r1", "gathering")));
        gateway.enqueue(calls("", call("m1", "mine_blocks")));
        gateway.enqueue(text("Mined."));

        brain.post(said("where are you?"));

        assertEquals(List.of("mine_blocks"), tools.executed, "the discovered tool runs on the next step");
        assertEquals("ok mine_blocks", toolResult(gateway.requests.get(2), "m1").text());
    }

    @Test
    void aCallToAnOfferedToolAddsNoNote() {
        routedToolbox(true);
        tools.results
                .computeIfAbsent("mine_blocks", name -> new java.util.ArrayDeque<>())
                .add(dev.friendai.execution.tool.ToolResult.error("no such block nearby"));
        gateway.enqueue(calls("", call("m1", "mine_blocks")));
        gateway.enqueue(text("Nothing to mine."));

        brain.post(said("mine some stone"));

        Message result = gateway.requests.get(1).messages().stream()
                .filter(m -> m.role() == Role.TOOL)
                .findFirst()
                .orElseThrow();
        assertEquals("no such block nearby", result.text());
    }

    @Test
    void hiddenToolNameIsStillCaughtByReplyGuard() {
        routedToolbox(true);
        gateway.enqueue(text("I will use mine_blocks now."));
        brain.post(said("where are you?"));
        assertTrue(speaker.said.stream().noneMatch(line -> line.contains("mine_blocks")), speaker.said.toString());
    }

    @Test
    void guestRequestsNeverContainOwnerConversation() {
        gateway.enqueue(text("OWNER_REPLY"));
        gateway.enqueue(text("GUEST_REPLY"));
        gateway.enqueue(text("OWNER_AGAIN"));
        brain.post(said("OWNER_SECRET"));
        brain.post(guest("GUEST_LINE"));
        brain.post(said("OWNER_SECOND"));
        List<Message> guestMessages = gateway.requests.get(1).messages();
        assertEquals(
                "[Bob (guest)]: GUEST_LINE", lastUser(gateway.requests.get(1)).text());
        assertFalse(
                guestMessages.stream()
                        .anyMatch(m ->
                                m.text().contains("OWNER_SECRET") || m.text().contains("OWNER_REPLY")),
                "guest input must not contain owner user or assistant history");
        List<Message> ownerMessages = gateway.requests.get(2).messages();
        assertTrue(ownerMessages.stream().anyMatch(m -> m.text().contains("OWNER_SECRET")), "owner history continues");
        assertFalse(
                ownerMessages.stream().anyMatch(m -> m.text().contains("GUEST_LINE")),
                "guest history must not enter the owner conversation");
    }

    @Test
    void ownerToolResultsStayOutOfGuestInput() {
        gateway.enqueue(calls("", call("c1", "stay")));
        gateway.enqueue(text("OWNER_TOOL_REPLY"));
        gateway.enqueue(text("guest reply"));
        brain.post(said("OWNER_SECRET"));
        brain.post(guest("GUEST_LINE"));
        List<Message> guestMessages = gateway.requests.get(2).messages();
        assertTrue(
                guestMessages.stream().noneMatch(m -> m.role() == Role.TOOL),
                "private tool results must not cross into a guest request");
        assertFalse(
                guestMessages.stream().anyMatch(m -> m.text().contains("OWNER_TOOL_REPLY")),
                "owner assistant text must not cross into a guest request");
    }

    @Test
    void guestHistoriesAreIsolatedFromEachOther() {
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000003");
        gateway.enqueue(text("FIRST_GUEST_REPLY"));
        gateway.enqueue(text("second guest reply"));
        brain.post(guest("FIRST_GUEST_SECRET"));
        brain.post(new BrainEvent.PlayerSaid(other, "Cara", "SECOND_GUEST_LINE", BrainEvent.Source.CHAT));
        List<Message> messages = gateway.requests.get(1).messages();
        assertEquals(
                "[Cara (guest)]: SECOND_GUEST_LINE",
                lastUser(gateway.requests.get(1)).text());
        assertFalse(
                messages.stream()
                        .anyMatch(m -> m.text().contains("FIRST_GUEST_SECRET")
                                || m.text().contains("FIRST_GUEST_REPLY")),
                "one guest must not see another guest's history");
    }

    @Test
    void revokingTrustInvalidatesTheTrustedHistory() {
        trusted.add(GUEST);
        gateway.enqueue(text("TRUSTED_REPLY"));
        gateway.enqueue(text("guest reply"));
        brain.post(guest("TRUSTED_SECRET"));
        trusted.remove(GUEST);
        brain.post(guest("AFTER_REVOKE"));
        List<Message> messages = gateway.requests.get(1).messages();
        assertFalse(
                messages.stream()
                        .anyMatch(m ->
                                m.text().contains("TRUSTED_SECRET") || m.text().contains("TRUSTED_REPLY")),
                "a revoked player must not receive the history built while trusted");
    }

    @Test
    void eventTurnsUseOnlyTheOwnerHistory() {
        gateway.enqueue(text("EVENT_REPLY"));
        gateway.enqueue(text("guest reply"));
        brain.post(new BrainEvent.WorldNotice("OWNER_EVENT_SECRET"));
        brain.post(guest("GUEST_LINE"));
        List<Message> messages = gateway.requests.get(1).messages();
        assertFalse(
                messages.stream()
                        .anyMatch(m -> m.text().contains("OWNER_EVENT_SECRET")
                                || m.text().contains("EVENT_REPLY")),
                "background event context belongs to the owner history");
    }

    @Test
    void historyMapIsBoundedAndEvictsTheLeastRecentlyUsedGuest() {
        gateway.enqueue(text("OWNER_REPLY"));
        brain.post(said("OWNER_ANCHOR"));
        List<UUID> guests = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            UUID id = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i + 100));
            guests.add(id);
            gateway.enqueue(text("reply " + i));
            brain.post(new BrainEvent.PlayerSaid(id, "G" + i, "LINE_" + i, BrainEvent.Source.CHAT));
        }
        gateway.enqueue(text("fresh reply"));
        brain.post(new BrainEvent.PlayerSaid(guests.getFirst(), "G0", "FRESH_LINE", BrainEvent.Source.CHAT));
        List<Message> fresh = gateway.requests.get(gateway.requests.size() - 1).messages();
        assertFalse(
                fresh.stream().anyMatch(m -> m.text().contains("LINE_0")),
                "the least recently used guest history is evicted");
        gateway.enqueue(text("OWNER_AGAIN"));
        brain.post(said("OWNER_SECOND"));
        List<Message> owner = gateway.requests.get(gateway.requests.size() - 1).messages();
        assertTrue(
                owner.stream().anyMatch(m -> m.text().contains("OWNER_ANCHOR")), "the owner history is never evicted");
    }

    private static BrainEvent said(String text) {
        return new BrainEvent.PlayerSaid(OWNER, "Steve", text, BrainEvent.Source.CHAT);
    }

    private static BrainEvent guest(String text) {
        return new BrainEvent.PlayerSaid(GUEST, "Bob", text, BrainEvent.Source.CHAT);
    }

    @Test
    void perceptionReceivesTheTurnReferent() {
        List<java.util.Optional<UtteranceReferent>> seen = new ArrayList<>();
        brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(maxSteps, 30, cooldown, guestCooldown),
                () -> gatewaySupplier.get(),
                tools,
                new PerceptionSource() {
                    @Override
                    public String describe() {
                        return "plain";
                    }

                    @Override
                    public String describe(
                            dev.friendai.execution.tool.Caller caller, java.util.Optional<UtteranceReferent> referent) {
                        seen.add(referent);
                        return "VIEW";
                    }
                },
                speaker,
                scheduler,
                AccessResolver.of(OWNER, trusted::contains));
        gateway.enqueue(text("ok"));
        gateway.enqueue(text("ok"));
        gateway.enqueue(text("ok"));
        UtteranceReferent oak = referent(2, 3, 8, "minecraft:oak_log", UtteranceReferent.Kind.BLOCK);
        brain.post(new BrainEvent.PlayerSaid(
                OWNER, "Steve", "break this block", BrainEvent.Source.CHAT, Audience.PUBLIC, oak));
        // The pointed block stays with the turn while the model answers; an unrelated event turn has none.
        assertEquals(Optional.of(oak), seen.get(seen.size() - 1));
        brain.post(new BrainEvent.TaskFinished(1, "mine_blocks", true, "mined 3 logs"));
        assertEquals(Optional.empty(), seen.get(seen.size() - 1));
        assertEquals(2, seen.size());
    }

    @Test
    void aLaterTargetedLineWinsOverAnEarlierOne() {
        List<java.util.Optional<UtteranceReferent>> seen = new ArrayList<>();
        brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(maxSteps, 30, cooldown, guestCooldown),
                () -> gatewaySupplier.get(),
                tools,
                new PerceptionSource() {
                    @Override
                    public String describe() {
                        return "plain";
                    }

                    @Override
                    public String describe(
                            dev.friendai.execution.tool.Caller caller, java.util.Optional<UtteranceReferent> referent) {
                        seen.add(referent);
                        return "VIEW";
                    }
                },
                speaker,
                scheduler,
                AccessResolver.of(OWNER, trusted::contains));
        gateway.enqueue(text("ok"));
        UtteranceReferent oak = referent(2, 3, 8, "minecraft:oak_log", UtteranceReferent.Kind.BLOCK);
        UtteranceReferent stone = referent(5, 6, 7, "minecraft:stone", UtteranceReferent.Kind.BLOCK);
        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", "one", BrainEvent.Source.CHAT, Audience.PUBLIC, oak));
        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", "two", BrainEvent.Source.CHAT, Audience.PUBLIC, stone));
        assertEquals(Optional.of(stone), seen.get(seen.size() - 1), "the last line that pointed at something wins");
    }

    private static UtteranceReferent referent(int x, int y, int z, String target, UtteranceReferent.Kind kind) {
        return new UtteranceReferent(OWNER, "minecraft:overworld", x, y, z, target, kind, 40L);
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
        assertEquals(List.of(Audience.PUBLIC), speaker.audiences, "typed chat is a public line");
        assertEquals(List.of(true, false), speaker.thinkingLog);
        brain.post(said("again"));
        gateway.enqueue(text("Again"));
        List<Message> second = gateway.requests.get(gateway.requests.size() - 1).messages();
        assertEquals("[Steve (owner)]: hi", second.get(0).text());
        assertEquals(Role.ASSISTANT, second.get(1).role());
    }

    @Test
    void aVoiceReplyStaysPrivateToTheSpeaker() {
        gateway.enqueue(text("Only you"));
        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", "hello", BrainEvent.Source.VOICE));

        assertEquals(List.of("Only you"), speaker.said);
        assertEquals(List.of(Audience.PRIVATE), speaker.audiences);
        assertEquals(List.of(OWNER), speaker.addressees);
    }

    @Test
    void anEventOnlyTurnSpeaksNearby() {
        gateway.enqueue(text("Done"));
        brain.post(new BrainEvent.TaskFinished(1, "stay", true, "ok"));

        assertEquals(List.of(Audience.NEARBY), speaker.audiences);
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
                .filter(m -> m.role() == Role.TOOL)
                .findFirst()
                .orElseThrow();
        assertEquals("c1", toolMsg.toolCallId());
        assertEquals(List.of("Staying"), speaker.said);
    }

    @Test
    void executesAllToolCallsInOrder() {
        tools.available.addAll(List.of(spec("first"), spec("second")));
        gateway.enqueue(calls("", call("a", "first"), call("b", "second")));
        gateway.enqueue(text("ok"));
        brain.post(said("go"));
        assertEquals(List.of("first", "second"), tools.executed);
        List<Message> second = gateway.requests.get(1).messages();
        List<String> toolIds = second.stream()
                .filter(m -> m.role() == Role.TOOL)
                .map(Message::toolCallId)
                .toList();
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
    void aNullProviderReplyFinishesTheFailedTurnAndUnblocksQueuedWork() {
        CompletableFuture<LlmResponse> pending = gateway.enqueuePending();
        gateway.enqueue(text("Second request answered"));
        brain.post(said("first request"));
        brain.post(said("second request"));

        pending.complete(null);

        assertEquals(List.of(UserMessage.of("friendai.error.llm.parse")), speaker.errors);
        assertEquals(List.of("Second request answered"), speaker.said);
        assertEquals(List.of(true, false, true, false), speaker.thinkingLog);
    }

    @Test
    void aNullProviderFutureFinishesTheTurn() {
        gatewaySupplier = () -> (system, messages, specs) -> null;
        brain.post(said("first request"));

        assertEquals(List.of(UserMessage.of("friendai.error.llm.parse")), speaker.errors);
        assertEquals(List.of(true, false), speaker.thinkingLog);
    }

    @Test
    void aSynchronousProviderFailureFinishesTheTurn() {
        gatewaySupplier = () -> (system, messages, specs) -> {
            throw new LlmException(LlmErrorKind.NETWORK, "request failed");
        };
        brain.post(said("first request"));

        assertEquals(List.of(UserMessage.of("friendai.error.llm.network")), speaker.errors);
        assertEquals(List.of(true, false), speaker.thinkingLog);
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
    void failureDuringARequestRefreshesTheContextBeforeSpeakingOrRunningStaleCalls() {
        CompletableFuture<LlmResponse> pending = gateway.enqueuePending();
        gateway.enqueue(text("The path is blocked; I cannot reach you."));
        brain.post(said("come here"));
        brain.post(new BrainEvent.TaskFinished(7, "go_to", false, "no safe route"));
        pending.complete(calls("I am walking to you.", call("stale", "give_items")));

        assertEquals(List.of("The path is blocked; I cannot reach you."), speaker.said);
        assertTrue(tools.executed.isEmpty(), "the old response must not run work against superseded facts");
        assertEquals(2, gateway.requests.size());
        assertTrue(lastUser(gateway.requests.get(1)).text().contains("go_to failed: no safe route"));
        assertFalse(brain.hasPendingWork(), "the failure was consumed once in the refreshed request");
    }

    @Test
    void firstRequestAfterPartialFailureIncludesMeasuredWorkAndBlocker() {
        CompletableFuture<LlmResponse> pending = gateway.enqueuePending();
        gateway.enqueue(text("I will try another reachable tree."));
        TaskWorkResult result = new TaskWorkResult(
                List.of(new TaskWorkResult.Line("minecraft:oak_log", 12, 9, TaskWorkResult.Evidence.CARRIED)),
                "partial",
                "2 logs remain on the crown",
                "try the next reachable tree");
        brain.post(said("get 12 oak logs"));
        brain.post(new BrainEvent.TaskFinished(
                7, 0, 0, "obtain_logs", false, "cut 9 of 12 oak logs", "", Optional.of(result)));
        pending.complete(calls("The first attempt is still running.", call("stale", "give_items")));

        assertEquals(2, gateway.requests.size());
        String nextRequest = lastUser(gateway.requests.get(1)).text();
        assertTrue(nextRequest.contains("minecraft:oak_log carried=9/12 remaining=3"), nextRequest);
        assertTrue(nextRequest.contains("blocker=2 logs remain on the crown"), nextRequest);
        assertTrue(nextRequest.contains("next=try the next reachable tree"), nextRequest);
        assertTrue(tools.executed.isEmpty(), "the stale call must not run before the measured failure is sent");
    }

    @Test
    void ownerFailureDoesNotInvalidateAGuestResponseOrLeakItsFacts() {
        CompletableFuture<LlmResponse> pending = gateway.enqueuePending();
        gateway.enqueue(text("I cannot reach the owner."));
        brain.post(guest("hello"));
        brain.post(new BrainEvent.TaskFinished(7, "go_to", false, "private route failure"));
        pending.complete(text("Hello Bob."));

        assertEquals(List.of("Hello Bob.", "I cannot reach the owner."), speaker.said);
        assertFalse(gateway.requests.get(0).messages().toString().contains("private route failure"));
        assertTrue(lastUser(gateway.requests.get(1)).text().contains("private route failure"));
    }

    @Test
    void anUnrelatedPlanFailureDoesNotDiscardTheConversationReply() {
        CompletableFuture<LlmResponse> pending = gateway.enqueuePending();
        gateway.enqueue(text("Hello back."));
        gateway.enqueue(text("Acknowledged."));
        brain.post(said("hello"));
        brain.post(new BrainEvent.TaskFinished(7, 9, 0, "go_to", false, "a background route failed", ""));
        pending.complete(text("Hello back."));

        assertEquals("Hello back.", speaker.said.get(0), "an unrelated job's failure must not replace the reply");
        assertTrue(
                lastUser(gateway.requests.get(1)).text().contains("a background route failed"),
                "the failure is still reported to the model in the next turn");
    }

    @Test
    void aPlanFailureThisTurnIsAboutReplacesTheAnswer() {
        CompletableFuture<LlmResponse> pending = gateway.enqueuePending();
        gateway.enqueue(text("The repair is queued."));
        brain.post(new BrainEvent.PlanBlocked(8, "shelter", 2, "build", "missing planks", 3));
        brain.post(new BrainEvent.TaskFinished(7, 8, 0, "build", false, "ran out of planks", ""));
        pending.complete(calls("The wall is finished.", call("stale", "give_items")));

        assertEquals(List.of("The repair is queued."), speaker.said);
        assertTrue(tools.executed.isEmpty(), "the superseded answer must not run work");
        assertTrue(
                lastUser(gateway.requests.get(1)).text().contains("ran out of planks"),
                "the affected plan's failure is what the next request sees");
    }

    @Test
    void anOwnerCorrectionDropsThePendingAnswerAndUsesTheNewestIntent() {
        CompletableFuture<LlmResponse> first = gateway.enqueuePending();
        CompletableFuture<LlmResponse> second = gateway.enqueuePending();
        gateway.enqueue(text("Sandstone it is."));
        brain.post(said("build the wall out of wood"));
        brain.post(said("No, build it out of sandstone"));
        assertEquals(2, gateway.requests.size(), "the correction starts at once instead of waiting for the answer");
        first.complete(calls("I am building the wooden wall.", call("stale", "queue_add")));
        second.complete(text("Sandstone it is."));

        assertTrue(tools.executed.isEmpty(), "the answered-for order was replaced before it could act");
        assertEquals(List.of("Sandstone it is."), speaker.said);
        assertTrue(lastUser(gateway.requests.get(1)).text().contains("sandstone"));
    }

    @Test
    void anAdditionGetsItsOwnTurnWithoutDroppingTheAnswer() {
        CompletableFuture<LlmResponse> first = gateway.enqueuePending();
        gateway.enqueue(text("And the coal too."));
        brain.post(said("come here"));
        brain.post(said("and also bring me coal"));
        first.complete(text("On my way."));

        assertEquals(List.of("On my way.", "And the coal too."), speaker.said);
        assertEquals(2, gateway.requests.size());
    }

    @Test
    void aGuestMessageNeverSupersedesTheOwnerTurn() {
        CompletableFuture<LlmResponse> pending = gateway.enqueuePending();
        gateway.enqueue(text("Hello Steve."));
        brain.post(said("hello"));
        brain.post(guest("No, stop talking"));
        pending.complete(text("Hello Steve."));

        assertEquals("Hello Steve.", speaker.said.get(0), "a guest cannot replace the owner's answer");
    }

    @Test
    void blockedWorkUsesItsToolContextAndKeepsRecoveryAndDiscoveryAvailable() {
        for (String name : List.of(
                "obtain_item", "clear_area", "go_to", "queue_edit", "request_tools", "barter", "breed_animals")) {
            tools.available.add(new ToolSpec(name, name, new JsonObject()));
        }
        gateway.enqueue(text("I need to clear space for the crafting table."));
        brain.post(new BrainEvent.PlanBlocked(3, "tools", 4, "obtain_item", "no free spot for crafting table", 2));

        Set<String> sent = gateway.requests.getFirst().tools().stream()
                .map(ToolSpec::name)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(
                sent.containsAll(Set.of("obtain_item", "clear_area", "go_to", "queue_edit", "request_tools")),
                sent.toString());
        assertFalse(sent.contains("barter"), sent.toString());
        assertFalse(sent.contains("breed_animals"), sent.toString());
    }

    @Test
    void aNoticeThatNamesAToolOffersThatTool() {
        routedToolbox(true);
        tools.available.add(spec("obtain_item"));
        gateway.enqueue(text("I will get the iron."));

        brain.post(new BrainEvent.WorldNotice(
                "plan #3 armor failed: missing materials; try: obtain_item minecraft:iron_ingot"));

        assertTrue(
                toolNames(gateway.requests.getFirst()).contains("obtain_item"),
                "a diagnosis naming a remedy must not leave the companion without it: "
                        + toolNames(gateway.requests.getFirst()));
    }

    @Test
    void aNoticeThatNamesNoToolStaysOnTheEventGroups() {
        routedToolbox(true);
        gateway.enqueue(text("Noted."));

        brain.post(new BrainEvent.WorldNotice("the furnace finished"));

        List<String> sent = toolNames(gateway.requests.getFirst());
        assertFalse(sent.contains("mine_blocks"), "news is not an order to mine: " + sent);
        assertTrue(sent.contains("stay") && sent.contains("request_tools"), sent.toString());
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
        assertEquals(
                "[event] the furnace is done", lastUser(gateway.requests.get(0)).text());
        assertEquals(AccessLevel.OWNER, tools.specAccess.get(0));
    }

    @Test
    void taskEventsOnlyTurnActsAsOwner() {
        gateway.enqueue(text("noted"));
        brain.post(new BrainEvent.TaskFinished(1, "stay", true, "ok"));
        assertEquals(AccessLevel.OWNER, tools.specAccess.get(0));
    }

    @Test
    void onlyOwnerPlayerSaidBatchOpensTradingTurn() {
        List<Boolean> initiated = new ArrayList<>();
        brain.setTradingTurnPort((caller, playerInitiated) -> {
            initiated.add(playerInitiated);
            return caller.withTradingScope(playerInitiated ? UUID.randomUUID() : null);
        });
        gateway.enqueue(text("done"));
        gateway.enqueue(text("done"));
        gateway.enqueue(text("recovery noted"));
        gateway.enqueue(text("goal noted"));

        brain.post(said("trade"));
        brain.post(new BrainEvent.TaskFinished(1, "trade_item", true, "finished"));
        brain.post(new BrainEvent.PlanBlocked(2, "gather", 3, "obtain_item", "missing input", 1));
        brain.post(new BrainEvent.GoalTick("gather logs", List.of("logs")));

        assertEquals(List.of(true, false, false, false), initiated);
    }

    @Test
    void aDiscussionAboutTradingOpensNoSpendingScope() {
        List<Boolean> initiated = new ArrayList<>();
        brain.setTradingTurnPort((caller, playerInitiated) -> {
            initiated.add(playerInitiated);
            return caller.withTradingScope(playerInitiated ? UUID.randomUUID() : null);
        });
        for (String line : List.of(
                "Don't buy anything from the villager",
                "Не покупай у жителя ничего",
                "He said \"buy three emeralds\"",
                "If I gave you emeralds, would you buy a map?")) {
            gateway.enqueue(text("Understood."));
            brain.post(said(line));
            scheduler.advance(1_000);
        }

        assertEquals(List.of(false, false, false, false), initiated, "only an affirmative order may spend");
    }

    @Test
    void aBlockedPlanBesideADiscussionAboutTradingOpensNoScope() {
        List<Boolean> initiated = new ArrayList<>();
        brain.setTradingTurnPort((caller, playerInitiated) -> {
            initiated.add(playerInitiated);
            return caller.withTradingScope(playerInitiated ? UUID.randomUUID() : null);
        });
        CompletableFuture<LlmResponse> first = gateway.enqueuePending();
        gateway.enqueue(text("The plan is blocked."));
        // An event turn first: a player line posted while it runs waits for the next batch instead of replacing it.
        brain.post(new BrainEvent.TaskFinished(1, "stay", true, "ok"));
        brain.post(said("Should I buy a map from the villager?"));
        brain.post(new BrainEvent.PlanBlocked(3, "gather", 4, "obtain_item", "missing input", 1));
        first.complete(text("Noted."));

        assertEquals(2, gateway.requests.size(), "the question and the blocked plan are one turn");
        String prompt = gateway.requests.get(1).messages().stream()
                .map(Message::text)
                .filter(java.util.Objects::nonNull)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(prompt.contains("Should I buy a map"), prompt);
        assertTrue(prompt.contains("needs a decision at job #4"), prompt);
        assertEquals(
                List.of(false, false),
                initiated,
                "a recovery route from an unrelated blocked plan must not read as consent to spend");
    }

    @Test
    void aProposalTurnNeverOpensASpendingScope() {
        List<Boolean> initiated = new ArrayList<>();
        brain.setTradingTurnPort((caller, playerInitiated) -> {
            initiated.add(playerInitiated);
            return caller.withTradingScope(playerInitiated ? UUID.randomUUID() : null);
        });
        gateway.enqueue(text("Here is the plan."));

        brain.post(said("Make a plan to buy a map. Do nothing yet."));

        assertEquals(List.of(false), initiated, "a plan the owner has not approved must not carry spending authority");
    }

    @Test
    void anAffirmativeTradeOrderStillOpensOne() {
        List<Boolean> initiated = new ArrayList<>();
        brain.setTradingTurnPort((caller, playerInitiated) -> {
            initiated.add(playerInitiated);
            return caller.withTradingScope(playerInitiated ? UUID.randomUUID() : null);
        });
        gateway.enqueue(text("On it."));

        brain.post(said("Buy a map from the villager"));

        assertEquals(List.of(true), initiated);
    }

    @Test
    void guestAndTrustedPlayerTurnsCannotOpenTradingScope() {
        List<Boolean> initiated = new ArrayList<>();
        brain.setTradingTurnPort((caller, playerInitiated) -> {
            initiated.add(playerInitiated);
            return caller.withTradingScope(playerInitiated ? UUID.randomUUID() : null);
        });
        gateway.enqueue(text("guest"));
        gateway.enqueue(text("trusted"));

        brain.post(guest("hello"));
        trusted.add(GUEST);
        brain.post(guest("hello again"));

        assertEquals(List.of(false, false), initiated);
    }

    @Test
    void tradingQuestionsCannotCreateAScopeForAQueuedSpendingStep() {
        List<Boolean> initiated = new ArrayList<>();
        brain.setTradingTurnPort((caller, playerInitiated) -> {
            initiated.add(playerInitiated);
            return caller.withTradingScope(playerInitiated ? UUID.randomUUID() : null);
        });
        gateway.enqueue(text("The remembered quotes are old."));

        brain.post(said("What trades does the villager have?"));

        assertEquals(
                List.of(false), initiated, "a chat response must not have an owner scope that queue_add could capture");
    }

    @Test
    void fallbackRoutingDoesNotCreateSpendingScopeWithoutAnExplicitTradeVerb() {
        List<Boolean> initiated = new ArrayList<>();
        brain.setTradingTurnPort((caller, playerInitiated) -> {
            initiated.add(playerInitiated);
            return caller.withTradingScope(playerInitiated ? UUID.randomUUID() : null);
        });
        gateway.enqueue(text("Okay."));

        brain.post(said("hmm okay then"));

        assertEquals(
                List.of(false), initiated, "fallback routing exposes every schema but must not mint trade authority");
    }

    @Test
    void toolsKeepTheScopeCreatedForTheirOwnOwnerTurn() {
        brain.setTradingTurnPort(
                (caller, playerInitiated) -> caller.withTradingScope(playerInitiated ? UUID.randomUUID() : null));
        tools.available.addAll(List.of(spec("first"), spec("second")));
        gateway.enqueue(calls("", call("a", "first")));
        gateway.enqueue(text("first done"));
        gateway.enqueue(calls("", call("b", "second")));
        gateway.enqueue(text("second done"));

        brain.post(said("buy an emerald from the villager"));
        UUID firstScope = tools.executeCallers.getFirst().tradingScope();
        brain.post(said("sell wheat to a villager"));
        UUID secondScope = tools.executeCallers.get(1).tradingScope();

        assertTrue(firstScope != null && secondScope != null && !firstScope.equals(secondScope));
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
                "[Bob (guest)]: g1\n[Bob (guest)]: g2",
                lastUser(gateway.requests.get(1)).text());
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
    void aDeliveredTurnIsNotCancelledAgain() {
        List<String> ends = new ArrayList<>();
        brain.setObserver(new BrainObserver() {
            @Override
            public void onTurnEnd(long turn, String outcome) {
                ends.add(outcome);
            }
        });
        gateway.enqueue(text("done"));
        brain.post(said("hi"));
        assertEquals(List.of("delivered"), ends);
        brain.cancel();
        brain.cancel();
        assertEquals(List.of("delivered"), ends, "a finished turn ends once");
    }

    @Test
    void cancellingOneFlightTwiceReportsOneEnd() {
        List<String> ends = new ArrayList<>();
        brain.setObserver(new BrainObserver() {
            @Override
            public void onTurnEnd(long turn, String outcome) {
                ends.add(outcome);
            }
        });
        gateway.enqueuePending();
        brain.post(said("hi"));
        brain.cancel();
        brain.cancel();
        assertEquals(List.of("cancelled"), ends);
    }

    @Test
    void theIdleGoalNudgeIsAnsweredWhenTheModelStaysSilent() {
        List<String> ends = new ArrayList<>();
        brain.setObserver(new BrainObserver() {
            @Override
            public void onTurnEnd(long turn, String outcome) {
                ends.add(outcome);
            }
        });
        gateway.enqueue(text("[silent]"));
        brain.post(new BrainEvent.GoalTick("stone tools", List.of("stone pickaxe")));
        assertEquals(List.of(UserMessage.of("friendai.companion.goal_blocked", "stone tools")), speaker.messages);
        assertEquals(List.of("delivered"), ends, "the answered nudge ends the turn in the log");
    }

    @Test
    void aCancelDuringTheCooldownDoesNotCancelTheDeliveredTurn() {
        cooldown = 500;
        List<String> ends = new ArrayList<>();
        brain.setObserver(new BrainObserver() {
            @Override
            public void onTurnEnd(long turn, String outcome) {
                ends.add(outcome);
            }
        });
        gateway.enqueue(text("done"));
        gateway.enqueue(text("later"));
        brain.post(said("one"));
        brain.post(said("two"));
        assertEquals(List.of("delivered"), ends);
        brain.cancel();
        brain.cancel();
        assertEquals(List.of("delivered"), ends, "a queued event is not a turn to cancel");
    }

    @Test
    void aPlayerTurnStaysSilentWhenTheModelSaysSo() {
        gateway.enqueue(text("[silent]"));
        brain.post(said("just checking in"));
        assertTrue(speaker.messages.isEmpty(), "the fallback belongs to goal nudges only: " + speaker.messages);
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

    /** A memory source that records the query it was given, so a test can see what retrieval would search for. */
    private void recordMemoryQueries(List<String> queries) {
        brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(maxSteps, 30, cooldown, guestCooldown),
                () -> gatewaySupplier.get(),
                tools,
                () -> perceptionText,
                speaker,
                scheduler,
                AccessResolver.of(OWNER, trusted::contains),
                new MemorySource() {
                    @Override
                    public String describe(dev.friendai.execution.tool.Caller caller) {
                        return "";
                    }

                    @Override
                    public String describe(dev.friendai.execution.tool.Caller caller, String playerText) {
                        queries.add(playerText);
                        return "MEM";
                    }
                });
    }

    @Test
    void anEventOnlyRecoveryTurnAsksMemoryAboutTheGoalAndItsBlocker() {
        List<String> queries = new ArrayList<>();
        recordMemoryQueries(queries);
        brain.setGoalSource(caller -> new dev.friendai.agent.goal.GoalView(
                "gather 12 iron ore", List.of("12 iron ore"), "plan 4 running", "", RiskClass.CAREFUL));
        gateway.enqueue(text("I need a stone pickaxe first."));
        brain.post(new BrainEvent.TaskFinished(7, "mine_blocks", false, "no pickaxe in the inventory"));

        assertEquals(1, queries.size());
        assertTrue(queries.get(0).contains("gather 12 iron ore"), queries.get(0));
        assertTrue(queries.get(0).contains("no pickaxe in the inventory"), queries.get(0));
        assertTrue(
                queries.get(0).length() <= 400,
                "a recovery query is bounded: " + queries.get(0).length());
        assertTrue(gateway.requests.get(0).system().contains("MEM"));
    }

    @Test
    void aFailureDuringARequestReAsksMemoryAboutTheBlockerNotOnlyTheOrder() {
        List<String> queries = new ArrayList<>();
        recordMemoryQueries(queries);
        CompletableFuture<LlmResponse> pending = gateway.enqueuePending();
        gateway.enqueue(text("I will look elsewhere."));
        brain.post(said("collect iron ore"));
        brain.post(new BrainEvent.TaskFinished(7, "mine_blocks", false, "no pickaxe in the inventory"));

        assertEquals("collect iron ore", queries.get(0), "the first request asks about the order alone");

        pending.complete(text("The cave wall is too hard."));
        assertEquals(2, gateway.requests.size());
        assertEquals(2, queries.size());
        assertTrue(queries.get(1).contains("collect iron ore"), queries.get(1));
        assertTrue(queries.get(1).contains("no pickaxe in the inventory"), queries.get(1));
    }

    @Test
    void perceptionReceivesTheTurnCaller() {
        java.util.List<dev.friendai.execution.tool.Caller> seen = new java.util.ArrayList<>();
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
                    public String describe(dev.friendai.execution.tool.Caller caller) {
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
        assertEquals(
                new dev.friendai.execution.tool.Caller(GUEST, "Bob", AccessLevel.TRUSTED), seen.get(seen.size() - 1));
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

    private static BrainEvent social(String... happenings) {
        return new BrainEvent.SocialOpening(
                BrainEvent.SocialOpening.Urgency.ORDINARY, List.of(happenings), "How was the trip?", true);
    }

    @Test
    void aSocialTurnOffersTheModelNoTools() {
        routedToolbox(false);
        gateway.enqueue(text("The light is good today."));
        brain.post(social());

        assertTrue(gateway.requests.get(0).tools().isEmpty(), "a social turn must not offer a tool");
        assertEquals(List.of("The light is good today."), speaker.said);
        assertTrue(tools.executed.isEmpty(), tools.executed.toString());
    }

    @Test
    void aSocialOpeningBatchedWithAWorkReportKeepsItsTools() {
        routedToolbox(false);
        CompletableFuture<LlmResponse> running = gateway.enqueuePending();
        brain.post(said("hi"));
        brain.post(social());
        brain.post(new BrainEvent.PlanBlocked(3, "stone kit", 1, "obtain_item", "no pickaxe here", 2));
        gateway.enqueue(text("On it."));
        running.complete(text("Hi"));

        assertEquals(2, gateway.requests.size());
        assertFalse(
                toolNames(gateway.requests.get(1)).isEmpty(),
                "asked to repair a plan, the model must be able to call queue_edit");
    }

    @Test
    void aSocialOpeningWithFinishedWorkStillSendsNoSchema() {
        routedToolbox(false);
        CompletableFuture<LlmResponse> running = gateway.enqueuePending();
        brain.post(said("hi"));
        brain.post(social());
        brain.post(new BrainEvent.TaskFinished(7, "mine_blocks", true, "mined 3 logs"));
        gateway.enqueue(text("Nice."));
        running.complete(text("Hi"));

        assertEquals(2, gateway.requests.size());
        assertTrue(toolNames(gateway.requests.get(1)).isEmpty(), "chatter about finished work spends no schema");
    }

    @Test
    void aToolCallTypedIntoTheReplyIsNeverDelivered() {
        gateway.enqueue(text("\n\n<tool_calls>\n<invoke name=\"find_trees\">\n<parameter name=\"radius\">32</parameter>"
                + "\n</invoke>\n</tool_calls>"));
        brain.post(social());

        assertTrue(speaker.said.isEmpty(), speaker.said.toString());
        assertTrue(speaker.addressees.isEmpty(), speaker.addressees.toString());
        assertFalse(brain.isThinking());
    }

    @Test
    void aSilentSocialReplySaysAndSpeaksNothing() {
        gateway.enqueue(text(ReplyGuard.SILENT));
        brain.post(new BrainEvent.SocialOpening(
                BrainEvent.SocialOpening.Urgency.HIGH, List.of("you died to a creeper"), "", false));

        assertTrue(speaker.said.isEmpty(), speaker.said.toString());
        assertTrue(speaker.addressees.isEmpty(), "nothing was spoken to anyone: " + speaker.addressees);
        assertFalse(brain.isThinking());
    }

    @Test
    void aFailedSocialRequestLeavesTheQueuedJobAlone() {
        CompletableFuture<LlmResponse> pending = gateway.enqueuePending();
        gateway.enqueue(text("Back to work."));
        brain.post(social());
        brain.post(new BrainEvent.TaskFinished(7, "mine_blocks", true, "mined 3 logs"));
        assertEquals(1, gateway.requests.size(), "the queued job waits behind the running social turn");

        pending.completeExceptionally(new LlmException(LlmErrorKind.AUTH, "bad key"));

        assertEquals(2, gateway.requests.size(), "the queued job still gets its turn");
        Message user = lastUser(gateway.requests.get(1));
        assertTrue(user.text().contains("task #7"), "the queued job reached the model: " + user.text());
        assertTrue(user.text().contains("mined 3 logs"), user.text());
        assertEquals(List.of("Back to work."), speaker.said);
        assertFalse(brain.isThinking());
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
