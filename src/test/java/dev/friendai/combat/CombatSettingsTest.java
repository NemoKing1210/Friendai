package dev.friendai.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.task.combat.CombatMode;
import org.junit.jupiter.api.Test;

class CombatSettingsTest {

    @Test
    void legacyModesMapToDocumentedSettings() {
        CombatSettings defensive = CombatMode.DEFENSIVE.toSettings();
        assertEquals(ProtectionPolicy.BODYGUARD, defensive.protection());
        assertEquals(FightingStyle.BALANCED, defensive.style());
        assertFalse(defensive.allowSplashPotions());
        assertEquals(24, defensive.pursuitLimitBlocks());
        assertEquals(RetreatPreference.STANDARD, defensive.retreatPreference());

        assertEquals(ProtectionPolicy.GUARD, CombatMode.GUARD.toSettings().protection());
        assertEquals(
                ProtectionPolicy.AVOID_COMBAT, CombatMode.PASSIVE.toSettings().protection());
        assertEquals(FightingStyle.BALANCED, CombatMode.GUARD.toSettings().style());
    }

    @Test
    void defaultsDisablePotionsAndUseTwentyFourBlockPursuit() {
        CombatSettings defaults = CombatSettings.defaults();
        assertEquals(ProtectionPolicy.BODYGUARD, defaults.protection());
        assertEquals(FightingStyle.BALANCED, defaults.style());
        assertFalse(defaults.allowSplashPotions());
        assertEquals(24, defaults.pursuitLimitBlocks());
        assertEquals(RetreatPreference.STANDARD, defaults.retreatPreference());
    }

    @Test
    void unknownSettingLabelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProtectionPolicy.parseStrict("friendship"));
        assertThrows(IllegalArgumentException.class, () -> FightingStyle.parseStrict("berserk"));
        assertThrows(IllegalArgumentException.class, () -> RetreatPreference.parseStrict("panic"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CombatSettings.decode(
                        "protection=friendship;style=balanced;splashPotions=false;pursuit=24;retreat=standard"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CombatSettings.decode(
                        "protection=bodyguard;style=balanced;splashPotions=maybe;pursuit=24;retreat=standard"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CombatSettings.decode(
                        "protection=bodyguard;style=balanced;splashPotions=false;pursuit=99;retreat=standard"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CombatSettings.decode("protection=bodyguard;style=balanced;splashPotions=false;pursuit=24"));
    }

    @Test
    void encodeRoundTripsAndMalformedDataFallsBackAsAWhole() {
        CombatSettings settings = new CombatSettings(
                ProtectionPolicy.SELF_ONLY, FightingStyle.RANGED, true, 40, RetreatPreference.CONSERVATIVE);
        assertEquals(settings, CombatSettings.decode(settings.encode()));
        assertEquals(CombatSettings.defaults(), CombatSettings.decodeOrDefault("garbage"));
        assertEquals(CombatSettings.defaults(), CombatSettings.decodeOrDefault(null));
        assertEquals(
                CombatSettings.defaults(),
                CombatSettings.decodeOrDefault(
                        "protection=bodyguard;style=balanced;splashPotions=false;pursuit=99;retreat=standard"));
    }

    @Test
    void pursuitLimitIsBounded() {
        assertThrows(
                IllegalArgumentException.class, () -> CombatSettings.defaults().withPursuitLimit(7));
        assertThrows(
                IllegalArgumentException.class, () -> CombatSettings.defaults().withPursuitLimit(65));
        assertTrue(CombatSettings.defaults().withPursuitLimit(8).pursuitLimitBlocks() == 8);
        assertTrue(CombatSettings.defaults().withPursuitLimit(64).pursuitLimitBlocks() == 64);
    }
}
