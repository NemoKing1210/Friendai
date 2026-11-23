package dev.friendai.foundation.movement;

/** The model-facing sentence for a finished orbit, built only from verified and blocked waypoint counts. */
public final class OrbitReport {
    private OrbitReport() {}

    public static String success(String label, int laps, OrbitCoverage coverage) {
        return "walked " + lapsText(laps) + " around the " + label + ": all " + coverage.total() + " waypoints verified"
                + bypassText(coverage);
    }

    /** A precise shortfall: what was covered, and why the orbit stopped. */
    public static String partial(String label, int laps, OrbitCoverage coverage, String reason) {
        return "covered " + coverage.verified() + " of " + coverage.total() + " waypoints of " + lapsText(laps)
                + " around the " + label + bypassText(coverage) + "; stopped: " + reason;
    }

    private static String bypassText(OrbitCoverage coverage) {
        int bypassed = coverage.bypassed();
        return bypassed == 0 ? "" : ", " + bypassed + " by a safe bypass";
    }

    private static String lapsText(int laps) {
        return laps + (laps == 1 ? " lap" : " laps");
    }
}
