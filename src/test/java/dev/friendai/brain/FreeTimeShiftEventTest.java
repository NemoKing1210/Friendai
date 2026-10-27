package dev.friendai.brain;

import static dev.friendai.testutil.FakeGateway.call;
import static dev.friendai.testutil.FakeGateway.calls;
import static dev.friendai.testutil.FakeGateway.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import dev.friendai.tool.AccessLevel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FreeTimeShiftEventTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static BrainEvent.FreeTimeShift event() {
        return new BrainEvent.FreeTimeShift(
                List.of("- GEAR: gear for stage STONE: missing stone pickaxe (urgency 55)"),
                "Development: stage STONE; missing stone pickaxe",
                List.of("chop wood: ok, 16 logs"),
                List.of("mine_blocks failed: no pickaxe"),
                "focus on food");
    }

    @Test
    void promptLineListsAgendaAndAsksForOneCall() {
        String line = event().toPromptLine();
        assertTrue(line.startsWith("[event] free time: you decide what to do next."), line);
        assertTrue(line.contains("Agenda:\n- GEAR: gear for stage STONE"), line);
        assertTrue(line.contains("Development: stage STONE"), line);
        assertTrue(line.contains("Recent shifts:\n- chop wood: ok, 16 logs"), line);
        assertTrue(line.contains("Lessons:\n- mine_blocks failed: no pickaxe"), line);
        assertTrue(line.contains("Owner wish: focus on food"), line);
        assertTrue(line.contains("Call choose_activity exactly once with one concrete activity and the agenda need it "
                + "serves, or reply exactly [silent] if nothing is worth doing."), line);
        assertTrue(line.endsWith("Free time happens on its own: the owner is not a destination. Never walk to them, "
                + "and do not put approaching or following the owner into the plan."), line);
    }

    @Test
    void emptySectionsAreLeftOut() {
        String line = new BrainEvent.FreeTimeShift(List.of(), "Development: stage WOOD", List.of(), List.of(), "")
                .toPromptLine();
        assertTrue(!line.contains("Recent shifts:") && !line.contains("Lessons:") && !line.contains("Owner wish:"),
                line);
    }

    @Test
    void theShiftTurnRunsChooseActivityAsTheOwner() {
        FakeGateway gateway = new FakeGateway();
        FakeToolExecutor tools = new FakeToolExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        CompanionBrain brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(8, 30, 0, 5_000),
                () -> gateway,
                tools,
                () -> "state",
                new RecordingSpeaker(),
                scheduler,
                AccessResolver.of(OWNER, p -> false));
        gateway.enqueue(calls("", call("c1", "choose_activity")));
        gateway.enqueue(text("[silent]"));
        brain.post(event());
        assertEquals(List.of("choose_activity"), tools.executed);
        assertEquals(AccessLevel.OWNER, tools.executeAccess.get(0));
        assertEquals(1, gateway.requests.size(), "a chosen activity ends the director turn: one request");
    }

    @Test
    void speechIsDroppedWhileTheOwnerIsAway() {
        RecordingSpeaker inner = new RecordingSpeaker();
        boolean[] online = {false};
        Speaker quiet = new OwnerPresentSpeaker(inner, () -> online[0]);
        quiet.say("hello", OWNER);
        quiet.say("plain");
        assertTrue(inner.said.isEmpty(), "nobody to talk to: " + inner.said);
        online[0] = true;
        quiet.say("hello", OWNER);
        assertEquals(List.of("hello"), inner.said);
    }
}
