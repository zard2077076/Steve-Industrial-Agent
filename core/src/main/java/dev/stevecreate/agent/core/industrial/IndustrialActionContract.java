package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable declaration of one exact adapter-owned interaction; it grants no execution authority. */
public record IndustrialActionContract(
        ResourceId actionId,
        IndustrialActionType actionType,
        Optional<ResourceId> requiredTool,
        Set<GenericResourceType> resourceTypes,
        boolean directSupported,
        boolean botSupported,
        boolean idempotent,
        int maximumAttempts,
        String verificationContract) {
    public IndustrialActionContract {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(actionType, "actionType");
        requiredTool = Objects.requireNonNull(requiredTool, "requiredTool");
        resourceTypes = Set.copyOf(Objects.requireNonNull(resourceTypes, "resourceTypes"));
        if (!directSupported && !botSupported) {
            throw new IllegalArgumentException("industrial action has no supported executor");
        }
        if (maximumAttempts < 1 || maximumAttempts > 16) {
            throw new IllegalArgumentException("industrial action attempt bound is invalid");
        }
        Objects.requireNonNull(verificationContract, "verificationContract");
        if (verificationContract.isBlank() || verificationContract.length() > 2_048) {
            throw new IllegalArgumentException("verification contract is blank or unbounded");
        }
        if ((actionType == IndustrialActionType.USE_TOOL
                || actionType == IndustrialActionType.FORM_MULTIBLOCK
                || actionType == IndustrialActionType.DISMANTLE)
                && requiredTool.isEmpty()) {
            throw new IllegalArgumentException("tool interaction must name its retained tool");
        }
    }
}
