package dev.stevecreate.agent.core.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormalSurveyModelTest {
    private static final String HASH = "a".repeat(64);
    private static final ResourceId OVERWORLD = ResourceId.parse("minecraft:overworld");

    @Test
    void everyFindingRetainsExactLocationResourceConfidenceEvidenceFingerprintAndGeneration() {
        SurveyLocation location = location();
        SurveyEvidence evidence = evidence();
        List<InfrastructureFinding> findings = List.of(
                new CreateMachineFinding(location, ResourceId.parse("create:millstone"),
                        SurveyFindingCategory.PROCESSING, SurveyConfidence.OBSERVED, List.of(evidence)),
                new StorageFinding(location, ResourceId.parse("create:item_vault"), true, true,
                        false, true, SurveyConfidence.OBSERVED, List.of(evidence)),
                new PowerFinding(location, ResourceId.parse("create:water_wheel"),
                        SurveyFindingCategory.POWER_SOURCE, SurveyConfidence.DERIVED_HIGH_CONFIDENCE,
                        false, false, List.of(evidence)),
                new LogisticsFinding(location, ResourceId.parse("create:belt"), GenericResourceType.ITEM,
                        SurveyConfidence.DERIVED_LOW_CONFIDENCE, false, List.of(evidence)));

        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.location().worldIdentity()).isEqualTo("world:" + HASH);
            assertThat(finding.location().dimension()).isEqualTo(OVERWORLD);
            assertThat(finding.location().regionX()).isZero();
            assertThat(finding.location().chunkX()).isEqualTo(1);
            assertThat(finding.location().position()).isEqualTo(new BlockPos3i(20, 64, 20));
            assertThat(finding.location().fileFingerprint()).isEqualTo(HASH);
            assertThat(finding.location().parseGeneration()).isEqualTo(7);
            assertThat(finding.evidence()).containsExactly(evidence);
        });
        StorageFinding storage = (StorageFinding) findings.get(1);
        assertThat(storage.contentsRead()).isFalse();
        assertThat(storage.futureContentAuthorizationRequired()).isTrue();
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new StorageFinding(location, ResourceId.parse("minecraft:chest"), true, false,
                        true, false, SurveyConfidence.OBSERVED, List.of(evidence)));
    }

    @Test
    void completeSurveyIsDeterministicPendingAndStructurallyNonExecutable() {
        SurveyEvidence evidence = evidence();
        CreateMachineFinding machine = new CreateMachineFinding(location(), ResourceId.parse("create:millstone"),
                SurveyFindingCategory.PROCESSING, SurveyConfidence.OBSERVED, List.of(evidence));
        ChunkSurvey chunk = new ChunkSurvey(1, 1, SurveyStatus.COMPLETE, 100, 200,
                List.of(machine), List.of(evidence), List.of());
        RegionSurvey region = new RegionSurvey(0, 0, "region/r.0.0.mca", HASH,
                SurveyStatus.COMPLETE, List.of(chunk), List.of(evidence), List.of());
        DimensionSurvey dimension = new DimensionSurvey(OVERWORLD, SurveyStatus.COMPLETE,
                List.of(region), List.of(evidence), List.of());
        SurveyCoverage coverage = new SurveyCoverage(1, 1, 1, 1, 100, Duration.ofSeconds(1), false);
        CandidateIndustrialZone zone = new CandidateIndustrialZone(
                "zone:" + HASH, OVERWORLD,
                new DeploymentBoundingBox(new BlockPos3i(0, 60, 0), new BlockPos3i(31, 80, 31)),
                List.of(new BlockPos3i(16, 64, 16)), List.of(machine), List.of(),
                Map.of("infrastructure", 10, "risk", -2), 8, SurveyConfidence.DERIVED_HIGH_CONFIDENCE,
                List.of("USER_SELECTION", "REGION_AUTHORIZATION", "CLAIM_PERMISSION"),
                coverage, CandidateZoneStatus.PENDING_USER_SELECTION);
        FormalWorldIdentity identity = new FormalWorldIdentity(
                "world:" + HASH, "Main", "Main", 3_465, "1.20.1", List.of("minecraft:overworld"));

        FormalWorldSurvey survey = new FormalWorldSurvey(identity, SurveyStatus.COMPLETE, HASH, HASH,
                false, false, false, false, List.of(dimension), List.of(zone), coverage,
                List.of(evidence), List.of());

        assertThat(survey.status()).isEqualTo(SurveyStatus.COMPLETE);
        assertThat(survey.candidateZones()).extracting(CandidateIndustrialZone::status)
                .containsExactly(CandidateZoneStatus.PENDING_USER_SELECTION);
        assertThat(survey.externalMutation()).isFalse();
        assertThat(survey.formalWorldWrite()).isFalse();
        assertThat(survey.inventoryContentsRead()).isFalse();
        assertThat(survey.executionAllowed()).isFalse();
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> survey.dimensions().clear());
    }

    @Test
    void completeSurveyRejectsFingerprintDriftAndOfflineModelsRejectLiveClaims() {
        FormalWorldIdentity identity = new FormalWorldIdentity(
                "world:" + HASH, "Main", "Main", 3_465, "1.20.1", List.of("minecraft:overworld"));
        SurveyCoverage coverage = new SurveyCoverage(0, 0, 0, 0, 0, Duration.ZERO, false);

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new FormalWorldSurvey(identity, SurveyStatus.COMPLETE, HASH, "b".repeat(64),
                        false, false, false, false, List.of(), List.of(), coverage, List.of(), List.of()));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new PowerFinding(location(), ResourceId.parse("create:shaft"),
                        SurveyFindingCategory.TRANSMISSION, SurveyConfidence.RUNTIME_REQUIRED,
                        true, false, List.of(evidence())));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new LogisticsFinding(location(), ResourceId.parse("create:belt"), GenericResourceType.ITEM,
                        SurveyConfidence.RUNTIME_REQUIRED, true, List.of(evidence())));
    }

    @Test
    void typedFailureVocabularyIsCompleteAndModelsHaveNoLoaderTypes() {
        assertThat(FormalSurveyFailureCode.values()).containsExactly(
                FormalSurveyFailureCode.FORMAL_SAVE_ROOT_NOT_FOUND,
                FormalSurveyFailureCode.MULTIPLE_FORMAL_WORLDS_AMBIGUOUS,
                FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED,
                FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT,
                FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN,
                FormalSurveyFailureCode.FORMAL_WORLD_WRITE_ATTEMPT,
                FormalSurveyFailureCode.FORMAL_WORLD_LOCK_ATTEMPT,
                FormalSurveyFailureCode.FORMAL_WORLD_PROCESS_START_FORBIDDEN,
                FormalSurveyFailureCode.LEVEL_DAT_UNREADABLE,
                FormalSurveyFailureCode.WORLD_DATA_VERSION_UNSUPPORTED,
                FormalSurveyFailureCode.REGION_HEADER_INVALID,
                FormalSurveyFailureCode.REGION_CHUNK_OFFSET_INVALID,
                FormalSurveyFailureCode.CHUNK_COMPRESSION_UNSUPPORTED,
                FormalSurveyFailureCode.CHUNK_NBT_TOO_LARGE,
                FormalSurveyFailureCode.CHUNK_NBT_CORRUPT,
                FormalSurveyFailureCode.SURVEY_BUDGET_EXHAUSTED,
                FormalSurveyFailureCode.SURVEY_PARTIAL,
                FormalSurveyFailureCode.BLOCK_PALETTE_UNSUPPORTED,
                FormalSurveyFailureCode.BLOCK_ENTITY_UNSUPPORTED,
                FormalSurveyFailureCode.CREATE_INFRASTRUCTURE_NOT_FOUND,
                FormalSurveyFailureCode.CANDIDATE_ZONE_NOT_FOUND,
                FormalSurveyFailureCode.CLAIM_PERMISSION_UNKNOWN,
                FormalSurveyFailureCode.FORMAL_INVENTORY_CONTENTS_FORBIDDEN,
                FormalSurveyFailureCode.WORLD_FINGERPRINT_CHANGED_DURING_SURVEY,
                FormalSurveyFailureCode.EXTERNAL_MUTATION_DETECTED,
                FormalSurveyFailureCode.FORMAL_WORLD_DRY_RUN_ONLY,
                FormalSurveyFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN);

        List<Class<?>> models = List.of(FormalWorldSurvey.class, DimensionSurvey.class, RegionSurvey.class,
                ChunkSurvey.class, SurveyLocation.class, SurveyEvidence.class, SurveyLimitation.class,
                CreateMachineFinding.class, StorageFinding.class, PowerFinding.class,
                LogisticsFinding.class, CandidateIndustrialZone.class);
        assertThat(models.stream().flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getType).map(Class::getName))
                .noneMatch(name -> name.startsWith("net.minecraft") || name.contains("minecraftforge")
                        || name.contains("simibubi") || name.contains("mekanism") || name.contains("nbt"));
    }

    private static SurveyLocation location() {
        return new SurveyLocation("world:" + HASH, OVERWORLD, 0, 0, 1, 1,
                new BlockPos3i(20, 64, 20), HASH, 7,
                SurveyEvidenceSource.BLOCK_PALETTE, List.of("runtime state not observed"));
    }

    private static SurveyEvidence evidence() {
        return new SurveyEvidence(SurveyEvidenceSource.BLOCK_PALETTE, "region/r.0.0.mca",
                HASH, 7, SurveyConfidence.OBSERVED, "palette contains exact resource id", true);
    }
}
