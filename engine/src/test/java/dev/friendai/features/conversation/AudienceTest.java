package dev.friendai.features.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AudienceTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void typedChatIsPublicAndVoiceIsPrivate() {
        assertEquals(Audience.PUBLIC, AudienceOrigin.of(false));
        assertEquals(Audience.PRIVATE, AudienceOrigin.of(true));
    }

    @Test
    void aPublicLineReachesEveryClientWhateverTheDistance() {
        assertTrue(AudiencePolicy.reaches(Audience.PUBLIC, GUEST, OWNER, false, 10_000, 16));
        assertTrue(AudiencePolicy.reaches(Audience.PUBLIC, STRANGER, OWNER, true, 2, 16));
    }

    @Test
    void aPrivateLineReachesOnlyItsSpeaker() {
        assertTrue(AudiencePolicy.reaches(Audience.PRIVATE, OWNER, OWNER, true, 1, 16));
        assertFalse(AudiencePolicy.reaches(Audience.PRIVATE, GUEST, OWNER, true, 1, 16));
        assertFalse(AudiencePolicy.reaches(Audience.PRIVATE, GUEST, OWNER, false, 10_000, 16));
    }

    @Test
    void aNearbyLineReachesOnlyPlayersInRangeInTheSameDimension() {
        assertTrue(AudiencePolicy.reaches(Audience.NEARBY, GUEST, OWNER, true, 16, 16));
        assertFalse(AudiencePolicy.reaches(Audience.NEARBY, GUEST, OWNER, true, 16.5, 16));
        assertFalse(AudiencePolicy.reaches(Audience.NEARBY, GUEST, OWNER, false, 1, 16));
    }

    @Test
    void aSpokenPublicReplyStaysNearbyAndAPrivateReplyStaysPrivate() {
        assertEquals(Audience.NEARBY, AudienceOrigin.speechFor(Audience.PUBLIC));
        assertEquals(Audience.NEARBY, AudienceOrigin.speechFor(Audience.NEARBY));
        assertEquals(Audience.PRIVATE, AudienceOrigin.speechFor(Audience.PRIVATE));
    }

    @Test
    void onlyAPrivateTurnKeepsItsChatLineOffOtherScreens() {
        assertEquals(Audience.PUBLIC, AudienceOrigin.chatFor(Audience.PUBLIC));
        assertEquals(Audience.PUBLIC, AudienceOrigin.chatFor(Audience.NEARBY));
        assertEquals(Audience.PRIVATE, AudienceOrigin.chatFor(Audience.PRIVATE));
    }

    @Test
    void missingFactsNeverDeliverAPrivateOrNearbyLine() {
        assertFalse(AudiencePolicy.reaches(null, GUEST, OWNER, true, 1, 16));
        assertFalse(AudiencePolicy.reaches(Audience.NEARBY, null, OWNER, true, 1, 16));
        assertFalse(AudiencePolicy.reaches(Audience.PRIVATE, GUEST, null, true, 1, 16));
    }

    @Test
    void aThreePlayerSessionSeparatesTheIntendedAudienceFromWhoCanHear() {
        List<AudiencePolicy.Candidate> candidates = List.of(
                new AudiencePolicy.Candidate(OWNER, true, 1),
                new AudiencePolicy.Candidate(GUEST, true, 8),
                new AudiencePolicy.Candidate(STRANGER, false, 1));

        assertEquals(
                List.of(OWNER, GUEST, STRANGER), AudiencePolicy.recipients(Audience.PUBLIC, OWNER, candidates, 16));
        assertEquals(List.of(OWNER), AudiencePolicy.recipients(Audience.PRIVATE, OWNER, candidates, 16));
        assertEquals(List.of(OWNER, GUEST), AudiencePolicy.recipients(Audience.NEARBY, OWNER, candidates, 16));
    }

    @Test
    void aNearbyLineNeverCrossesDimensionsAndAPublicOneDoes() {
        List<AudiencePolicy.Candidate> candidates =
                List.of(new AudiencePolicy.Candidate(OWNER, true, 2), new AudiencePolicy.Candidate(GUEST, false, 2));

        assertEquals(List.of(OWNER), AudiencePolicy.recipients(Audience.NEARBY, OWNER, candidates, 16));
        assertEquals(List.of(OWNER, GUEST), AudiencePolicy.recipients(Audience.PUBLIC, OWNER, candidates, 16));
    }
}
