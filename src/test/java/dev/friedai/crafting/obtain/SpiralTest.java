package dev.friedai.crafting.obtain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpiralTest {
    @Test
    void waypointsExpandAroundTheStart() {
        Spiral spiral = new Spiral(0, 0, 16, 64);
        List<double[]> points = new ArrayList<>();
        spiral.next().ifPresent(points::add);
        spiral.next().ifPresent(points::add);
        spiral.next().ifPresent(points::add);
        spiral.next().ifPresent(points::add);
        spiral.next().ifPresent(points::add);
        assertEquals(5, points.size());
        assertPoint(points.get(0), 16, 0);
        assertPoint(points.get(1), 16, -16);
        assertPoint(points.get(2), -16, -16);
        assertPoint(points.get(3), -16, 16);
        assertPoint(points.get(4), 32, 16);
    }

    @Test
    void diagonalWaypointIsReachedAndTheRadiusIsRespected() {
        Spiral spiral = new Spiral(0, 0, 16, 64);
        boolean diagonal = false;
        int count = 0;
        while (true) {
            var point = spiral.next();
            if (point.isEmpty()) {
                break;
            }
            double[] p = point.get();
            assertTrue(Math.hypot(p[0], p[1]) <= 64, "waypoint outside the radius: " + p[0] + "," + p[1]);
            if (p[0] == 48 && p[1] == 32) {
                diagonal = true;
            }
            count++;
        }
        assertTrue(diagonal, "the spiral never reached the north-east diagonal");
        assertTrue(count > 5, "only " + count + " waypoints");
    }

    private static void assertPoint(double[] point, double x, double z) {
        assertEquals(x, point[0], 0.0001);
        assertEquals(z, point[1], 0.0001);
    }
}
