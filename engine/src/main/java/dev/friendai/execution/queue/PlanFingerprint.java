package dev.friendai.execution.queue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * A short, deterministic token for one plan's current work: the jobs, their order, tools, arguments and live state.
 * It is a stale-read check, not authority or persistence; a planner that computed a patch against an older token must
 * be refused rather than applied to a changed plan.
 */
public final class PlanFingerprint {
    public static final int LENGTH = 16;

    private PlanFingerprint() {}

    public static String of(JobQueue queue, long planId) {
        if (queue == null) throw new IllegalArgumentException("queue");
        StringBuilder text = new StringBuilder();
        var plan = queue.plan(planId).orElse(null);
        text.append(plan == null ? "missing" : plan.goal()).append('\n');
        queue.jobs().stream()
                .filter(job -> job.planId() == planId)
                .sorted(Comparator.comparingLong(Job::id))
                .forEach(job -> text.append(job.id())
                        .append('|')
                        .append(job.tool())
                        .append('|')
                        .append(job.state())
                        .append('|')
                        .append(job.attempts())
                        .append('|')
                        .append(job.args())
                        .append('|')
                        .append(job.after())
                        .append('\n'));
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
