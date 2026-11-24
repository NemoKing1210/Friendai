package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.foundation.item.Tier;
import org.junit.jupiter.api.Test;

class ToolChargeTest {
    @Test
    void aFreshToolBreaksTheVanillaDurabilityOfItsKindAndTier() {
        assertEquals(59, ToolCharge.perTool(new ToolNeed(ToolKind.PICKAXE, Tier.WOOD)));
        assertEquals(131, ToolCharge.perTool(new ToolNeed(ToolKind.PICKAXE, Tier.STONE)));
        assertEquals(250, ToolCharge.perTool(new ToolNeed(ToolKind.PICKAXE, Tier.IRON)));
        assertEquals(1_561, ToolCharge.perTool(new ToolNeed(ToolKind.PICKAXE, Tier.DIAMOND)));
        assertEquals(2_031, ToolCharge.perTool(new ToolNeed(ToolKind.PICKAXE, Tier.NETHERITE)));
        assertEquals(238, ToolCharge.perTool(new ToolNeed(ToolKind.SHEARS, Tier.NONE)));
    }

    @Test
    void aNeedWithoutAToolHasNoKnownCharge() {
        assertEquals(0, ToolCharge.perTool(ToolNeed.HAND));
        assertEquals(0, ToolCharge.perTool(null));
    }

    @Test
    void aTripAwayKeepsAReserveAndAJobHereDoesNot() {
        assertEquals(40, ToolCharge.required(40, false));
        assertEquals(40 + ToolCharge.APPROACH_RESERVE, ToolCharge.required(40, true));
        assertEquals(0, ToolCharge.required(-3, false));
        assertEquals(ToolCharge.APPROACH_RESERVE, ToolCharge.required(-3, true));
    }
}
