package dev.friendai.agent.perception;

/** The companion's own body and shelter, with no Minecraft types. Text is English, LLM-facing. */
public record SelfStatus(
        boolean onGround,
        boolean inWater,
        boolean inLava,
        boolean onFire,
        boolean inWall,
        boolean seesSky,
        int lightLevel,
        String underfoot,
        int headroom) {

    /** Headroom is reported up to this many blocks, then as "4+". */
    public static final int MAX_HEADROOM = 4;

    public String toPromptText() {
        StringBuilder body = new StringBuilder();
        if (inWater) {
            body.append("in water, ");
        }
        if (inLava) {
            body.append("in lava, ");
        }
        if (onFire) {
            body.append("on fire, ");
        }
        if (inWall) {
            body.append("stuck inside a block, ");
        }
        body.append(onGround ? "on the ground" : "airborne");
        String room = headroom >= MAX_HEADROOM ? MAX_HEADROOM + "+" : Integer.toString(headroom);
        return "Self: " + body + "; light " + lightLevel + ", " + (seesSky ? "open sky" : "no sky above")
                + "; underfoot: " + underfoot + "; headroom " + room;
    }
}
