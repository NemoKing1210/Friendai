package dev.friendai.protocol.speech;

/** What the server decided about one admitted voice transcript; correlated by the request's id. */
public record SpeechAcknowledgement(Outcome outcome) {
    public SpeechAcknowledgement {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
    }

    public enum Outcome {
        /** The line reached the companion (or the pending trust question) and is being handled. */
        ACCEPTED(1),
        /** The transcript does not fit the 500-byte wire limit; the client refuses it first, the server keeps a guard. */
        TOO_LONG(2),
        /** The per-player voice rate gate refused this transcript. */
        RATE_LIMITED(3),
        /** The speaker owns no companion on this server. */
        NO_COMPANION(4),
        /** The speaker's companion exists but is out of hearing range. */
        TOO_FAR(5);

        private final int id;

        Outcome(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Outcome fromId(int id) {
            for (Outcome outcome : values()) {
                if (outcome.id == id) {
                    return outcome;
                }
            }
            return null;
        }
    }
}
