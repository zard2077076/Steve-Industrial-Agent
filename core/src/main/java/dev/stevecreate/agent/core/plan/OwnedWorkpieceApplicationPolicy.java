package dev.stevecreate.agent.core.plan;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/**
 * Loader-neutral authority for one allowlisted C-10 application to a session-owned workpiece.
 *
 * <p>This is not generic block-use authority. The exact recipe, initial block, held item, result
 * block, verified plan, session and bounded position are immutable. Every unrelated interaction
 * surface remains forbidden.</p>
 */
public record OwnedWorkpieceApplicationPolicy(
        ResourceId verifiedPhysicalPlanId,
        ResourceId sessionId,
        ResourceId recipeId,
        ResourceId initialBlock,
        ResourceId heldItem,
        ResourceId resultBlock,
        BlockPos3i workpiecePosition,
        BlockPos3i regionMinimum,
        BlockPos3i regionMaximum,
        boolean arbitraryPositionUseForbidden,
        boolean containerOpeningForbidden,
        boolean entityInteractionForbidden,
        boolean combatForbidden,
        boolean playerInventoryForbidden,
        boolean privateStorageForbidden,
        boolean unknownNbtMutationForbidden,
        boolean unknownBlockEntityMutationForbidden,
        boolean unknownWorldSideEffectsForbidden) {

    private static final ResourceId ANDESITE_CASING_RECIPE =
            id("create:item_application/andesite_casing_from_log");
    private static final ResourceId STRIPPED_OAK_LOG = id("minecraft:stripped_oak_log");
    private static final ResourceId ANDESITE_ALLOY = id("create:andesite_alloy");
    private static final ResourceId ANDESITE_CASING = id("create:andesite_casing");

    public OwnedWorkpieceApplicationPolicy {
        Objects.requireNonNull(verifiedPhysicalPlanId, "verifiedPhysicalPlanId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(initialBlock, "initialBlock");
        Objects.requireNonNull(heldItem, "heldItem");
        Objects.requireNonNull(resultBlock, "resultBlock");
        Objects.requireNonNull(workpiecePosition, "workpiecePosition");
        Objects.requireNonNull(regionMinimum, "regionMinimum");
        Objects.requireNonNull(regionMaximum, "regionMaximum");
        if (!recipeId.equals(ANDESITE_CASING_RECIPE)
                || !initialBlock.equals(STRIPPED_OAK_LOG)
                || !heldItem.equals(ANDESITE_ALLOY)
                || !resultBlock.equals(ANDESITE_CASING)) {
            throw new IllegalArgumentException(
                    "C-10 owned-workpiece Phase I admits only the reviewed andesite-casing recipe");
        }
        if (!contains(regionMinimum, regionMaximum, workpiecePosition)) {
            throw new IllegalArgumentException("C-10 workpiece is outside its verified region");
        }
        if (!arbitraryPositionUseForbidden
                || !containerOpeningForbidden
                || !entityInteractionForbidden
                || !combatForbidden
                || !playerInventoryForbidden
                || !privateStorageForbidden
                || !unknownNbtMutationForbidden
                || !unknownBlockEntityMutationForbidden
                || !unknownWorldSideEffectsForbidden) {
            throw new IllegalArgumentException(
                    "C-10 owned-workpiece authority cannot grant unrelated interaction power");
        }
    }

    public static OwnedWorkpieceApplicationPolicy andesiteCasing(
            ResourceId verifiedPhysicalPlanId,
            ResourceId sessionId,
            BlockPos3i workpiecePosition,
            BlockPos3i regionMinimum,
            BlockPos3i regionMaximum) {
        return new OwnedWorkpieceApplicationPolicy(
                verifiedPhysicalPlanId,
                sessionId,
                ANDESITE_CASING_RECIPE,
                STRIPPED_OAK_LOG,
                ANDESITE_ALLOY,
                ANDESITE_CASING,
                workpiecePosition,
                regionMinimum,
                regionMaximum,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true);
    }

    public boolean contains(BlockPos3i position) {
        return contains(regionMinimum, regionMaximum, position);
    }

    private static boolean contains(
            BlockPos3i minimum, BlockPos3i maximum, BlockPos3i position) {
        if (minimum.x() > maximum.x()
                || minimum.y() > maximum.y()
                || minimum.z() > maximum.z()) {
            throw new IllegalArgumentException("C-10 owned-workpiece region is inverted");
        }
        return position.x() >= minimum.x() && position.x() <= maximum.x()
                && position.y() >= minimum.y() && position.y() <= maximum.y()
                && position.z() >= minimum.z() && position.z() <= maximum.z();
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
