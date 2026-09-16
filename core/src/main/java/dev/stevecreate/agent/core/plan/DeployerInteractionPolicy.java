package dev.stevecreate.agent.core.plan;

import java.util.Objects;

/**
 * Loader-neutral C-10 authority boundary.
 *
 * <p>Phase I may only process an item on the plan-owned Depot. It never grants
 * generic use, inventory, entity or NBT authority to the Deployer fake player
 * or to a construction Bot.
 */
public record DeployerInteractionPolicy(
        Target target,
        PlanBlockFacing interactionFace,
        boolean entityInteractionForbidden,
        boolean combatForbidden,
        boolean arbitraryBlockUseForbidden,
        boolean containerOpeningForbidden,
        boolean playerInventoryForbidden,
        boolean privateStorageForbidden,
        boolean unknownNbtMutationForbidden,
        boolean unknownWorldSideEffectsForbidden) {

    public enum Target {
        OWNED_DEPOT_ITEM
    }

    public DeployerInteractionPolicy {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(interactionFace, "interactionFace");
        if (target != Target.OWNED_DEPOT_ITEM
                || interactionFace != PlanBlockFacing.DOWN
                || !entityInteractionForbidden
                || !combatForbidden
                || !arbitraryBlockUseForbidden
                || !containerOpeningForbidden
                || !playerInventoryForbidden
                || !privateStorageForbidden
                || !unknownNbtMutationForbidden
                || !unknownWorldSideEffectsForbidden) {
            throw new IllegalArgumentException(
                    "C-10 Phase I permits only downward processing of an item "
                            + "on the plan-owned Depot with every unrelated "
                            + "interaction authority forbidden");
        }
    }

    public static DeployerInteractionPolicy safeDepotItemOnly() {
        return new DeployerInteractionPolicy(
                Target.OWNED_DEPOT_ITEM,
                PlanBlockFacing.DOWN,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true);
    }
}
