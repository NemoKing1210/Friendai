package dev.friendai.features.mining.minecraft.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.places.PlaceKind;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class MiningRefusalTest {
    private static final BlockPos POS = new BlockPos(24, 68, 17);

    @Test
    void theServerIsNamedWhenTheLevelRefused() {
        MiningRefusal refusal = new MiningRefusal.ServerProtected(POS);
        assertTrue(refusal.describe().contains("24 68 17"), refusal.describe());
        assertTrue(refusal.describe().contains("server"), refusal.describe());
        assertFalse(refusal.describe().contains("place"), refusal.describe());
        assertFalse(refusal.remedy().isBlank());
    }

    @Test
    void aPlaceIsNamedWithItsKindAndRadius() {
        MiningRefusal refusal = new MiningRefusal.PlaceProtected(POS, "home", PlaceKind.HOUSE, 32, 24, 17);
        assertTrue(refusal.describe().contains("place \"home\""), refusal.describe());
        assertTrue(refusal.describe().contains("house"), refusal.describe());
        assertTrue(refusal.describe().contains("r=32"), refusal.describe());
        assertTrue(refusal.remedy().startsWith("mine outside home"), refusal.remedy());
    }

    @Test
    void theRemedyPointsFromTheBlockToTheEdgeOfThePlace() {
        MiningRefusal.PlaceProtected eastEdge =
                new MiningRefusal.PlaceProtected(new BlockPos(4, 64, 0), "home", PlaceKind.HOUSE, 5, 0, 0);
        assertEquals("mine outside home: 2 blocks east", eastEdge.remedy());
        MiningRefusal.PlaceProtected deepInside =
                new MiningRefusal.PlaceProtected(new BlockPos(0, 64, 1), "home", PlaceKind.HOUSE, 5, 0, 0);
        assertTrue(deepInside.remedy().contains("blocks"), deepInside.remedy());
    }

    @Test
    void theBlockUnderTheFeetIsNotAProtectionRule() {
        MiningRefusal refusal = new MiningRefusal.OwnSupport(POS, true);
        assertTrue(refusal.describe().contains("standing on"), refusal.describe());
        assertFalse(refusal.describe().contains("protected"), refusal.describe());
        assertFalse(refusal.remedy().isBlank());
    }

    @Test
    void anUnsafeSupportSaysWhyItIsUnsafe() {
        MiningRefusal refusal = new MiningRefusal.OwnSupport(POS, false);
        assertTrue(refusal.describe().contains("not safe"), refusal.describe());
        assertTrue(refusal.remedy().contains("neighbouring"), refusal.remedy());
    }

    @Test
    void theBreakListAndAnUnbreakableBlockNameThemselves() {
        assertTrue(new MiningRefusal.Blacklisted(POS, "minecraft:bedrock")
                .describe()
                .contains("break list"));
        assertTrue(new MiningRefusal.Blacklisted(POS, "minecraft:bedrock")
                .describe()
                .contains("minecraft:bedrock"));
        assertTrue(new MiningRefusal.Unbreakable(POS, "minecraft:bedrock")
                .describe()
                .contains("cannot be broken"));
    }

    @Test
    void noReachCarriesTheReasonWhenThereIsOne() {
        assertTrue(new MiningRefusal.OutOfReach(POS, "").describe().contains("can't reach"));
        assertTrue(new MiningRefusal.OutOfReach(POS, "no route").describe().contains("no route"));
    }
}
