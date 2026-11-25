package dev.friendai.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.agent.goal.Criterion;
import dev.friendai.agent.verify.Fact;
import dev.friendai.agent.verify.Outcome;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The durable outcome verdict must say whether anything was checked, and what. */
class SessionEventsOutcomeTest {
    private static final List<Fact> NO_FACTS = List.of();

    @Test
    void aQueuedJobThatSucceededSaysItWasNotChecked() {
        Map<String, String> data =
                SessionEvents.outcomeData(new Outcome(true, NO_FACTS, List.of()), true, List.of(), 3, 1);

        assertEquals("unchecked", data.get("verdict"));
        assertEquals("0", data.get("checked"));
        assertEquals("", data.get("criteria"));
    }

    @Test
    void aFailedJobStaysUnmet() {
        List<Criterion> criteria = List.of(new Criterion("12 logs", Optional.empty()));
        Map<String, String> data =
                SessionEvents.outcomeData(new Outcome(false, NO_FACTS, List.of()), false, criteria, 4, 1);

        assertEquals("unmet", data.get("verdict"));
        assertEquals("1", data.get("checked"), "the failed job still names what a plan owns");
    }

    @Test
    void aCheckedPlanNamesItsCriteria() {
        List<Criterion> criteria = List.of(
                new Criterion("2 stone pickaxes", Optional.empty()),
                new Criterion("the owner has one", Optional.empty()));
        Map<String, String> data = SessionEvents.outcomeData(
                new Outcome(true, List.of(new Fact("carried 2/2 minecraft:stone_pickaxe")), List.of()),
                true,
                criteria,
                5,
                1);

        assertEquals("met", data.get("verdict"));
        assertEquals("2", data.get("checked"));
        assertEquals("2 stone pickaxes; the owner has one", data.get("criteria"));
        assertEquals("carried 2/2 minecraft:stone_pickaxe", data.get("facts"));
    }

    @Test
    void anUnmetCriterionKeepsItsText() {
        List<Criterion> criteria = List.of(new Criterion("tell the owner", Optional.empty()));
        Map<String, String> data = SessionEvents.outcomeData(
                new Outcome(false, NO_FACTS, List.of("tell the owner")), true, criteria, 6, 1);

        assertEquals("unmet", data.get("verdict"));
        assertEquals("tell the owner", data.get("unmet"));
        assertEquals("1", data.get("checked"));
    }
}
