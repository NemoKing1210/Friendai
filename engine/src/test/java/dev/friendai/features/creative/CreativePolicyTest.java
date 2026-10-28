package dev.friendai.features.creative;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CreativePolicyTest {
    private static final CreativePermissions AUTHORIZED_OWNER = new CreativePermissions(true, true, true);

    @Test
    void creativeActionsAreOffInSurvival() {
        for (CreativeAction action : CreativeAction.values()) {
            assertFalse(CreativePolicy.canAct(CreativeMode.SURVIVAL, AUTHORIZED_OWNER, action), action.name());
        }
        assertFalse(CreativePolicy.hasCreativeImmunity(CreativeMode.SURVIVAL));
    }

    @Test
    void activationRequiresAnOnlineOwnerWithCurrentActivationRights() {
        assertTrue(CreativePolicy.canEnable(AUTHORIZED_OWNER));
        assertFalse(CreativePolicy.canEnable(new CreativePermissions(false, true, true)));
        assertFalse(CreativePolicy.canEnable(new CreativePermissions(true, false, true)));
        assertFalse(CreativePolicy.canEnable(new CreativePermissions(true, true, false)));
    }

    @Test
    void ownerMayDisableAfterLosingActivationRights() {
        assertTrue(CreativePolicy.canDisable(new CreativePermissions(true, true, false)));
        assertFalse(CreativePolicy.canDisable(new CreativePermissions(false, true, false)));
        assertFalse(CreativePolicy.canDisable(new CreativePermissions(true, false, false)));
    }

    @Test
    void actionRightsAreRecheckedIndependentlyFromIntrinsicImmunity() {
        assertTrue(CreativePolicy.hasCreativeImmunity(CreativeMode.CREATIVE));
        for (CreativeAction action : CreativeAction.values()) {
            assertTrue(CreativePolicy.canAct(CreativeMode.CREATIVE, AUTHORIZED_OWNER, action), action.name());
            assertFalse(
                    CreativePolicy.canAct(CreativeMode.CREATIVE, new CreativePermissions(false, true, true), action),
                    action.name());
            assertFalse(
                    CreativePolicy.canAct(CreativeMode.CREATIVE, new CreativePermissions(true, false, true), action),
                    action.name());
            assertFalse(
                    CreativePolicy.canAct(CreativeMode.CREATIVE, new CreativePermissions(true, true, false), action),
                    action.name());
        }
    }
}
