package dev.friendai.features.creative;

/** Pure authorization rules shared by the server transition service and task guards. */
public final class CreativePolicy {
    private CreativePolicy() {}

    public static boolean canEnable(CreativePermissions permissions) {
        return permissions != null && permissions.owner() && permissions.online() && permissions.activationAllowed();
    }

    /** Owners may revoke creative after losing operator rights, but must be online to issue the change. */
    public static boolean canDisable(CreativePermissions permissions) {
        return permissions != null && permissions.owner() && permissions.online();
    }

    public static boolean canAct(CreativeMode mode, CreativePermissions permissions, CreativeAction action) {
        return mode == CreativeMode.CREATIVE && action != null && canEnable(permissions);
    }

    /** Damage and hunger immunity belong to the entity mode, not the current player's online status. */
    public static boolean hasCreativeImmunity(CreativeMode mode) {
        return mode == CreativeMode.CREATIVE;
    }
}
