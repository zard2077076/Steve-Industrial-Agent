package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable fail-closed deployment limits. This phase never permits formal-world execution. */
public record DeploymentPolicy(
        String policyId,
        Set<WorldEnvironmentType> allowedEnvironmentTypes,
        Set<String> forbiddenRootIdentities,
        Set<String> allowedRootIdentities,
        int maximumAffectedBlocks,
        long maximumBoundingVolume,
        int maximumRouteLength,
        long maximumMaterialCost,
        long maximumRotationalStressDemand,
        Set<ResourceId> allowedAdapterIds,
        Set<ResourceId> allowedImplementationIds,
        Set<ResourceId> allowedRecipeTypes,
        Set<ResourceId> allowedDimensionIds,
        Optional<DeploymentTimeWindow> allowedTimeWindow,
        boolean executionPermitted,
        boolean backupRequired,
        boolean dryRunRequired,
        boolean humanApprovalRequired,
        Duration approvalExpiry,
        boolean rollbackRequired,
        RollbackPolicy rollbackPolicy,
        UnknownBlockReplacementPolicy unknownBlockReplacementPolicy,
        PlayerBuiltBlockProtectionPolicy playerBuiltBlockProtectionPolicy,
        BlockEntityProtectionPolicy blockEntityProtectionPolicy,
        ClaimPermissionRequirement claimPermissionRequirement,
        ResourceSourcePolicy resourceSourcePolicy,
        ExistingMachineReusePolicy existingMachineReusePolicy,
        int worldMutationBudget) {
    public static final int MAX_AFFECTED_BLOCKS = 4_096;
    public static final long MAX_BOUNDING_VOLUME = 16_777_216L;
    public static final int MAX_ROUTE_LENGTH = 4_096;

    public DeploymentPolicy {
        policyId = WorldEnvironmentEvidence.text(policyId, "policyId");
        allowedEnvironmentTypes = copy(allowedEnvironmentTypes, "allowedEnvironmentTypes");
        if (allowedEnvironmentTypes.isEmpty()) throw new IllegalArgumentException("No environment is allowed");
        forbiddenRootIdentities = strings(forbiddenRootIdentities, "forbiddenRootIdentities");
        allowedRootIdentities = strings(allowedRootIdentities, "allowedRootIdentities");
        if (forbiddenRootIdentities.isEmpty()) throw new IllegalArgumentException("Forbidden roots must be explicit");
        if (maximumAffectedBlocks < 0 || maximumAffectedBlocks > MAX_AFFECTED_BLOCKS
                || maximumBoundingVolume < 1 || maximumBoundingVolume > MAX_BOUNDING_VOLUME
                || maximumRouteLength < 0 || maximumRouteLength > MAX_ROUTE_LENGTH
                || maximumMaterialCost < 0 || maximumRotationalStressDemand < 0) {
            throw new IllegalArgumentException("A deployment bound is outside its limit");
        }
        if (worldMutationBudget < 0 || worldMutationBudget > maximumAffectedBlocks) {
            throw new IllegalArgumentException("World mutation budget exceeds affected-block limit");
        }
        allowedAdapterIds = ids(allowedAdapterIds, "allowedAdapterIds");
        allowedImplementationIds = ids(allowedImplementationIds, "allowedImplementationIds");
        allowedRecipeTypes = ids(allowedRecipeTypes, "allowedRecipeTypes");
        allowedDimensionIds = ids(allowedDimensionIds, "allowedDimensionIds");
        allowedTimeWindow = Objects.requireNonNull(allowedTimeWindow, "allowedTimeWindow");
        approvalExpiry = Objects.requireNonNull(approvalExpiry, "approvalExpiry");
        if (approvalExpiry.isZero() || approvalExpiry.isNegative()
                || approvalExpiry.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Approval expiry is outside its bound");
        }
        Objects.requireNonNull(rollbackPolicy, "rollbackPolicy");
        Objects.requireNonNull(unknownBlockReplacementPolicy, "unknownBlockReplacementPolicy");
        Objects.requireNonNull(playerBuiltBlockProtectionPolicy, "playerBuiltBlockProtectionPolicy");
        Objects.requireNonNull(blockEntityProtectionPolicy, "blockEntityProtectionPolicy");
        Objects.requireNonNull(claimPermissionRequirement, "claimPermissionRequirement");
        Objects.requireNonNull(resourceSourcePolicy, "resourceSourcePolicy");
        Objects.requireNonNull(existingMachineReusePolicy, "existingMachineReusePolicy");
        if (executionPermitted && allowedEnvironmentTypes.stream().anyMatch(type ->
                type == WorldEnvironmentType.FORMAL_PLAYER_WORLD
                        || type == WorldEnvironmentType.UNKNOWN_WORLD
                        || type == WorldEnvironmentType.FORBIDDEN_WORLD)) {
            throw new IllegalArgumentException("Formal, unknown and forbidden execution cannot be enabled");
        }
        if (executionPermitted && !dryRunRequired) {
            throw new IllegalArgumentException("Execution cannot bypass dry-run");
        }
        if (rollbackRequired && rollbackPolicy == RollbackPolicy.DESTRUCTIVE_FORBIDDEN
                && executionPermitted) {
            throw new IllegalArgumentException("Executable policy lacks a verified rollback strategy");
        }
    }

    public static DeploymentPolicy formalWorldDryRunOnly(String formalRootIdentity) {
        return new DeploymentPolicy(
                "formal-player-world-default", Set.of(WorldEnvironmentType.FORMAL_PLAYER_WORLD),
                Set.of(WorldEnvironmentEvidence.text(formalRootIdentity, "formalRootIdentity")), Set.of(),
                MAX_AFFECTED_BLOCKS, MAX_BOUNDING_VOLUME, MAX_ROUTE_LENGTH, Long.MAX_VALUE,
                Long.MAX_VALUE, Set.of(), Set.of(), Set.of(), Set.of(), Optional.empty(),
                false, true, true, true, Duration.ofMinutes(15), true,
                RollbackPolicy.DESTRUCTIVE_FORBIDDEN, UnknownBlockReplacementPolicy.FORBIDDEN,
                PlayerBuiltBlockProtectionPolicy.PROTECT, BlockEntityProtectionPolicy.PROTECT,
                ClaimPermissionRequirement.REQUIRED, ResourceSourcePolicy.AUTO_WITHDRAW_FORBIDDEN,
                ExistingMachineReusePolicy.FORBIDDEN, 0);
    }

    public static DeploymentPolicy isolatedTestDefault(
            String allowedRootIdentity,
            String forbiddenRootIdentity) {
        return new DeploymentPolicy(
                "isolated-test-default", Set.of(WorldEnvironmentType.ISOLATED_TEST_WORLD),
                Set.of(WorldEnvironmentEvidence.text(forbiddenRootIdentity, "forbiddenRootIdentity")),
                Set.of(WorldEnvironmentEvidence.text(allowedRootIdentity, "allowedRootIdentity")),
                256, 65_536, 256, 4_096, 4_096, Set.of(), Set.of(), Set.of(), Set.of(),
                Optional.empty(), true, true, true, true, Duration.ofMinutes(15), true,
                RollbackPolicy.REQUIRED_VERIFIED, UnknownBlockReplacementPolicy.FORBIDDEN,
                PlayerBuiltBlockProtectionPolicy.PROTECT, BlockEntityProtectionPolicy.PROTECT,
                ClaimPermissionRequirement.REQUIRED, ResourceSourcePolicy.TEST_FIXTURE_PROVIDED,
                ExistingMachineReusePolicy.FORBIDDEN, 256);
    }

    public boolean isWithinTimeWindow(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return allowedTimeWindow.map(window -> window.contains(instant)).orElse(true);
    }

    public boolean allowsEnvironment(WorldEnvironmentType type, String gameDirectoryIdentity) {
        Objects.requireNonNull(type, "type");
        String identity = key(gameDirectoryIdentity);
        if (forbiddenRootIdentities.stream().map(DeploymentPolicy::key)
                .anyMatch(root -> identity.equals(root) || identity.startsWith(root + "/"))) {
            return false;
        }
        boolean allowedRoot = allowedRootIdentities.isEmpty()
                || allowedRootIdentities.stream().map(DeploymentPolicy::key)
                .anyMatch(root -> identity.equals(root) || identity.startsWith(root + "/"));
        return allowedRoot && allowedEnvironmentTypes.contains(type);
    }

    private static <T> Set<T> copy(Set<T> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > 256 || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " is outside its bound");
        }
        return Set.copyOf(values);
    }

    private static Set<ResourceId> ids(Set<ResourceId> values, String name) {
        return copy(values, name);
    }

    private static Set<String> strings(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > 64) throw new IllegalArgumentException(name + " is too large");
        return Set.copyOf(values.stream().map(value -> WorldEnvironmentEvidence.text(value, name + " entry")).toList());
    }

    private static String key(String value) {
        return WorldEnvironmentEvidence.text(value, "path identity").replace('\\', '/')
                .replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }
}
