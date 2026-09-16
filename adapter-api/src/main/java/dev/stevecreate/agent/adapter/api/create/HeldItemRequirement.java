package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.planning.RecipeIngredient;
import java.util.Objects;
import java.util.Optional;

/** Deployer held-item semantics; player inventory access is structurally forbidden. */
public record HeldItemRequirement(
        Optional<RecipeIngredient> heldItem,
        boolean consumed,
        boolean stateBeforeAfterRequired,
        boolean playerInventoryForbidden) {
    public HeldItemRequirement {
        heldItem = Objects.requireNonNull(heldItem, "heldItem");
        heldItem.ifPresent(value -> Objects.requireNonNull(value, "heldItem value"));
        if (heldItem.isEmpty() && (consumed || stateBeforeAfterRequired)) {
            throw new IllegalArgumentException("Absent held item cannot claim state or consumption");
        }
        if (heldItem.isPresent() && (!stateBeforeAfterRequired || !playerInventoryForbidden)) {
            throw new IllegalArgumentException(
                    "A held item requires before/after evidence and cannot come from player inventory");
        }
        if (!playerInventoryForbidden) {
            throw new IllegalArgumentException("Player inventory must remain forbidden");
        }
    }

    public static HeldItemRequirement none() {
        return new HeldItemRequirement(Optional.empty(), false, false, true);
    }
}
