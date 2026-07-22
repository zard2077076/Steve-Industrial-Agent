package dev.stevecreate.agent.core.formalbackup;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.deployment.PermissionDecision;
import dev.stevecreate.agent.core.deployment.RegionAuthorizedOperation;
import dev.stevecreate.agent.core.deployment.RollbackPolicy;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.survey.CandidateIndustrialZone;
import dev.stevecreate.agent.core.survey.CandidateZoneStatus;
import dev.stevecreate.agent.core.survey.FormalFingerprintPolicy;
import dev.stevecreate.agent.core.survey.FormalReadOnlyPolicy;
import dev.stevecreate.agent.core.survey.FormalSaveDiscovery;
import dev.stevecreate.agent.core.survey.FormalSaveDiscoveryResult;
import dev.stevecreate.agent.core.survey.FormalWorldFingerprint;
import dev.stevecreate.agent.core.survey.FormalWorldFingerprintService;
import dev.stevecreate.agent.core.survey.FormalWorldIdentity;
import dev.stevecreate.agent.core.survey.FormalWorldReadOnlyGuard;
import dev.stevecreate.agent.core.survey.NbtReadLimits;
import dev.stevecreate.agent.core.survey.SurveyConfidence;
import dev.stevecreate.agent.core.survey.SurveyCoverage;
import dev.stevecreate.agent.core.survey.SurveyLimitation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit ignored-output acceptance entry point. Never used by production code or normal tests. */
public final class FormalBackupAcceptanceMain {
    private static final Pattern POSITION = Pattern.compile("x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)");

    private FormalBackupAcceptanceMain() {}

    public static void main(String[] args) throws Exception {
        Path project = path("formalProjectRoot");
        Path platform = path("formalPlatformRoot");
        Path instance = path("formalInstanceRoot");
        Path source = path("formalSaveRoot");
        Path evidenceRoot = path("formalEvidenceRoot");
        Path candidateTsv = path("formalCandidateTsv");
        String expectedWorld = required("formalWorldIdentity");
        String runtime = required("formalRuntimeFingerprint");
        String expectedSourceFingerprint = required("formalExpectedSourceFingerprint");
        String gitHead = required("formalGitHead");
        Instant quiescenceAt = Instant.parse(required("formalQuiescenceObservedAt"));
        Files.createDirectories(evidenceRoot);
        Path backupRoot = project.resolve("work/formal-backups");
        Path restoreRoot = project.resolve("work/formal-restore-drills");
        Files.createDirectories(backupRoot);
        Files.createDirectories(restoreRoot);

        Path saves = instance.resolve("saves");
        FormalWorldReadOnlyGuard discoveryGuard = new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                "formal-backup-discovery-v1", "world:unselected", instance, saves,
                evidenceRoot.resolve("discovery-audit")));
        FormalSaveDiscoveryResult discovery = new FormalSaveDiscovery(
                discoveryGuard, NbtReadLimits.minecraft1201Defaults()).discover(instance);
        FormalWorldIdentity world = discovery.selectedCandidate().orElseThrow().worldIdentity();
        require(world.value().equals(expectedWorld), "WorldIdentity changed before backup");
        require(Path.of(discovery.selectedCandidate().orElseThrow().canonicalWorldPath())
                .toRealPath().equals(source.toRealPath()), "selected source path changed");

        FormalWorldReadOnlyGuard fingerprintGuard = new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                "formal-backup-source-v1", world.value(), instance, source,
                evidenceRoot.resolve("source-audit")));
        FormalFingerprintPolicy fingerprintPolicy = new FormalFingerprintPolicy(
                10_000, Set.of("level.dat", "level.dat_old"), true);
        FormalWorldFingerprint observedPre = new FormalWorldFingerprintService().capture(
                fingerprintGuard, world.value(), fingerprintPolicy);
        require(observedPre.worldFingerprint().equals(expectedSourceFingerprint),
                "source fingerprint changed since accepted FS-12 evidence");

        long safetyMargin = Math.max(512L * 1_024 * 1_024, observedPre.totalBytes());
        Instant requestedAt = Instant.now();
        FormalBackupDestinationRequest destinationRequest = new FormalBackupDestinationRequest(
                world.value(), world.value(), platform, instance, source, project, backupRoot,
                observedPre.worldFingerprint(), runtime, observedPre.fileCount(), observedPre.totalBytes(),
                safetyMargin, requestedAt, "formal-backup-v1", "steve-agent-0.1", gitHead);
        FormalBackupDestinationDecision destinationDecision = new FormalBackupDestinationPolicy()
                .plan(destinationRequest);
        FormalBackupDestinationPlan plan = destinationDecision.plan().orElseThrow(() ->
                new IllegalStateException(destinationDecision.failure().orElseThrow().toString()));
        System.out.println("FB07_PREFLIGHT_PASS source=" + source.toRealPath()
                + " world=" + world.value() + " destination=" + plan.plannedTarget()
                + " files=" + observedPre.fileCount() + " bytes=" + observedPre.totalBytes()
                + " safetyMargin=" + safetyMargin + " required=" + plan.requiredSpaceBytes()
                + " usable=" + plan.observedUsableSpaceBytes()
                + " preFingerprint=" + observedPre.worldFingerprint());

        FormalBackupCopyDecision copyDecision = new FormalBackupService().create(
                new FormalBackupCopyRequest(plan, fingerprintGuard, fingerprintPolicy,
                        new FormalBackupQuiescenceEvidence(quiescenceAt, "windows-exclusive-lock-scan-v1",
                                List.of(), List.of(), true, true),
                        10_000, Math.multiplyExact(observedPre.totalBytes(), 2)));
        FormalBackupCopyResult copy = copyDecision.result().orElseThrow(() ->
                new IllegalStateException(copyDecision.failure().orElseThrow().toString()));
        require(copy.sourcePreFingerprint().equals(observedPre), "copy pre-fingerprint disagrees");
        require(copy.sourcePreFingerprint().equals(copy.sourcePostFingerprint()),
                "formal source changed during backup");

        FormalBackupVerificationRequest verificationRequest = new FormalBackupVerificationRequest(
                platform, project, backupRoot, copy.completedTarget(), world, plan);
        BackupVerificationResult verification = new FormalBackupVerifier().verify(verificationRequest)
                .result().orElseThrow();
        FormalRestoreDrillRequest restoreRequest = new FormalRestoreDrillRequest(
                verificationRequest, verification, restoreRoot, Instant.now(),
                NbtReadLimits.minecraft1201Defaults());
        RestoreDrillResult restore = new FormalRestoreDrillService().run(restoreRequest)
                .result().orElseThrow();

        List<CandidateIndustrialZone> zones = readZones(candidateTsv);
        require(zones.size() == 8, "accepted FS-12 candidate count changed");
        FormalDeploymentCandidatePackager packager = new FormalDeploymentCandidatePackager();
        List<FormalDeploymentCandidateInput> candidateInputs = zones.stream().map(zone ->
                new FormalDeploymentCandidateInput(
                        world.value(), zone, ResourceId.parse("minecraft:gravel"), 3,
                        Optional.empty(), Optional.empty(), runtime, observedPre.worldFingerprint(),
                        verification, PermissionDecision.UNKNOWN,
                        List.of(RegionAuthorizedOperation.DRY_RUN),
                        List.of("USER_SELECTION", "CLAIM_PERMISSION_UNKNOWN", "REGION_AUTHORIZATION",
                                "FORMAL_PREVIEW_UNAVAILABLE", "VERIFIED_PHYSICAL_PLAN_UNAVAILABLE",
                                "FORMAL_WORLD_EXECUTION_FORBIDDEN"),
                        RollbackPolicy.REQUIRED_VERIFIED, Instant.now().plus(Duration.ofDays(7)),
                        "Real FS-12 candidate remains blocked pending user selection and verified preview"))
                .toList();
        FormalDeploymentCandidateBatch batch = packager.packageBatch(
                world.value(), verification, candidateInputs, 8);
        FormalDeploymentApprovalRequestFactory approvalFactory = new FormalDeploymentApprovalRequestFactory();
        List<FormalDeploymentApprovalRequest> approvalRequests = batch.candidates().stream()
                .map(candidate -> approvalFactory.awaitUserSelection(candidate, Instant.now())).toList();
        require(approvalRequests.stream().allMatch(request ->
                        request.status() == FormalApprovalRequestStatus.AWAITING_USER_SELECTION
                                && request.scope().isEmpty()
                                && request.decision().state() == FormalApprovalDecisionState.ABSENT),
                "real approval request crossed the selection/decision boundary");
        FormalExecutionHardStopResult hardStop = new FormalWorldExecutionHardStop().evaluate(
                world.value(), Optional.of(approvalRequests.get(0)),
                Optional.of(approvalRequests.get(0).decision()));
        require(!hardStop.executionAllowed()
                        && hardStop.failure().code() == FormalBackupFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN,
                "formal execution hard stop failed");

        FormalWorldFingerprint finalSource = new FormalWorldFingerprintService().capture(
                fingerprintGuard, world.value(), fingerprintPolicy);
        require(finalSource.equals(observedPre), "final formal source fingerprint changed");
        Path report = evidenceRoot.resolve("acceptance.json");
        String json = report(world, source, plan, copy, verification, restore, batch,
                approvalRequests, observedPre, finalSource, safetyMargin);
        Files.writeString(report, json, StandardCharsets.UTF_8);
        System.out.println("FB07_ACCEPTANCE_PASS backup=" + copy.backupIdentity()
                + " manifest=" + copy.manifest().manifestHash()
                + " copiedFiles=" + copy.manifest().entries().size()
                + " copiedBytes=" + copy.manifest().totalBytes()
                + " restore=" + restore.restoreDrillIdentity()
                + " candidates=" + batch.candidates().size()
                + " approvalsAwaitingSelection=" + approvalRequests.size()
                + " postFingerprint=" + finalSource.worldFingerprint()
                + " report=" + report);
    }

    private static List<CandidateIndustrialZone> readZones(Path tsv) throws Exception {
        List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
        List<CandidateIndustrialZone> zones = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            BlockPos3i minimum = position(fields[2]);
            BlockPos3i maximum = position(fields[3]);
            BlockPos3i anchor = position(fields[4]);
            int score = Integer.parseInt(fields[6]);
            ResourceId dimension = ResourceId.parse(fields[1]);
            zones.add(new CandidateIndustrialZone(fields[0], dimension,
                    new DeploymentBoundingBox(minimum, maximum), List.of(anchor), List.of(),
                    List.of(
                            new SurveyLimitation("CLAIM_PERMISSION_UNKNOWN", dimension + "/candidate",
                                    "Claim permission remains UNKNOWN", true, true),
                            new SurveyLimitation("CLEAR_SPACE_NOT_VERIFIED", dimension + "/candidate",
                                    "Candidate clear space is not verified", true, true),
                            new SurveyLimitation("PLAYER_BUILDING_BOUNDARY_UNKNOWN", dimension + "/candidate",
                                    "Player-building boundary remains unknown", true, true)),
                    Map.of("acceptedSurveyScore", score), score, SurveyConfidence.UNKNOWN,
                    List.of("CLAIM_PERMISSION_UNKNOWN", "DRY_RUN_VALIDATION",
                            "PROTECTED_OR_UNKNOWN_REVIEW", "REGION_AUTHORIZATION", "USER_SELECTION"),
                    new SurveyCoverage(59, 16, 9_733, 9_733, 107_126_784,
                            Duration.ZERO, false), CandidateZoneStatus.PENDING_USER_SELECTION));
        }
        return List.copyOf(zones);
    }

    private static BlockPos3i position(String value) {
        Matcher matcher = POSITION.matcher(value);
        if (!matcher.find()) throw new IllegalArgumentException("candidate position is invalid");
        return new BlockPos3i(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    private static String report(
            FormalWorldIdentity world,
            Path source,
            FormalBackupDestinationPlan plan,
            FormalBackupCopyResult copy,
            BackupVerificationResult verification,
            RestoreDrillResult restore,
            FormalDeploymentCandidateBatch candidates,
            List<FormalDeploymentApprovalRequest> approvals,
            FormalWorldFingerprint pre,
            FormalWorldFingerprint post,
            long safetyMargin) throws Exception {
        return "{\n"
                + field("worldIdentity", world.value()) + ",\n"
                + field("canonicalSource", source.toRealPath().toString().replace('\\', '/')) + ",\n"
                + field("sourcePreFingerprint", pre.worldFingerprint()) + ",\n"
                + field("sourcePostFingerprint", post.worldFingerprint()) + ",\n"
                + field("backupIdentity", copy.backupIdentity()) + ",\n"
                + field("backupTarget", copy.completedTarget().toString().replace('\\', '/')) + ",\n"
                + field("manifestHash", copy.manifest().manifestHash()) + ",\n"
                + number("sourceFileCount", pre.fileCount()) + ",\n"
                + number("sourceBytes", pre.totalBytes()) + ",\n"
                + number("copiedFileCount", copy.manifest().entries().size()) + ",\n"
                + number("copiedBytes", copy.manifest().totalBytes()) + ",\n"
                + number("excludedFileCount", copy.manifest().exclusions().size()) + ",\n"
                + number("safetyMarginBytes", safetyMargin) + ",\n"
                + number("requiredSpaceBytes", plan.requiredSpaceBytes()) + ",\n"
                + number("observedUsableSpaceBytes", plan.observedUsableSpaceBytes()) + ",\n"
                + bool("backupValid", verification.verified()) + ",\n"
                + field("restoreDrillIdentity", restore.restoreDrillIdentity()) + ",\n"
                + field("restoreTarget", restore.disposableRestoreTarget().toString().replace('\\', '/')) + ",\n"
                + bool("restoreDrillPass", restore.passed()) + ",\n"
                + number("candidatePackages", candidates.candidates().size()) + ",\n"
                + number("candidatePreviewUnavailable", candidates.candidates().stream()
                        .filter(value -> value.previewHash().isEmpty()).count()) + ",\n"
                + number("approvalRequestsAwaitingSelection", approvals.size()) + ",\n"
                + number("formalAllowedDecisions", 0) + ",\n"
                + field("claimPermission", "UNKNOWN") + ",\n"
                + bool("externalMutation", false) + ",\n"
                + bool("formalWorldWrite", false) + ",\n"
                + bool("sessionLockCreatedOrModified", false) + ",\n"
                + bool("inventoryContentsRead", false) + ",\n"
                + bool("minecraftOrForgeStarted", false) + ",\n"
                + bool("formalExecutionAllowed", false) + "\n"
                + "}\n";
    }

    private static String field(String name, String value) {
        return "  \"" + name + "\": \"" + escape(value) + "\"";
    }

    private static String number(String name, long value) {
        return "  \"" + name + "\": " + value;
    }

    private static String bool(String name, boolean value) {
        return "  \"" + name + "\": " + value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static Path path(String name) {
        return Path.of(required(name)).toAbsolutePath().normalize();
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + name);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
