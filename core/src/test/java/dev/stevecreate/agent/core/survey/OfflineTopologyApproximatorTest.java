package dev.stevecreate.agent.core.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfflineTopologyApproximatorTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void buildsHighConfidenceAxisCompatiblePowerItemAndFluidAdjacency() {
        List<InfrastructureFinding> findings = List.of(
                power("create:shaft", 0, "x"), power("create:cogwheel", 1, "x"),
                logistics("create:belt", 3, GenericResourceType.ITEM, "x"),
                logistics("create:chute", 4, GenericResourceType.ITEM, "x"),
                logistics("create:fluid_pipe", 6, GenericResourceType.FLUID, "x"),
                logistics("create:mechanical_pump", 7, GenericResourceType.FLUID, "x"));

        OfflineTopologyGraph graph = new OfflineTopologyApproximator().approximate(findings, true, List.of());

        assertThat(graph.edges()).extracting(OfflineTopologyEdge::resourceType)
                .containsExactly(GenericResourceType.ROTATIONAL_POWER, GenericResourceType.ITEM,
                        GenericResourceType.FLUID);
        assertThat(graph.edges()).extracting(OfflineTopologyEdge::confidence)
                .containsOnly(SurveyConfidence.DERIVED_HIGH_CONFIDENCE);
        assertThat(graph.edges()).allSatisfy(edge -> assertThat(edge.runtimeConnectivityKnown()).isFalse());
    }

    @Test
    void machineAndStorageAttachmentRemainLowConfidenceAndRuntimeUnknown() {
        List<InfrastructureFinding> findings = List.of(
                power("create:shaft", 0, "x"), machine("create:millstone", 1),
                logistics("create:belt", 3, GenericResourceType.ITEM, null), storage("minecraft:chest", 4),
                logistics("create:fluid_pipe", 6, GenericResourceType.FLUID, null), storage("create:fluid_tank", 7));

        OfflineTopologyGraph graph = new OfflineTopologyApproximator().approximate(findings, true, List.of());

        assertThat(graph.edges()).hasSize(3);
        assertThat(graph.edges()).extracting(OfflineTopologyEdge::confidence)
                .containsOnly(SurveyConfidence.DERIVED_LOW_CONFIDENCE);
        assertThat(graph.edges()).allSatisfy(edge -> assertThat(edge.runtimeConnectivityKnown()).isFalse());
    }

    @Test
    void partialCoverageRequiresExplicitUnscannedBoundaryAndOutputIsDeterministic() {
        SurveyLimitation boundary = new SurveyLimitation(
                "UNSCANNED_REGION_BOUNDARY", "minecraft:overworld/r.1.0", "region was outside budget", false, true);
        List<InfrastructureFinding> findings = List.of(power("create:shaft", 1, "x"), power("create:shaft", 0, "x"));
        OfflineTopologyApproximator service = new OfflineTopologyApproximator();

        OfflineTopologyGraph first = service.approximate(findings, false, List.of(boundary));
        OfflineTopologyGraph second = service.approximate(List.of(findings.get(1), findings.get(0)),
                false, List.of(boundary));

        assertThat(first).isEqualTo(second);
        assertThat(first.coverageComplete()).isFalse();
        assertThat(first.unscannedBoundaries()).containsExactly(boundary);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.approximate(findings, false, List.of()));
    }

    @Test
    void rejectsDuplicatePositionsAndDoesNotConnectAcrossGaps() {
        InfrastructureFinding first = power("create:shaft", 0, "x");
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new OfflineTopologyApproximator().approximate(List.of(first, first), true, List.of()));
        OfflineTopologyGraph graph = new OfflineTopologyApproximator().approximate(
                List.of(first, power("create:shaft", 2, "x")), true, List.of());
        assertThat(graph.edges()).isEmpty();
    }

    @Test
    void crossRegionAdjacencyRetainsBothPersistedSources() {
        InfrastructureFinding first = power("create:shaft", 0, null, "region/r.0.0.mca");
        InfrastructureFinding second = power("create:shaft", 1, "x", "region/r.1.0.mca");

        OfflineTopologyEdge edge = new OfflineTopologyApproximator()
                .approximate(List.of(first, second), true, List.of()).edges().get(0);

        assertThat(edge.evidence()).filteredOn(evidence -> evidence.source() == SurveyEvidenceSource.ADJACENCY)
                .extracting(SurveyEvidence::relativeSourcePath)
                .containsExactly("region/r.0.0.mca", "region/r.1.0.mca");
        assertThat(edge.evidence()).filteredOn(evidence ->
                        evidence.source() == SurveyEvidenceSource.ORIENTATION_PROPERTY)
                .extracting(SurveyEvidence::relativeSourcePath)
                .containsExactly("region/r.1.0.mca");
        assertThat(edge.confidence()).isEqualTo(SurveyConfidence.DERIVED_HIGH_CONFIDENCE);
    }

    private static PowerFinding power(String id, int x, String axis) {
        return power(id, x, axis, "region/r.0.0.mca");
    }

    private static PowerFinding power(String id, int x, String axis, String source) {
        return new PowerFinding(location(x), ResourceId.parse(id), SurveyFindingCategory.TRANSMISSION,
                SurveyConfidence.OBSERVED, false, false, evidence(id, axis, source));
    }

    private static LogisticsFinding logistics(String id, int x, GenericResourceType type, String axis) {
        return new LogisticsFinding(location(x), ResourceId.parse(id), type,
                SurveyConfidence.OBSERVED, false, evidence(id, axis));
    }

    private static CreateMachineFinding machine(String id, int x) {
        return new CreateMachineFinding(location(x), ResourceId.parse(id), SurveyFindingCategory.PROCESSING,
                SurveyConfidence.OBSERVED, evidence(id, null));
    }

    private static StorageFinding storage(String id, int x) {
        return new StorageFinding(location(x), ResourceId.parse(id), true, false,
                false, true, SurveyConfidence.OBSERVED, evidence(id, null));
    }

    private static SurveyLocation location(int x) {
        return new SurveyLocation("world:" + HASH, ResourceId.parse("minecraft:overworld"), 0, 0, 0, 0,
                new BlockPos3i(x, 64, 0), HASH, 1, SurveyEvidenceSource.PERSISTED_BLOCK_STATE, List.of());
    }

    private static List<SurveyEvidence> evidence(String id, String axis) {
        return evidence(id, axis, "region/r.0.0.mca");
    }

    private static List<SurveyEvidence> evidence(String id, String axis, String source) {
        var base = new SurveyEvidence(SurveyEvidenceSource.BLOCK_PALETTE, source, HASH, 1,
                SurveyConfidence.OBSERVED, "resource=" + id, true);
        if (axis == null) return List.of(base);
        return List.of(base, new SurveyEvidence(SurveyEvidenceSource.ORIENTATION_PROPERTY,
                source, HASH, 1, SurveyConfidence.OBSERVED, "axis=" + axis, true));
    }
}
