package dev.friendai.features.mining.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.mining.minecraft.mining.MiningSession;
import dev.friendai.features.mining.minecraft.mining.MiningSession.DescentRules;
import dev.friendai.features.mining.minecraft.task.ExcavateTask.StallFallback;
import org.junit.jupiter.api.Test;

/**
 * The bounded stall rule behind the {@code excavate} descent fallback. The live break needs a world and is covered by
 * the {@code excavation_tests} GameTests; here the rule, the task's cell rules and its typed refusal are pinned without
 * one.
 */
class ExcavateFallbackTest {
    @Test
    void a_search_that_makes_no_progress_for_two_hundred_ticks_falls_back_to_the_cell_below() {
        StallFallback fallback = new StallFallback();

        for (int tick = 1; tick < ExcavateTask.STALL_FALLBACK_TICKS; tick++) {
            assertEquals(StallFallback.Decision.WAIT, fallback.observe(8, "approach"), "tick " + tick);
        }

        assertEquals(StallFallback.Decision.DIG, fallback.observe(8, "approach"));
    }

    @Test
    void a_broken_block_restarts_the_stall_window() {
        StallFallback fallback = new StallFallback();
        for (int tick = 0; tick < ExcavateTask.STALL_FALLBACK_TICKS; tick++) {
            fallback.observe(8, "approach");
        }

        assertEquals(StallFallback.Decision.WAIT, fallback.observe(9, "approach"));
        for (int tick = 2; tick < ExcavateTask.STALL_FALLBACK_TICKS; tick++) {
            assertEquals(StallFallback.Decision.WAIT, fallback.observe(9, "approach"), "tick " + tick);
        }

        assertEquals(StallFallback.Decision.DIG, fallback.observe(9, "approach"));
    }

    @Test
    void only_a_stalled_approach_or_descent_counts() {
        StallFallback fallback = new StallFallback();

        for (int tick = 0; tick < ExcavateTask.STALL_FALLBACK_TICKS * 2; tick++) {
            assertEquals(StallFallback.Decision.WAIT, fallback.observe(3, "extract"));
            assertEquals(StallFallback.Decision.WAIT, fallback.observe(3, "settle"));
            assertEquals(StallFallback.Decision.WAIT, fallback.observe(3, "tunnel"));
        }
        for (int tick = 1; tick < ExcavateTask.STALL_FALLBACK_TICKS; tick++) {
            assertEquals(StallFallback.Decision.WAIT, fallback.observe(3, "descend"), "tick " + tick);
        }

        assertEquals(StallFallback.Decision.DIG, fallback.observe(3, "descend"));
    }

    @Test
    void a_descent_follows_the_tasks_own_cell_rules() {
        // The safe defaults refuse a container, a liquid and a block that needs the wrong tool.
        assertFalse(MiningSession.descentAllowed(DescentRules.SAFE, true, false, false, true));
        assertFalse(MiningSession.descentAllowed(DescentRules.SAFE, false, true, false, true));
        assertFalse(MiningSession.descentAllowed(DescentRules.SAFE, false, false, true, false));
        // A task that opted in allows each of them, and a cell with no special rule is always allowed.
        assertTrue(MiningSession.descentAllowed(new DescentRules(true, false, true), true, false, false, true));
        assertTrue(MiningSession.descentAllowed(new DescentRules(false, true, true), false, true, false, true));
        assertTrue(MiningSession.descentAllowed(new DescentRules(false, false, false), false, false, true, false));
        assertTrue(MiningSession.descentAllowed(DescentRules.SAFE, false, false, false, true));
    }

    @Test
    void the_fallback_ends_on_the_existing_typed_unreachable_message() {
        assertEquals(
                "friendai.mining.unreachable", ExcavateTask.fallbackRefusal().key());
        assertEquals("a cell cannot be reached safely", ExcavateTask.failureText(ExcavateTask.fallbackRefusal()));
    }
}
