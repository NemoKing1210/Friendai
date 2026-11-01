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

        assertEquals(
                "social activity: 2 ordinary, 1 urgent, 2 held, 1 dropped lines; 3 owner messages", activity.summary());
    }

    @Test
    void totalLinesCountsSpokenLinesAndNotHeldOrDroppedOnes() {
        SocialActivity activity = new SocialActivity();
        activity.held();
        activity.dropped();
        activity.ownerSpoke();
        assertEquals(0, activity.totalLines());

        activity.line(true);
        activity.line(false);
        activity.line(false);
        assertEquals(3, activity.totalLines());
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

        assertEquals(
                "social activity: 3 ordinary, 1 urgent, 2 held, 1 dropped lines; 14 owner messages",
                activity.summary());
        assertEquals(4, activity.totalLines());
    }

    @Test
    void anEmptyWindowStillSummarisesToZeroes() {
        assertEquals(
                "social activity: 0 ordinary, 0 urgent, 0 held, 0 dropped lines; 0 owner messages",
                new SocialActivity().summary());
    }

    @Test
    void resetClearsEveryCounter() {
        SocialActivity activity = new SocialActivity();
        activity.line(true);
        activity.line(false);
        activity.held();
        activity.dropped();
        activity.ownerSpoke();

        activity.reset();

        assertEquals(0, activity.totalLines());
        assertEquals(
                "social activity: 0 ordinary, 0 urgent, 0 held, 0 dropped lines; 0 owner messages", activity.summary());
    }
}
