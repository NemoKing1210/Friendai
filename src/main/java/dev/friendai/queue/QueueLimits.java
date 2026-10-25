package dev.friendai.queue;

public record QueueLimits(int maxJobs, int maxPlans, int maxStepsPerAdd, int maxArgsChars,
                          int maxArchives, int maxAddChars, int maxQueueArgsChars) {
    public static final QueueLimits DEFAULTS = new QueueLimits(64, 8, 32, 8192, 5);
    public QueueLimits(int maxJobs, int maxPlans, int maxStepsPerAdd, int maxArgsChars, int maxArchives) {
        this(maxJobs, maxPlans, maxStepsPerAdd, maxArgsChars, maxArchives, 49152, 65536);
    }
}
