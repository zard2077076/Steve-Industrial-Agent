package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.deployment.DeploymentBudget;
import dev.stevecreate.agent.core.deployment.DeploymentDryRunReport;
import dev.stevecreate.agent.core.deployment.DeploymentRiskAssessment;
import dev.stevecreate.agent.core.deployment.PermissionDecision;
import dev.stevecreate.agent.core.deployment.RegionAuthorizedOperation;
import dev.stevecreate.agent.core.deployment.WorldEnvironmentType;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.survey.CandidateZoneStatus;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/** Deterministic FB-04 packager. Ranking is retained, but selection and approval are impossible. */
public final class FormalDeploymentCandidatePackager {
    public FormalDeploymentCandidatePackage packageCandidate(FormalDeploymentCandidateInput input) {
        Objects.requireNonNull(input, "input");
        if (!input.worldIdentity().equals(input.backup().identity().worldIdentity().value())
                || input.candidateZone().status() != CandidateZoneStatus.PENDING_USER_SELECTION
                || input.claimPermission() != PermissionDecision.UNKNOWN
                || !input.expiresAt().isAfter(input.backup().identity().backupTimestamp())) {
            throw new IllegalArgumentException("candidate, backup, permission or expiration context is invalid");
        }
        boolean available = input.dryRunReport().isPresent();
        if (available != input.verifiedPhysicalPlanIdentity().isPresent()) {
            throw new IllegalArgumentException("preview and physical plan evidence must be jointly present or absent");
        }
        Optional<String> previewHash = Optional.empty();
        Optional<DeploymentRiskAssessment> risk = Optional.empty();
        Optional<DeploymentBudget> budget = Optional.empty();
        FormalDeploymentArtifactAvailability availability;
        if (available) {
            DeploymentDryRunReport report = input.dryRunReport().orElseThrow();
            if (!report.preview().hasValidHash()
                    || report.preview().environmentClassification() != WorldEnvironmentType.FORMAL_PLAYER_WORLD
                    || !report.preview().target().equals(input.target())
                    || report.preview().quantity() != input.quantity()
                    || !report.preview().runtimeFingerprint().equals(input.runtimeFingerprint())
                    || !report.preview().worldSnapshotFingerprint().equals(input.worldSnapshotFingerprint())
                    || !input.candidateZone().boundingBox().contains(report.preview().anchor())
                    || !input.candidateZone().boundingBox().contains(report.preview().affectedBounds().minimum())
                    || !input.candidateZone().boundingBox().contains(report.preview().affectedBounds().maximum())
                    || report.formalWorldExecutable() || report.worldMutation() || report.sessionCreated()
                    || report.playerItemsConsumed() || report.machineStarted()) {
                throw new IllegalArgumentException("verified dry-run does not match the formal candidate scope");
            }
            previewHash = Optional.of(report.preview().previewHash());
            risk = Optional.of(report.riskAssessment());
            budget = Optional.of(report.budget());
            availability = FormalDeploymentArtifactAvailability.VERIFIED_DRY_RUN_AVAILABLE;
        } else {
            availability = FormalDeploymentArtifactAvailability.BLOCKED_PREVIEW_UNAVAILABLE;
        }
        List<String> protectedUnknown = input.candidateZone().protectedAndUnknownFindings().stream()
                .map(value -> value.code() + "@" + value.scope()).sorted().toList();
        List<String> missing = java.util.stream.Stream.concat(
                        input.missingAuthorizations().stream(),
                        input.candidateZone().requiredFutureAuthorization().stream())
                .distinct().sorted().toList();
        if (!missing.contains("USER_SELECTION") || !missing.contains("CLAIM_PERMISSION_UNKNOWN")) {
            throw new IllegalArgumentException("formal candidate must retain selection and UNKNOWN claim blockers");
        }
        if (!available && (!missing.contains("FORMAL_PREVIEW_UNAVAILABLE")
                || !missing.contains("VERIFIED_PHYSICAL_PLAN_UNAVAILABLE"))) {
            throw new IllegalArgumentException("unavailable artifacts require exact blockers");
        }
        List<RegionAuthorizedOperation> operations = input.requiredOperations().stream()
                .sorted().toList();
        String identity = identity(input, previewHash, risk, budget, protectedUnknown, missing, operations);
        return new FormalDeploymentCandidatePackage(
                identity, input.worldIdentity(), input.candidateZone(), input.candidateZone().boundingBox(),
                input.target(), input.quantity(), previewHash, input.verifiedPhysicalPlanIdentity(),
                input.runtimeFingerprint(), input.worldSnapshotFingerprint(), input.backup().identity(),
                risk, budget, protectedUnknown, input.claimPermission(), operations, missing,
                input.rollbackPolicy(), input.expiresAt(), input.humanSummary(), availability,
                FormalDeploymentCandidateStatus.PENDING_USER_SELECTION,
                false, false, false);
    }

    public FormalDeploymentCandidateBatch packageBatch(
            String worldIdentity,
            BackupVerificationResult backup,
            List<FormalDeploymentCandidateInput> inputs,
            int maximumCandidates) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.isEmpty() || inputs.size() > maximumCandidates) {
            throw new IllegalArgumentException("candidate inputs are empty or exceed the maximum");
        }
        List<FormalDeploymentCandidatePackage> packages = inputs.stream()
                .map(this::packageCandidate).toList();
        return new FormalDeploymentCandidateBatch(worldIdentity, backup.identity().backupIdentity(),
                packages, maximumCandidates, Optional.empty(), false, false, false);
    }

    private static String identity(
            FormalDeploymentCandidateInput input,
            Optional<String> previewHash,
            Optional<DeploymentRiskAssessment> risk,
            Optional<DeploymentBudget> budget,
            List<String> protectedUnknown,
            List<String> missing,
            List<RegionAuthorizedOperation> operations) {
        MessageDigest digest = FormalWorldBackupManifest.digest();
        update(digest, input.worldIdentity());
        update(digest, input.candidateZone().candidateId());
        update(digest, input.candidateZone().dimension().toString());
        update(digest, input.candidateZone().boundingBox().toString());
        update(digest, input.target().toString());
        update(digest, Long.toString(input.quantity()));
        update(digest, previewHash.orElse("UNAVAILABLE"));
        update(digest, input.verifiedPhysicalPlanIdentity().map(ResourceId::toString).orElse("UNAVAILABLE"));
        update(digest, input.runtimeFingerprint());
        update(digest, input.worldSnapshotFingerprint());
        update(digest, input.backup().identity().backupIdentity());
        protectedUnknown.forEach(value -> update(digest, value));
        operations.forEach(value -> update(digest, value.name()));
        missing.forEach(value -> update(digest, value));
        update(digest, input.claimPermission().name());
        update(digest, input.rollbackPolicy().name());
        update(digest, input.expiresAt().toString());
        update(digest, input.humanSummary());
        risk.ifPresent(value -> {
            update(digest, value.previewHash());
            update(digest, value.highestSeverity().name());
            update(digest, Boolean.toString(value.approvalBlocked()));
            value.findings().forEach(finding -> update(digest, finding.toString()));
        });
        budget.ifPresent(value -> budget(digest, value));
        return "formal-candidate:" + HexFormat.of().formatHex(digest.digest());
    }

    private static void budget(MessageDigest digest, DeploymentBudget value) {
        update(digest, value.previewHash());
        maps(digest, value.rawMaterialRequirements());
        maps(digest, value.intermediateProductRequirements());
        maps(digest, value.constructionBlockRequirements());
        maps(digest, value.powerComponentRequirements());
        maps(digest, value.logisticsComponentRequirements());
        maps(digest, value.inputInventoryRequirements());
        maps(digest, value.expectedOutput());
        update(digest, Long.toString(value.rotationalStressDemand()));
        update(digest, Long.toString(value.powerCapacityMargin()));
        update(digest, value.estimatedRuntimeTicks().isPresent()
                ? Long.toString(value.estimatedRuntimeTicks().orElseThrow()) : "UNKNOWN");
        update(digest, Long.toString(value.maximumConstructionTicks()));
        update(digest, Long.toString(value.maximumAffectedBlocks()));
        update(digest, Long.toString(value.rollbackExtraSpaceBytes()));
        update(digest, Long.toString(value.journalSizeEstimateBytes()));
        update(digest, value.resourceSourcePolicy().name());
        value.policyViolations().forEach(item -> update(digest, item.name()));
        update(digest, Boolean.toString(value.withinPolicy()));
    }

    private static void maps(MessageDigest digest, Map<ResourceId, Long> values) {
        values.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> update(digest, entry.getKey() + "=" + entry.getValue()));
    }

    private static void update(MessageDigest digest, String value) {
        FormalWorldBackupManifest.update(digest, value);
    }
}
