package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuardVocabularyTest {
    private static final class Facts implements GuardFacts {
        Boolean online;
        Boolean daytime;
        String weather = "";
        int food = -1;
        int xp = -1;
        Boolean station;
        Boolean harvest;
        Boolean plan;
        Boolean goal;

        @Override
        public int countItems(String idOrTag) {
            return 0;
        }

        @Override
        public int emptySlots() {
            return 0;
        }

        @Override
        public float healthFraction() {
            return 1f;
        }

        @Override
        public int ownerDistance() {
            return 0;
        }

        @Override
        public int countBlocks(String idOrTag, int radius) {
            return 0;
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }

        @Override
        public Boolean ownerOnline() {
            return online;
        }

        @Override
        public Boolean daytime() {
            return daytime;
        }

        @Override
        public String weather() {
            return weather;
        }

        @Override
        public int foodLevel() {
            return food;
        }

        @Override
        public int xpLevel() {
            return xp;
        }

        @Override
        public Boolean stationWithin(String station, int radius) {
            return this.station;
        }

        @Override
        public Boolean canHarvest(String block) {
            return harvest;
        }

        @Override
        public Boolean planDone(long planId) {
            return plan;
        }

        @Override
        public Boolean goalMet(long goalId) {
            return goal;
        }
    }

    @Test
    void ownerOnlineAndOfflineNeverTreatUnknownAsOffline() {
        Facts facts = new Facts();
        assertFalse(new Guard.OwnerOnline().holds(facts));
        assertFalse(new Guard.OwnerOffline().holds(facts), "unknown is not offline");

        facts.online = true;
        assertTrue(new Guard.OwnerOnline().holds(facts));
        assertFalse(new Guard.OwnerOffline().holds(facts));

        facts.online = false;
        assertFalse(new Guard.OwnerOnline().holds(facts));
        assertTrue(new Guard.OwnerOffline().holds(facts));
    }

    @Test
    void dayAndNightMirrorTheClockAndUnknownSatisfiesNeither() {
        Facts facts = new Facts();
        assertFalse(new Guard.Daytime().holds(facts));
        assertFalse(new Guard.Nighttime().holds(facts), "unknown is not night");

        facts.daytime = true;
        assertTrue(new Guard.Daytime().holds(facts));
        assertFalse(new Guard.Nighttime().holds(facts));

        facts.daytime = false;
        assertFalse(new Guard.Daytime().holds(facts));
        assertTrue(new Guard.Nighttime().holds(facts));
    }

    @Test
    void weatherIsExactAndUnknownIsFalse() {
        Facts facts = new Facts();
        assertFalse(new Guard.WeatherIs("clear").holds(facts), "unread weather is not clear");

        facts.weather = "clear";
        assertTrue(new Guard.WeatherIs("clear").holds(facts));
        assertFalse(new Guard.WeatherIs("rain").holds(facts));

        facts.weather = "thunder";
        assertTrue(new Guard.WeatherIs("thunder").holds(facts));
    }

    @Test
    void foodAndXpStayUnknownBelowZero() {
        Facts facts = new Facts();
        assertFalse(new Guard.FoodAtLeast(0).holds(facts), "unknown food is not zero");
        assertFalse(new Guard.XpAtLeast(0).holds(facts), "unknown xp is not zero");

        facts.food = 10;
        facts.xp = 5;
        assertTrue(new Guard.FoodAtLeast(10).holds(facts));
        assertFalse(new Guard.FoodAtLeast(11).holds(facts));
        assertTrue(new Guard.XpAtLeast(5).holds(facts));
        assertFalse(new Guard.XpAtLeast(6).holds(facts));
    }

    @Test
    void stationToolPlanAndGoalRequirePositiveEvidence() {
        Facts facts = new Facts();
        assertFalse(new Guard.StationWithin("minecraft:brewing_stand", 8).holds(facts));
        assertFalse(new Guard.ToolAtLeast("minecraft:iron_ore").holds(facts));
        assertFalse(new Guard.PlanDone(4).holds(facts));
        assertFalse(new Guard.GoalMet(4).holds(facts));

        facts.station = true;
        facts.harvest = true;
        facts.plan = true;
        facts.goal = true;
        assertTrue(new Guard.StationWithin("minecraft:brewing_stand", 8).holds(facts));
        assertTrue(new Guard.ToolAtLeast("minecraft:iron_ore").holds(facts));
        assertTrue(new Guard.PlanDone(4).holds(facts));
        assertTrue(new Guard.GoalMet(4).holds(facts));
    }

    @Test
    void everyNewGuardRoundTripsThroughJson() {
        List<Guard> guards = List.of(
                new Guard.OwnerOnline(),
                new Guard.OwnerOffline(),
                new Guard.Daytime(),
                new Guard.Nighttime(),
                new Guard.WeatherIs("rain"),
                new Guard.FoodAtLeast(12),
                new Guard.XpAtLeast(7),
                new Guard.StationWithin("minecraft:brewing_stand", 8),
                new Guard.ToolAtLeast("minecraft:iron_ore"),
                new Guard.PlanDone(9),
                new Guard.GoalMet(11));
        for (Guard guard : guards) {
            assertEquals(guard, Guard.parse(guard.toJson()).orElseThrow(), guard.describe());
            assertFalse(guard.describe().isBlank(), guard.getClass().getSimpleName());
        }
    }

    @Test
    void invalidShapesAreRejectedInsteadOfBecomingFalse() {
        assertTrue(Guard.parse(json("weather", "weather", "snow")).isEmpty(), "unknown weather");
        assertTrue(Guard.parse(json("food_at_least", "points", 21)).isEmpty());
        assertTrue(Guard.parse(json("xp_at_least", "levels", -1)).isEmpty());
        JsonObject badStation = new JsonObject();
        badStation.addProperty("type", "station_within");
        badStation.addProperty("station", "x");
        badStation.addProperty("radius", 33);
        assertTrue(Guard.parse(badStation).isEmpty());
        assertTrue(Guard.parse(json("plan_done", "plan", 0)).isEmpty());
        assertTrue(Guard.parse(json("goal_met", "goal", 0)).isEmpty());
        assertTrue(Guard.parse(new JsonObject()).isEmpty());
        JsonObject extra = new Guard.WeatherIs("clear").toJson();
        extra.addProperty("extra", 1);
        assertTrue(Guard.parse(extra).isEmpty(), "extra keys are not silently ignored");
    }

    @Test
    void childAndDepthLimitsStillApply() {
        JsonArray nine = new JsonArray();
        for (int i = 0; i < 9; i++) {
            nine.add(new Guard.OwnerOnline().toJson());
        }
        JsonObject group = new JsonObject();
        group.addProperty("type", "all_of");
        group.add("conditions", nine);
        assertTrue(Guard.parse(group).isEmpty());

        Guard nested = new Guard.OwnerOnline();
        for (int i = 0; i < Guard.MAX_DEPTH - 1; i++) {
            nested = new Guard.Not(nested);
        }
        JsonObject tooDeep = new JsonObject();
        tooDeep.addProperty("type", "not");
        tooDeep.add("condition", nested.toJson());
        assertTrue(Guard.parse(tooDeep).isEmpty());
    }

    private static JsonObject json(String type, String key, Object value) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        if (value instanceof String text) {
            json.addProperty(key, text);
        } else {
            json.addProperty(key, (Number) value);
        }
        return json;
    }
}
