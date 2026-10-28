package dev.friendai.features.navigation.movement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MovementOrdersTest {
    @Test
    void theThreeOrdersAreOrders() {
        assertTrue(MovementOrders.isOrder("stay"));
        assertTrue(MovementOrders.isOrder("follow_player"));
        assertTrue(MovementOrders.isOrder("go_to"));
    }

    @Test
    void workIsNotAnOrder() {
        assertFalse(MovementOrders.isOrder("mine_blocks"));
        assertFalse(MovementOrders.isOrder("jump"));
        assertFalse(MovementOrders.isOrder(""));
        assertFalse(MovementOrders.isOrder(null));
    }
}
