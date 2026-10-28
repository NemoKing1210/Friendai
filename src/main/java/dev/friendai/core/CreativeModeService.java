package dev.friendai.core;

import dev.friendai.creative.CreativeAction;
import dev.friendai.creative.CreativeActionContext;
import dev.friendai.creative.CreativeMode;
import dev.friendai.creative.CreativePermissions;
import dev.friendai.creative.CreativePolicy;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionLifeData;
import dev.friendai.entity.CompanionManager;
import dev.friendai.net.Operators;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread transition boundary for the owner-persisted creative preference. */
public final class CreativeModeService {
    public enum Result {
        CHANGED,
        UNCHANGED,
        DENIED
    }

    private CreativeModeService() {}

    public static Result requestMode(CompanionEntity companion, ServerPlayer actor, boolean enabled) {
        if (companion == null
                || actor == null
                || !(companion.level() instanceof ServerLevel level)
                || actor.level().getServer() != level.getServer()
                || companion.ownerUuid() == null
                || !companion.ownerUuid().equals(actor.getUUID())
                || level.getServer().getPlayerList().getPlayer(actor.getUUID()) != actor
                || CompanionManager.get(level.getServer()).find(actor.getUUID()).orElse(null) != companion) {
            return Result.DENIED;
        }
        if (companion.creativeMode() == enabled) {
            return Result.UNCHANGED;
        }
        CreativePermissions permissions = new CreativePermissions(true, true, Operators.isOperator(actor));
        if (enabled ? !CreativePolicy.canEnable(permissions) : !CreativePolicy.canDisable(permissions)) {
            return Result.DENIED;
        }

        FriendaiServerState state = FriendaiServerState.get(level.getServer());
        state.sessions().cancel(actor.getUUID());
        companion.executionCoordinator().cancelForCapabilityChange();
        companion.getNavigation().stop();
        companion.setSprinting(false);
        CompanionLifeData life = state.companionLife();
        life.setCreativeMode(actor.getUUID(), enabled);
        companion.setCreativeMode(enabled);
        return Result.CHANGED;
    }

    /** Revalidates a queued action against the live owner, player connection, mode generation and operator rights. */
    public static boolean isActionAuthorized(
            CompanionEntity companion, CreativeActionContext actionContext, CreativeAction action) {
        if (companion == null
                || actionContext == null
                || action == null
                || !companion.creativeMode()
                || !(companion.level() instanceof ServerLevel level)
                || companion.ownerUuid() == null
                || !actionContext.matches(companion.ownerUuid(), companion.creativeModeRevision())) {
            return false;
        }
        ServerPlayer principal = level.getServer().getPlayerList().getPlayer(actionContext.principal());
        boolean online = principal != null && principal.level().getServer() == level.getServer();
        boolean owner = online && actionContext.principal().equals(companion.ownerUuid());
        boolean activationAllowed = owner && Operators.isOperator(principal);
        return CreativePolicy.canAct(
                CreativeMode.CREATIVE, new CreativePermissions(owner, online, activationAllowed), action);
    }

    /** World-changing tools remain usable under their normal policy in survival; creative mode requires a fresh owner grant. */
    public static boolean mayStartWorldChangingTask(CompanionEntity companion, UUID principal) {
        if (companion == null) return false;
        if (!companion.creativeMode()) return true;
        if (principal == null) return false;
        return isActionAuthorized(
                companion,
                new CreativeActionContext(principal, companion.creativeModeRevision()),
                CreativeAction.WORLD_CHANGING_TASK);
    }

    /** Passive following may use flight only while the live owner retains the activation right. */
    public static boolean ownerMayUsePassiveFlight(CompanionEntity companion) {
        return companion != null
                && companion.creativeMode()
                && companion.owner().filter(Operators::isOperator).isPresent();
    }
}
