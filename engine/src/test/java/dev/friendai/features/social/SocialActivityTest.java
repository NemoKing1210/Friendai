package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SocialActivityTest {
    @Test
    void eachCounterCountsOnlyItsOwnKind() {
        SocialActivity activity = new SocialActivity();
        activity.line(false);
        activity.line(false);
        activity.line(true);
        activity.held();
        activity.held();
        activity.dropped();
        activity.ownerSpoke();
        activity.ownerSpoke();
        activity.ownerSpoke();
        activity.event();
        activity.event();

        assertEquals(
                "social activity: 2 ordinary, 1 urgent, 2 held, 1 dropped lines; 3 owner messages; 2 events seen",
                activity.summary());
    }

    @Test
    void totalLinesCountsSpokenLinesAndNotHeldOrDroppedOnes() {
        SocialActivity activity = new SocialActivity();
        activity.held();
        activity.dropped();
        activity.ownerSpoke();
        activity.event();
        assertEquals(0, activity.totalLines());

        activity.line(true);
        activity.line(false);
        activity.line(false);
        assertEquals(3, activity.totalLines());
        assertEquals(1, activity.events());
    }

    @Test
    void eventCountsOnlyItselfAndNeverALine() {
        SocialActivity activity = new SocialActivity();
        activity.event();
        activity.event();
        activity.event();

        assertEquals(3, activity.events());
        assertEquals(0, activity.totalLines());
        assertEquals(
                "social activity: 0 ordinary, 0 urgent, 0 held, 0 dropped lines; 0 owner messages; 3 events seen",
                activity.summary());
    }

    @Test
    void summaryNamesEveryCounterInAFixedOrder() {
        SocialActivity activity = new SocialActivity();
        for (int i = 0; i < 3; i++) {
            activity.line(false);
        }
        activity.line(true);
        activity.held();
        activity.held();
        activity.dropped();
        for (int i = 0; i < 14; i++) {
            activity.ownerSpoke();
        }
        for (int i = 0; i < 4; i++) {
            activity.event();
        }

        assertEquals(
                "social activity: 3 ordinary, 1 urgent, 2 held, 1 dropped lines; 14 owner messages; 4 events seen",
                activity.summary());
        assertEquals(4, activity.totalLines());
        assertEquals(4, activity.events());
    }

    @Test
    void anEmptyWindowStillSummarisesToZeroes() {
        assertEquals(
                "social activity: 0 ordinary, 0 urgent, 0 held, 0 dropped lines; 0 owner messages; 0 events seen",
                new SocialActivity().summary());
        assertEquals(0, new SocialActivity().events());
    }

    @Test
    void resetClearsEveryCounter() {
        SocialActivity activity = new SocialActivity();
        activity.line(true);
        activity.line(false);
        activity.held();
        activity.dropped();
        activity.ownerSpoke();
        activity.event();

        activity.reset();

        assertEquals(0, activity.totalLines());
        assertEquals(0, activity.events());
        assertEquals(
                "social activity: 0 ordinary, 0 urgent, 0 held, 0 dropped lines; 0 owner messages; 0 events seen",
                activity.summary());
    }
}
