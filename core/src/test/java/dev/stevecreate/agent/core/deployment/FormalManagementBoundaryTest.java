package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.formalbackup.FormalBackupCommandException;
import dev.stevecreate.agent.core.formalbackup.FormalBackupFailureCode;
import dev.stevecreate.agent.core.formalbackup.FormalClaimPermissionReadiness;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentCandidatePackage;
import dev.stevecreate.agent.core.formalbackup.FormalDeploymentCandidatePackager;
import dev.stevecreate.agent.core.formalbackup.FormalManagementCommandParser;
import dev.stevecreate.agent.core.formalbackup.FormalManagementCommandPlan;
import dev.stevecreate.agent.core.formalbackup.FormalManagementCommandType;
import dev.stevecreate.agent.core.formalbackup.FormalResourceInventoryAuthorizationRequest;
import dev.stevecreate.agent.core.formalbackup.FormalResourceInventoryRequestFactory;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FormalManagementBoundaryTest {
    private static final String WORLD = "world:" + "a".repeat(64);
    private static final String BACKUP = "formal-backup:" + "b".repeat(64);
    private static final String CANDIDATE = "formal-candidate:" + "c".repeat(64);

    @Test
    void parsesExactlySevenRegisteredIdentityCommandsIntoAuditOnlyPlans() throws Exception {
        FormalManagementCommandParser parser = parser();
        List<FormalManagementCommandPlan> plans = List.of(
                parser.parse("formal backup plan " + WORLD),
                parser.parse("formal backup create " + WORLD),
                parser.parse("formal backup verify " + BACKUP),
                parser.parse("formal backup restore-drill " + BACKUP),
                parser.parse("formal deployment candidates " + WORLD),
                parser.parse("formal deployment approval-request " + CANDIDATE),
                parser.parse("formal deployment status " + CANDIDATE));

        assertThat(plans).extracting(FormalManagementCommandPlan::type)
                .containsExactly(FormalManagementCommandType.values());
        assertThat(plans).allSatisfy(plan -> {
            assertThat(plan.auditRelativeKey())
                    .matches("formal-management/[a-z0-9-]+/[a-f0-9]{64}\\.json");
            assertThat(plan.auditRequired()).isTrue();
            assertThat(plan.arbitraryPathAccepted()).isFalse();
            assertThat(plan.sessionCreated()).isFalse();
            assertThat(plan.formalWorldWrite()).isFalse();
            assertThat(plan.automaticallyApproved()).isFalse();
            assertThat(plan.inventoryContentsRead()).isFalse();
            assertThat(plan.executionAllowed()).isFalse();
        });
    }

    @Test
    void rejectsPathsUnknownIdentitiesExtraTokensAndUnregisteredVerbs() {
        FormalManagementCommandParser parser = parser();
        assertThatThrownBy(() -> parser.parse("formal backup create D:\\PCL2\\world"))
                .isInstanceOf(FormalBackupCommandException.class);
        assertThatThrownBy(() -> parser.parse("formal backup verify formal-backup:" + "0".repeat(64)))
                .isInstanceOf(FormalBackupCommandException.class)
                .satisfies(error -> assertThat(((FormalBackupCommandException) error).failure().code())
                        .isEqualTo(FormalBackupFailureCode.BACKUP_VERIFICATION_FAILED));
        assertThatThrownBy(() -> parser.parse("formal deployment status " + CANDIDATE + " extra"))
                .isInstanceOf(FormalBackupCommandException.class);
        assertThatThrownBy(() -> parser.parse("formal deployment approve " + CANDIDATE))
                .isInstanceOf(FormalBackupCommandException.class);
        assertThatThrownBy(() -> parser.parse("formal backup create ../world"))
                .isInstanceOf(FormalBackupCommandException.class);
    }

    @Test
    void inventoryIsRequestOnlyClaimUnknownBlocksAndModelsHaveNoExecutionSurface() {
        FormalDeploymentCandidatePackage candidate = new FormalDeploymentCandidatePackager().packageCandidate(
                FormalDeploymentCandidatePackageTest.blocked(
                        FormalDeploymentCandidatePackageTest.zone('c', 5), "minecraft:gravel", 3));
        var position = candidate.candidateZone().anchorCandidates().get(0);
        FormalResourceInventoryAuthorizationRequest request = new FormalResourceInventoryRequestFactory().create(
                candidate, List.of(position), Set.of(GenericResourceType.ITEM),
                "Estimate future material availability after explicit approval",
                "Only requested item-category totals at one listed container",
                Instant.parse("2026-07-20T18:00:00Z"));

        assertThat(request.requestIdentity()).matches("formal-inventory-request:[0-9a-f]{64}");
        assertThat(request.containerPositions()).containsExactly(position);
        assertThat(request.requestedResourceCategories()).containsExactly(GenericResourceType.ITEM);
        assertThat(request.noWriteGuarantee()).isTrue();
        assertThat(request.requestExecuted()).isFalse();
        assertThat(request.inventoryContentsRead()).isFalse();
        assertThatThrownBy(() -> new FormalResourceInventoryAuthorizationRequest(
                request.requestIdentity(), request.worldIdentity(), request.candidatePackageIdentity(),
                request.containerPositions(), request.requestedResourceCategories(), request.purpose(),
                request.dataMinimizationScope(), request.expiresAt(), true, true, true))
                .isInstanceOf(IllegalArgumentException.class);

        FormalClaimPermissionReadiness claim = new FormalClaimPermissionReadiness(
                WORLD, candidate.packageIdentity(), PermissionDecision.UNKNOWN,
                List.of("opac-0.25.8-read-only-adapter"), false, false, false, false);
        assertThat(claim.permission()).isEqualTo(PermissionDecision.UNKNOWN);
        assertThat(claim.readinessAllowed()).isFalse();
        assertThat(Arrays.stream(new Class<?>[] {
                    FormalManagementCommandPlan.class,
                    FormalResourceInventoryAuthorizationRequest.class,
                    FormalClaimPermissionReadiness.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType).map(Class::getName))
                .noneMatch(name -> name.contains("GenericExecutionSession")
                        || name.startsWith("net.minecraft") || name.contains("minecraftforge")
                        || name.toLowerCase().contains("llm"));
    }

    private static FormalManagementCommandParser parser() {
        return new FormalManagementCommandParser(Set.of(WORLD), Map.of(BACKUP, WORLD),
                Map.of(CANDIDATE, WORLD));
    }
}
