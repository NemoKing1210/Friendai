package dev.friendai.core;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionLifeData;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.features.creative.CreativeActionContext;
import dev.friendai.features.creative.CreativeMode;
import dev.friendai.features.creative.CreativePermissions;
import dev.friendai.features.creative.CreativePolicy;
import dev.friendai.server.net.Operators;
import dev.friendai.server.runtime.ServerRuntime;
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

    public static Result requestMode(
            ServerRuntime runtime, CompanionEntity companion, ServerPlayer actor, boolean enabled) {
        if (runtime == null
                || companion == null
                || actor == null
                || !(companion.level() instanceof ServerLevel level)) {
            return Result.DENIED;
        }
        var server = runtime.server();
        if (server != level.getServer()
                || actor.level().getServer() != server
                || companion.ownerUuid() == null
                || !companion.ownerUuid().equals(actor.getUUID())
                || server.getPlayerList().getPlayer(actor.getUUID()) != actor
                || runtime.companions().find(actor.getUUID()).orElse(null) != companion) {
            return Result.DENIED;
        }
        if (companion.creativeMode() == enabled) {
            return Result.UNCHANGED;
        }
        CreativePermissions permissions = new CreativePermissions(true, true, Operators.isOperator(actor));
        if (enabled ? !CreativePolicy.canEnable(permissions) : !CreativePolicy.canDisable(permissions)) {
            return Result.DENIED;
        }

        runtime.sessions().cancel(actor.getUUID());
        companion.executionCoordinator().cancelForCapabilityChange();
        companion.getNavigation().stop();
        companion.setSprinting(false);
        CompanionLifeData life = runtime.companionLife();
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
