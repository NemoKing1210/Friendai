package dev.friendai.agent.perception;

import dev.friendai.features.survival.selfcare.SelfCareEffect;
import dev.friendai.features.survival.selfcare.SelfCareStock;
import java.util.List;
import java.util.Locale;

/** The companion's own body, its effects and the items it keeps for an emergency. No Minecraft types. */
public record SelfStatus(
        boolean onGround,
        boolean inWater,
        boolean inLava,
        boolean onFire,
        boolean inWall,
        boolean seesSky,
        int lightLevel,
        String underfoot,
        int headroom,
        float healthFraction,
        float fallDistance,
        List<SelfCareEffect> effects,
        SelfCareStock stock) {

    /** Headroom is reported up to this many blocks, then as "4+". */
    public static final int MAX_HEADROOM = 4;

    /** A body with nothing to report: full health, no effects and no emergency stock. */
    public SelfStatus(
            boolean onGround,
            boolean inWater,
            boolean inLava,
            boolean onFire,
            boolean inWall,
            boolean seesSky,
            int lightLevel,
            String underfoot,
            int headroom) {
        this(
                onGround,
                inWater,
                inLava,
                onFire,
                inWall,
                seesSky,
                lightLevel,
                underfoot,
                headroom,
                1.0F,
                0.0F,
                List.of(),
                SelfCareStock.EMPTY);
    }

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
        StringBuilder line = new StringBuilder()
                .append("Self: ")
                .append(body)
                .append("; light ")
                .append(lightLevel)
                .append(", ")
                .append(seesSky ? "open sky" : "no sky above")
                .append("; underfoot: ")
                .append(underfoot)
                .append("; headroom ")
                .append(room)
                .append("; health ")
                .append(Math.round(healthFraction * 100.0F))
                .append('%');
        if (!onGround && fallDistance > 0.0F) {
            line.append("; falling ")
                    .append(String.format(Locale.ROOT, "%.1f", fallDistance))
                    .append(" blocks");
        }
        if (!effects.isEmpty()) {
            line.append('\n').append("Effects: ");
            for (int i = 0; i < effects.size(); i++) {
                if (i > 0) {
                    line.append(", ");
                }
                line.append(effects.get(i).toPromptText());
            }
        }
        String carried = stock.toPromptText();
        if (!carried.isEmpty()) {
            line.append('\n').append("Emergency: ").append(carried);
        }
        return line.toString();
    }
}
