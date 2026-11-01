package dev.friendai.features.combat;

import java.util.Set;

/**
 * The pure threat table. Minecraft decides what a mob is; this decides whether the companion may attack it, must
 * avoid it, or may ignore it. Every id is a vanilla block/entity id, never a class name guessed from memory.
 */
public final class ThreatRules {
    /** Mobs that are never targets, only dangers. */
    public static final Set<String> AVOID = Set.of("minecraft:warden", "minecraft:ender_dragon");

    /** Mobs that are neutral until they are hit or target the companion/owner. */
    public static final Set<String> PROVOKED_ONLY =
            Set.of("minecraft:zombified_piglin", "minecraft:piglin", "minecraft:enderman");

    /** Mobs whose nearby kin make a fight a group fight. */
    public static final Set<String> GROUP_RISK = Set.of("minecraft:zombified_piglin", "minecraft:piglin");

    private static final String SPIDER = "minecraft:spider";

    private ThreatRules() {}

    public record Decision(ThreatClass type, boolean attackable, boolean danger, String reason) {}

    /**
     * @param id vanilla entity id, for example {@code minecraft:zombified_piglin}
     * @param enemy whether the game classifies the mob as an {@code Enemy}
     * @param targetingUs whether its current target is the companion or the owner
     * @param hitUsRecently whether it hit either of them inside the provocation window
     * @param daylight whether it is currently day outside
     * @param ownerOrder whether the owner explicitly ordered an attack on this kind
     * @param groupRisk whether at least one same-kind mob stands nearby
     */
    public static Decision decide(
            String id,
            boolean enemy,
            boolean targetingUs,
            boolean hitUsRecently,
            boolean daylight,
            boolean ownerOrder,
            boolean groupRisk) {
        if (AVOID.contains(id)) {
            return new Decision(ThreatClass.AVOID, false, true, ThreatClass.AVOID.reason());
        }
        boolean provoked = targetingUs || hitUsRecently;
        if (SPIDER.equals(id)) {
            if (daylight && !provoked && !ownerOrder) {
                return new Decision(ThreatClass.PROVOKED_ONLY, false, false, "neutral in daylight");
            }
            return new Decision(ThreatClass.HOSTILE, true, true, "spider away from daylight");
        }
        if (PROVOKED_ONLY.contains(id)) {
            if (groupRisk && !ownerOrder) {
                return new Decision(ThreatClass.AVOID, false, true, "group risk; avoid unless ordered");
            }
            if (provoked || ownerOrder) {
                return new Decision(ThreatClass.HOSTILE, true, true, "provoked");
            }
            return new Decision(ThreatClass.PROVOKED_ONLY, false, false, ThreatClass.PROVOKED_ONLY.reason());
        }
        if (enemy) {
            return new Decision(ThreatClass.HOSTILE, true, true, ThreatClass.HOSTILE.reason());
        }
        return new Decision(ThreatClass.IGNORE, false, false, ThreatClass.IGNORE.reason());
    }
}
