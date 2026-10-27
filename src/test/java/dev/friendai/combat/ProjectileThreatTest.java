package dev.friendai.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProjectileThreatTest {
    private static final ProjectileThreat.EntityBounds BODY =
            ProjectileThreat.EntityBounds.around(0, 0, 0, 0.3, 1.8);

    @Test
    void arrowTowardCompanionIntersectsBounds() {
        ProjectileThreat.Facts arrow = ProjectileThreat.Facts.of(
                -5, 1.0, 0, 0.5, 0, 0, 0.1, true, "minecraft:arrow");
        assertTrue(ProjectileThreat.intersects(arrow, BODY, 20));
    }

    @Test
    void arrowPassingBesideCompanionIsIgnored() {
        ProjectileThreat.Facts arrow = ProjectileThreat.Facts.of(
                -5, 1.0, 3.0, 0.5, 0, 0, 0.1, true, "minecraft:arrow");
        assertFalse(ProjectileThreat.intersects(arrow, BODY, 20));
    }

    @Test
    void movingProjectileUsesBoundedHorizon() {
        ProjectileThreat.Facts slow = ProjectileThreat.Facts.of(
                -30, 1.0, 0, 0.5, 0, 0, 0.1, true, "minecraft:arrow");
        assertFalse(ProjectileThreat.intersects(slow, BODY, 20));
        assertTrue(ProjectileThreat.intersects(slow, BODY, 100));
        assertFalse(ProjectileThreat.intersects(slow, BODY, 0));
    }

    @Test
    void rearAttackIsNotAFrontalBlock() {
        assertTrue(ProjectileThreat.isFrontal(5, 0, 0, 0, 1, 0));
        assertFalse(ProjectileThreat.isFrontal(-5, 0, 0, 0, 1, 0));
        // Exactly at the side is outside the 60-degree frontal cone.
        assertFalse(ProjectileThreat.isFrontal(0, 5, 0, 0, 1, 0));
    }

    @Test
    void unverifiedProjectileTypeCannotBeDeflected() {
        ProjectileThreat.Facts arrow = ProjectileThreat.Facts.of(
                -5, 1, 0, 0.5, 0, 0, 0.1, true, "minecraft:arrow");
        ProjectileThreat.Facts fireball = ProjectileThreat.Facts.of(
                -5, 1, 0, 0.5, 0, 0, 0.5, true, "minecraft:fireball");
        assertFalse(ProjectileThreat.deflectable(arrow));
        assertFalse(ProjectileThreat.deflectable(fireball),
                "26.3 has no verified PathfinderMob fireball deflection hook");
    }

    @Test
    void boundsInflateByProjectileRadius() {
        ProjectileThreat.Facts wide = ProjectileThreat.Facts.of(
                -5, 1.0, 0.6, 0.5, 0, 0, 0.2, true, "minecraft:arrow");
        assertFalse(ProjectileThreat.intersects(wide, BODY, 20));
        assertTrue(ProjectileThreat.intersects(wide, BODY.inflate(0.2), 20));
        assertEquals(-0.5, BODY.inflate(0.2).minX(), 1e-9);
    }
}
