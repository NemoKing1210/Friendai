package dev.friendai.features.mining.minecraft.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.features.mining.minecraft.task.ExcavateTask.StallFallback;
import org.junit.jupiter.api.Test;

/**
 * The bounded stall rule behind the {@code excavate} descent fallback. The live break needs a world and is covered by
 * the {@code excavation_tests} GameTests; here the rule and its typed refusal are pinned without one.
 */
class ExcavateFallbackTest {
    @Test
    void a_search_that_makes_no_progress_for_two_hundred_ticks_falls_back_to_the_cell_below() {
        StallFallback fallback = new StallFallback();

        for (int tick = 1; tick <= ExcavateTask.STALL_FALLBACK_TICKS; tick++) {
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
        for (int tick = 1; tick < ExcavateTask.STALL_FALLBACK_TICKS; tick++) {
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
        for (int tick = 1; tick <= ExcavateTask.STALL_FALLBACK_TICKS; tick++) {
            assertEquals(StallFallback.Decision.WAIT, fallback.observe(3, "descend"), "tick " + tick);
        }

        assertEquals(StallFallback.Decision.DIG, fallback.observe(3, "descend"));
    }

    @Test
    void the_fallback_ends_on_the_existing_typed_unreachable_message() {
        assertEquals(
                "friendai.mining.unreachable", ExcavateTask.fallbackRefusal().key());
        assertEquals("a cell cannot be reached safely", ExcavateTask.failureText(ExcavateTask.fallbackRefusal()));
    }
}
