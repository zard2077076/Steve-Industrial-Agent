package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.QuarterTurn;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.survey.FormalDryRunKind;
import dev.stevecreate.agent.core.survey.FormalWorldDryRunBatch;
import dev.stevecreate.agent.core.survey.FormalWorldDryRunCandidate;
import dev.stevecreate.agent.core.survey.CandidateIndustrialZone;
import dev.stevecreate.agent.core.survey.CandidateZoneStatus;
import dev.stevecreate.agent.core.survey.SurveyConfidence;
import dev.stevecreate.agent.core.survey.SurveyCoverage;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class FormalWorldDryRunBatchTest {
    private static final String HASH = "a".repeat(64);
    private static final String SNAPSHOT = "snapshot:formal:read-only";
    private static final String RUNTIME = "runtime:deceasedcraft:verified";

    @Test
    void acceptsExactlyFourHashedFormalDryRunsAndKeepsEveryAuthorityFalse() {
        FormalWorldDryRunBatch batch = batch(candidates());

        assertThat(batch.candidates()).extracting(FormalWorldDryRunCandidate::kind)
                .containsExactly(FormalDryRunKind.values());
        assertThat(batch.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.report().preview().hasValidHash()).isTrue();
            assertThat(candidate.report().formalWorldExecutable()).isFalse();
            assertThat(candidate.report().sessionCreated()).isFalse();
            assertThat(candidate.inventoryContentsRead()).isFalse();
            assertThat(candidate.resourceOperationPerformed()).isFalse();
            assertThat(candidate.approvalCreated()).isFalse();
            assertThat(candidate.executionAllowed()).isFalse();
        });
        assertThat(batch.claimPermission()).isEqualTo(PermissionDecision.UNKNOWN);
        assertThat(batch.executionAllowed()).isFalse();
    }

    @Test
    void rejectsWrongStandardTargetQuantityOrRecipeType() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> candidate(
                FormalDryRunKind.GRAVEL_MILLING, "minecraft:gravel", 2, "create:milling/cobblestone"));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> candidate(
                FormalDryRunKind.IRON_SHEET_PRESSING, "create:iron_sheet", 2, "create:milling/iron_ingot"));
    }

    @Test
    void rejectsIncompleteDuplicateStaleOrNonCustomBatchMembers() {
        List<FormalWorldDryRunCandidate> candidates = candidates();
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> batch(candidates.subList(0, 3)));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> batch(List.of(
                candidates.get(0), candidates.get(0), candidates.get(2), candidates.get(3))));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> candidate(
                FormalDryRunKind.PACK_CUSTOM_MILLING, "deceasedcraft:custom_dust", 1,
                "create:milling/cobblestone"));
        FormalWorldDryRunCandidate stale = candidate(FormalDryRunKind.PACK_CUSTOM_PRESSING,
                "deceasedcraft:custom_plate", 1, "kubejs:pressing/custom_plate", "snapshot:stale");
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> batch(List.of(
                candidates.get(0), candidates.get(1), candidates.get(2), stale)));
    }

    @Test
    void modelsHaveNoSessionWorldResourceApprovalOrLlmCapability() {
        assertThat(Arrays.stream(new Class<?>[] {
                    FormalWorldDryRunCandidate.class, FormalWorldDryRunBatch.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType).map(Class::getName))
                .noneMatch(name -> name.startsWith("net.minecraft") || name.contains("minecraftforge")
                        || name.contains("GenericExecutionSession") || name.contains("WorldResourceBuffer")
                        || name.contains("HumanApprovalToken") || name.toLowerCase().contains("llm"));
        FormalWorldDryRunCandidate valid = candidates().get(0);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new FormalWorldDryRunCandidate(valid.candidateZone(), valid.dimension(), valid.kind(), valid.target(),
                        valid.quantity(), valid.report(), false, false, true, true));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new FormalWorldDryRunCandidate(zoneAt(10), valid.dimension(), valid.kind(), valid.target(),
                        valid.quantity(), valid.report(), false, false, false, false));
    }

    private static List<FormalWorldDryRunCandidate> candidates() {
        return List.of(
                candidate(FormalDryRunKind.GRAVEL_MILLING, "minecraft:gravel", 3,
                        "create:milling/cobblestone"),
                candidate(FormalDryRunKind.IRON_SHEET_PRESSING, "create:iron_sheet", 2,
                        "create:pressing/iron_ingot"),
                candidate(FormalDryRunKind.PACK_CUSTOM_MILLING, "deceasedcraft:custom_dust", 1,
                        "kubejs:milling/custom_dust"),
                candidate(FormalDryRunKind.PACK_CUSTOM_PRESSING, "deceasedcraft:custom_plate", 1,
                        "kubejs:pressing/custom_plate"));
    }

    private static FormalWorldDryRunBatch batch(List<FormalWorldDryRunCandidate> candidates) {
        return new FormalWorldDryRunBatch("world:" + HASH, SNAPSHOT, RUNTIME, candidates,
                PermissionDecision.UNKNOWN, false, false, false, false, false);
    }

    private static FormalWorldDryRunCandidate candidate(
            FormalDryRunKind kind, String target, long quantity, String recipe) {
        return candidate(kind, target, quantity, recipe, SNAPSHOT);
    }

    private static FormalWorldDryRunCandidate candidate(
            FormalDryRunKind kind, String target, long quantity, String recipe, String snapshot) {
        ResourceId targetId = ResourceId.parse(target);
        DeploymentPreview unsigned = preview(targetId, quantity, ResourceId.parse(recipe), snapshot, "");
        String hash = DeploymentPreviewService.sha256(DeploymentPreviewCodec.canonicalJson(unsigned, false));
        DeploymentPreview preview = preview(targetId, quantity, ResourceId.parse(recipe), snapshot, hash);
        DeploymentRiskAssessment risk = new DeploymentRiskAssessment(hash, List.of(), RiskSeverity.INFO, false);
        DeploymentBudget budget = new DeploymentBudget(hash, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(targetId, quantity), 0, 0, OptionalLong.empty(), 1, 0, 0, 0,
                ResourceSourcePolicy.AUTO_WITHDRAW_FORBIDDEN, List.of(), true);
        DeploymentDryRunReport report = new DeploymentDryRunReport(preview, risk, budget, QuarterTurn.ZERO,
                List.of("REGION_AUTHORIZATION", "CLAIM_PERMISSION", "HUMAN_APPROVAL"),
                List.of("BACKUP_PLAN", "BACKUP_MANIFEST", "RESTORE_VERIFICATION"),
                List.of("FORMAL_WORLD_EXECUTION_FORBIDDEN"), true, false, false, false,
                false, false, false, false);
        return new FormalWorldDryRunCandidate(zone(), ResourceId.parse("minecraft:overworld"),
                kind, targetId, quantity, report,
                false, false, false, false);
    }

    private static CandidateIndustrialZone zone() {
        return zoneAt(0);
    }

    private static CandidateIndustrialZone zoneAt(int x) {
        BlockPos3i origin = new BlockPos3i(x, 64, 0);
        return new CandidateIndustrialZone("zone:" + HASH, ResourceId.parse("minecraft:overworld"),
                new DeploymentBoundingBox(origin, origin), List.of(origin), List.of(), List.of(),
                Map.of("fixture", 1), 1, SurveyConfidence.DERIVED_LOW_CONFIDENCE,
                List.of("USER_SELECTION", "REGION_AUTHORIZATION", "CLAIM_PERMISSION"),
                new SurveyCoverage(1, 1, 1, 1, 1, Duration.ZERO, false),
                CandidateZoneStatus.PENDING_USER_SELECTION);
    }

    private static DeploymentPreview preview(
            ResourceId target, long quantity, ResourceId recipe, String snapshot, String hash) {
        BlockPos3i origin = new BlockPos3i(0, 64, 0);
        return new DeploymentPreview(target, quantity, List.of(recipe),
                List.of(ResourceId.parse("create:fixture")), origin, List.of(QuarterTurn.ZERO),
                new DeploymentBoundingBox(origin, origin), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(target, quantity),
                OptionalLong.empty(), 0, List.of("offline formal assumption"), 0,
                RollbackClassification.UNSUPPORTED, WorldEnvironmentType.FORMAL_PLAYER_WORLD,
                RUNTIME, snapshot, List.of("FORMAL_WORLD_PREVIEW_ONLY"),
                List.of("REGION_AUTHORIZATION", "CLAIM_PERMISSION", "HUMAN_APPROVAL"),
                List.of("ENVIRONMENT_NOT_ALLOWED"), hash);
    }
}
