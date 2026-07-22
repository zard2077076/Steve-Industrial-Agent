package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RegionAuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-07-17T13:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void exactApprovedIsolatedScopePassesWithoutCreatingExecutionAuthority() {
        RegionAuthorization authorization = isolatedAuthorization(
                Set.of(RegionAuthorizedOperation.DRY_RUN, RegionAuthorizedOperation.PLACE_BLOCK),
                12, RegionApprovalState.APPROVED, RegionRevocationState.ACTIVE,
                RegionUseState.UNUSED, Optional.of("owner:alice"));
        RegionAuthorizationRequest request = request(
                "world:isolated", WorldEnvironmentType.ISOLATED_TEST_WORLD,
                id("minecraft:overworld"), box(2, 2, 2, 4, 4, 4), Optional.of("owner:alice"),
                Set.of(RegionAuthorizedOperation.DRY_RUN, RegionAuthorizedOperation.PLACE_BLOCK),
                8, HASH, "snapshot:one", "runtime:one");

        RegionAuthorizationCheck check = new RegionAuthorizationService()
                .check(authorization, request, NOW);

        assertThat(check.allowed()).isTrue();
        assertThat(check.failures()).isEmpty();
        assertThat(authorization.ownerIdentity()).contains("owner:alice");
        assertThat(authorization.provenance()).isEqualTo("fixture:verified-authorizer");
    }

    @Test
    void exactScopeChecksWorldDimensionOwnerRegionOperationsBudgetAndFingerprints() {
        RegionAuthorization authorization = isolatedAuthorization(
                Set.of(RegionAuthorizedOperation.DRY_RUN), 4,
                RegionApprovalState.APPROVED, RegionRevocationState.ACTIVE,
                RegionUseState.UNUSED, Optional.empty());
        RegionAuthorizationRequest request = request(
                "world:other", WorldEnvironmentType.DEVELOPMENT_WORLD,
                id("minecraft:the_nether"), box(-1, 0, 0, 2, 2, 2), Optional.of("owner:forged"),
                Set.of(RegionAuthorizedOperation.PLACE_BLOCK), 5,
                "b".repeat(64), "snapshot:changed", "runtime:changed");

        assertThat(new RegionAuthorizationService().check(authorization, request, NOW).failures())
                .containsExactly(
                        RegionAuthorizationFailure.ENVIRONMENT_MISMATCH,
                        RegionAuthorizationFailure.WORLD_IDENTITY_MISMATCH,
                        RegionAuthorizationFailure.DIMENSION_MISMATCH,
                        RegionAuthorizationFailure.OWNER_IDENTITY_MISMATCH,
                        RegionAuthorizationFailure.REGION_OUT_OF_SCOPE,
                        RegionAuthorizationFailure.OPERATION_NOT_ALLOWED,
                        RegionAuthorizationFailure.MUTATION_BUDGET_EXCEEDED,
                        RegionAuthorizationFailure.PREVIEW_HASH_MISMATCH,
                        RegionAuthorizationFailure.WORLD_SNAPSHOT_MISMATCH,
                        RegionAuthorizationFailure.RUNTIME_FINGERPRINT_MISMATCH);
    }

    @Test
    void expiryApprovalRevocationAndOneTimeUseFailClosed() {
        RegionAuthorizationService service = new RegionAuthorizationService();
        RegionAuthorizationRequest request = request(
                "world:isolated", WorldEnvironmentType.ISOLATED_TEST_WORLD,
                id("minecraft:overworld"), box(1, 1, 1, 2, 2, 2), Optional.empty(),
                Set.of(RegionAuthorizedOperation.DRY_RUN), 0,
                HASH, "snapshot:one", "runtime:one");
        RegionAuthorization inactive = new RegionAuthorization(
                "authorization:inactive", "world:isolated",
                WorldEnvironmentType.ISOLATED_TEST_WORLD, id("minecraft:overworld"),
                box(0, 0, 0, 9, 9, 9), Optional.empty(), "authorizer:test-only",
                Set.of(RegionAuthorizedOperation.DRY_RUN), 12, NOW.minusSeconds(1), HASH,
                "snapshot:one", "runtime:one", true, RegionApprovalState.PENDING,
                RegionRevocationState.REVOKED, RegionUseState.CONSUMED,
                "fixture:verified-authorizer");

        assertThat(service.check(inactive, request, NOW).failures()).containsExactly(
                RegionAuthorizationFailure.EXPIRED,
                RegionAuthorizationFailure.NOT_APPROVED,
                RegionAuthorizationFailure.REVOKED,
                RegionAuthorizationFailure.ONE_TIME_USE_ALREADY_CONSUMED);
    }

    @Test
    void formalWorldCanOnlyModelPendingReadOnlyDryRunAndNeverPassesLiveCheck() {
        assertThatThrownBy(() -> formalAuthorization(
                Set.of(RegionAuthorizedOperation.PLACE_BLOCK), 0, RegionApprovalState.PENDING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> formalAuthorization(
                Set.of(RegionAuthorizedOperation.DRY_RUN), 1, RegionApprovalState.PENDING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> formalAuthorization(
                Set.of(RegionAuthorizedOperation.READ_ONLY_SCAN), 0, RegionApprovalState.APPROVED))
                .isInstanceOf(IllegalArgumentException.class);

        RegionAuthorization modeled = formalAuthorization(
                Set.of(RegionAuthorizedOperation.READ_ONLY_SCAN, RegionAuthorizedOperation.DRY_RUN),
                0, RegionApprovalState.PENDING);
        RegionAuthorizationRequest request = request(
                "world:formal", WorldEnvironmentType.FORMAL_PLAYER_WORLD,
                id("minecraft:overworld"), box(1, 1, 1, 2, 2, 2), Optional.empty(),
                Set.of(RegionAuthorizedOperation.READ_ONLY_SCAN), 0,
                HASH, "snapshot:formal", "runtime:formal");

        assertThat(new RegionAuthorizationService().check(modeled, request, NOW).failures())
                .containsExactly(RegionAuthorizationFailure.NOT_APPROVED,
                        RegionAuthorizationFailure.FORMAL_WORLD_EXECUTION_FORBIDDEN);
    }

    @Test
    void operationVocabularyAndLoaderNeutralContractAreExact() {
        assertThat(Arrays.stream(RegionAuthorizedOperation.values()).map(Enum::name))
                .containsExactly("READ_ONLY_SCAN", "DRY_RUN", "PLACE_BLOCK", "REMOVE_BLOCK",
                        "REPLACE_BLOCK", "ACCESS_CONTAINER", "WITHDRAW_ITEM", "INSERT_ITEM",
                        "CONNECT_POWER", "CONNECT_LOGISTICS", "START_MACHINE", "CLEANUP",
                        "ROLLBACK");
        assertThat(Arrays.stream(new Class<?>[] {
                    RegionAuthorization.class, RegionAuthorizationRequest.class,
                    RegionAuthorizationCheck.class, RegionAuthorizationService.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType).map(Class::getName))
                .noneMatch(name -> name.startsWith("net.minecraft")
                        || name.startsWith("net.minecraftforge")
                        || name.startsWith("com.simibubi.create")
                        || name.contains("GenericExecutionSession")
                        || name.contains("WorldResourceBuffer"));
    }

    private static RegionAuthorization isolatedAuthorization(
            Set<RegionAuthorizedOperation> operations,
            int maximumMutations,
            RegionApprovalState approval,
            RegionRevocationState revocation,
            RegionUseState useState,
            Optional<String> owner) {
        return new RegionAuthorization(
                "authorization:isolated", "world:isolated",
                WorldEnvironmentType.ISOLATED_TEST_WORLD, id("minecraft:overworld"),
                box(0, 0, 0, 9, 9, 9), owner, "authorizer:test-only", operations,
                maximumMutations, NOW.plusSeconds(300), HASH, "snapshot:one", "runtime:one",
                true, approval, revocation, useState, "fixture:verified-authorizer");
    }

    private static RegionAuthorization formalAuthorization(
            Set<RegionAuthorizedOperation> operations,
            int maximumMutations,
            RegionApprovalState approval) {
        return new RegionAuthorization(
                "authorization:formal-model", "world:formal",
                WorldEnvironmentType.FORMAL_PLAYER_WORLD, id("minecraft:overworld"),
                box(0, 0, 0, 9, 9, 9), Optional.empty(), "authorizer:not-approved", operations,
                maximumMutations, NOW.plusSeconds(300), HASH, "snapshot:formal", "runtime:formal",
                true, approval, RegionRevocationState.ACTIVE, RegionUseState.UNUSED,
                "fixture:model-only");
    }

    private static RegionAuthorizationRequest request(
            String world,
            WorldEnvironmentType environment,
            ResourceId dimension,
            DeploymentBoundingBox bounds,
            Optional<String> owner,
            Set<RegionAuthorizedOperation> operations,
            int mutations,
            String previewHash,
            String snapshot,
            String runtime) {
        return new RegionAuthorizationRequest(
                world, environment, dimension, bounds, owner, operations, mutations,
                previewHash, snapshot, runtime);
    }

    private static DeploymentBoundingBox box(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new DeploymentBoundingBox(
                new BlockPos3i(minX, minY, minZ), new BlockPos3i(maxX, maxY, maxZ));
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
