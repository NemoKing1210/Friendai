package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StreamSpeechPolicyTest {
    private static StreamSpeechPolicy policy() {
        return StreamSpeechPolicy.create();
    }

    private static StreamSpeechPolicy.Speak spoken(StreamSpeechPolicy.Decision decision) {
        return assertInstanceOf(StreamSpeechPolicy.Speak.class, decision);
    }

    @Test
    void releasesASentenceThatIsSafeOnItsOwn() {
        StreamSpeechPolicy.Speak speak = spoken(policy().offer("The sparrow is on the fence.", true));

        assertEquals("The sparrow is on the fence.", speak.text());
        assertFalse(speak.last());
    }

    @Test
    void holdsAFragmentThatEndsAtALineBreak() {
        StreamSpeechPolicy policy = policy();

        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("The hive is north\n", true));
        assertEquals(
                "The hive is north of here.",
                spoken(policy.offer("of here.", true)).text());
    }

    @Test
    void holdsAFragmentThatEndsInsideADigitRun() {
        StreamSpeechPolicy policy = policy();

        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("It is at 1279 68 35", true));
        assertEquals("It is at 1279 68 358.", spoken(policy.offer("8.", true)).text());
    }

    @Test
    void holdsAFragmentThatEndsInsideAnAssignedCoordinate() {
        StreamSpeechPolicy policy = policy();

        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("x=1279 y=68", true));
        assertEquals("x=1279 y=68 z=30.", spoken(policy.offer(" z=30.", true)).text());
    }

    @Test
    void holdsAFragmentWithAnUnterminatedFence() {
        StreamSpeechPolicy policy = policy();

        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("```java\nnew Thing();", true));
    }

    @Test
    void holdsAColonTerminatedFragmentUntilTheSentenceEnds() {
        StreamSpeechPolicy policy = policy();

        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("Here is what I found:", true));
        StreamSpeechPolicy.Speak speak = spoken(policy.finish(" the hive.", true));
        assertEquals("Here is what I found: the hive.", speak.text());
        assertTrue(speak.last());
    }

    @Test
    void holdsWhileParityIsFalse() {
        StreamSpeechPolicy policy = policy();

        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("The sparrow is on the fence.", false));
    }

    @Test
    void releasesAFragmentRetriedWithParity() {
        StreamSpeechPolicy policy = policy();

        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("The sparrow is on the fence.", false));
        StreamSpeechPolicy.Speak speak = spoken(policy.offer("The sparrow is on the fence.", true));
        assertEquals("The sparrow is on the fence.", speak.text());
    }

    @Test
    void stopsWhenParityIsClaimedForTextThatWasNotVerified() {
        StreamSpeechPolicy policy = policy();

        policy.offer("The sparrow is on the fence.", false);
        assertInstanceOf(StreamSpeechPolicy.StopIncomplete.class, policy.offer(" A wren too.", true));
    }

    @Test
    void marksTheLastFragmentExactlyOnce() {
        StreamSpeechPolicy policy = policy();

        StreamSpeechPolicy.Speak speak = spoken(policy.finish("All done.", true));
        assertEquals("All done.", speak.text());
        assertTrue(speak.last());
        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.finish("All done.", true));
        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("Late text.", true));
    }

    @Test
    void stopsWhenTheFinalFragmentFailsParity() {
        StreamSpeechPolicy policy = policy();

        assertFalse(spoken(policy.offer("The sparrow is on the fence.", true)).last());
        assertInstanceOf(StreamSpeechPolicy.StopIncomplete.class, policy.finish(" and a wren.", false));
        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("Late text.", true));
    }

    @Test
    void releasesTextTheCallerReofferedOnlyOnce() {
        StreamSpeechPolicy policy = policy();

        policy.offer("The sparrow is on the fence.", true);
        StreamSpeechPolicy.Speak second = spoken(policy.offer("The sparrow is on the fence. A wren too.", true));
        assertEquals("A wren too.", second.text());
    }

    @Test
    void finishWithoutRemainingTextOnlyMarksTheEnd() {
        StreamSpeechPolicy policy = policy();

        spoken(policy.offer("The sparrow is on the fence.", true));
        StreamSpeechPolicy.Speak end = spoken(policy.finish("", true));
        assertEquals("", end.text());
        assertTrue(end.last());
    }

    @Test
    void blankFragmentsHold() {
        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy().offer("   ", true));
        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy().offer(null, true));
    }

    @Test
    void fullReofferAfterASpokenPrefixDoesNotDuplicate() {
        StreamSpeechPolicy policy = policy();
        assertInstanceOf(StreamSpeechPolicy.Speak.class, policy.offer("Hello.", true));
        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("Hello. ```unfinished", true));
        assertInstanceOf(StreamSpeechPolicy.StopIncomplete.class, policy.finish("Hello. ```unfinished", true));
    }

    @Test
    void waitsForSentenceWhenWordsArriveInGrowingFragments() {
        StreamSpeechPolicy policy = policy();
        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("Hel", true));
        assertInstanceOf(StreamSpeechPolicy.Hold.class, policy.offer("Hello wor", true));
        assertEquals("Hello world.", spoken(policy.offer("Hello world.", true)).text());
        assertEquals("", spoken(policy.finish("", true)).text());
    }
}
