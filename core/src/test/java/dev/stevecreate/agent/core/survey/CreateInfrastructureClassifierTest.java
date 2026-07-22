package dev.stevecreate.agent.core.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CreateInfrastructureClassifierTest {
    private static final String HASH = "a".repeat(64);
    private static final ResourceId OVERWORLD = ResourceId.parse("minecraft:overworld");

    @Test
    void classifiesRequiredCreatePowerTransmissionProcessingItemFluidAndMovingBlocks() {
        List<String> ids = List.of(
                "create:water_wheel", "create:large_water_wheel", "create:steam_engine",
                "create:shaft", "create:cogwheel", "create:large_cogwheel", "create:gearbox", "create:clutch",
                "create:rotation_speed_controller", "create:stressometer", "create:speedometer",
                "create:belt", "create:chute", "create:andesite_funnel", "create:brass_tunnel", "create:depot",
                "create:mechanical_arm", "create:portable_storage_interface",
                "create:fluid_pipe", "create:fluid_tank", "create:mechanical_pump", "create:hose_pulley",
                "create:millstone", "create:crushing_wheel", "create:mechanical_press", "create:mechanical_mixer",
                "create:basin", "create:encased_fan", "create:mechanical_saw", "create:deployer",
                "create:mechanical_drill", "create:mechanical_harvester", "create:mechanical_plough",
                "create:mechanical_piston");
        ClassifiedChunkInfrastructure result = new CreateInfrastructureClassifier().classify(
                context(List.of()), chunk(ids, List.of()));

        assertThat(result.findings()).hasSize(ids.size());
        assertThat(result.findings()).extracting(finding -> finding.resourceId().toString())
                .containsAll(ids);
        assertThat(result.findings()).filteredOn(PowerFinding.class::isInstance)
                .extracting(InfrastructureFinding::category)
                .contains(SurveyFindingCategory.POWER_SOURCE, SurveyFindingCategory.TRANSMISSION);
        assertThat(result.findings()).filteredOn(LogisticsFinding.class::isInstance)
                .extracting(finding -> ((LogisticsFinding) finding).transportType())
                .contains(GenericResourceType.ITEM, GenericResourceType.FLUID);
        assertThat(result.findings()).filteredOn(CreateMachineFinding.class::isInstance)
                .extracting(InfrastructureFinding::category)
                .contains(SurveyFindingCategory.PROCESSING, SurveyFindingCategory.MOVING_STRUCTURE);
    }

    @Test
    void storageIsPresenceOnlyBlockEntityAttributedAndCandidateAware() {
        BlockPos3i position = new BlockPos3i(0, 0, 0);
        OfflineBlockEntity chestEntity = new OfflineBlockEntity(ResourceId.parse("minecraft:chest"), position);
        OfflineBlockEntity drawerEntity = new OfflineBlockEntity(
                ResourceId.parse("storagedrawers:standard_drawers_1"), new BlockPos3i(3, 0, 0));
        InfrastructureClassificationContext context = context(List.of(new DeploymentBoundingBox(
                new BlockPos3i(-1, -1, -1), new BlockPos3i(1, 1, 1))));

        ClassifiedChunkInfrastructure result = new CreateInfrastructureClassifier().classify(
                context, chunk(List.of("minecraft:chest", "create:item_vault", "minecraft:blue_shulker_box",
                                "storagedrawers:oak_full_drawers_1"),
                        List.of(chestEntity, drawerEntity)));

        assertThat(result.findings()).hasSize(4).allMatch(StorageFinding.class::isInstance);
        StorageFinding chest = (StorageFinding) result.findings().stream()
                .filter(finding -> finding.resourceId().equals(ResourceId.parse("minecraft:chest")))
                .findFirst().orElseThrow();
        assertThat(chest.blockEntityPresent()).isTrue();
        assertThat(chest.withinCandidateZone()).isTrue();
        assertThat(chest.contentsRead()).isFalse();
        assertThat(chest.futureContentAuthorizationRequired()).isTrue();
        assertThat(chest.evidence()).extracting(SurveyEvidence::detail)
                .contains("blockEntityType=minecraft:chest")
                .noneMatch(detail -> detail.contains("Items") || detail.contains("Count"));
        StorageFinding drawer = (StorageFinding) result.findings().stream()
                .filter(finding -> finding.resourceId().namespace().equals("storagedrawers"))
                .findFirst().orElseThrow();
        assertThat(drawer.confidence()).isEqualTo(SurveyConfidence.DERIVED_LOW_CONFIDENCE);
        assertThat(drawer.contentsRead()).isFalse();
    }

    @Test
    void outputIsDeterministicAndOfflineClaimsRemainUnknown() {
        OfflineChunkSnapshot chunk = chunk(List.of(
                "create:shaft", "create:belt", "create:unknown_pack_machine"), List.of());
        CreateInfrastructureClassifier classifier = new CreateInfrastructureClassifier();

        ClassifiedChunkInfrastructure first = classifier.classify(context(List.of()), chunk);
        ClassifiedChunkInfrastructure second = classifier.classify(context(List.of()), chunk);

        assertThat(first).isEqualTo(second);
        assertThat(first.findings()).filteredOn(PowerFinding.class::isInstance).allSatisfy(finding -> {
            assertThat(((PowerFinding) finding).liveRpmKnown()).isFalse();
            assertThat(((PowerFinding) finding).liveStressKnown()).isFalse();
        });
        assertThat(first.findings()).filteredOn(LogisticsFinding.class::isInstance)
                .allSatisfy(finding -> assertThat(((LogisticsFinding) finding).runtimeConnectivityKnown()).isFalse());
        assertThat(first.findings()).filteredOn(finding -> finding.resourceId().path().equals("unknown_pack_machine"))
                .extracting(InfrastructureFinding::category)
                .containsExactly(SurveyFindingCategory.OTHER_INFRASTRUCTURE);
    }

    @Test
    void naturalCreateGeologyCannotBecomeInfrastructureOrCandidateEvidence() {
        List<String> natural = List.of("create:zinc_ore", "create:deepslate_zinc_ore", "create:asurine",
                "create:crimsite", "create:limestone", "create:ochrum", "create:scoria",
                "create:scorchia", "create:veridium", "create:raw_zinc_block");

        ClassifiedChunkInfrastructure result = new CreateInfrastructureClassifier().classify(
                context(List.of()), chunk(natural, List.of()));

        assertThat(result.findings()).isEmpty();
    }

    @Test
    void rejectsMismatchedContextDuplicateSectionsAndInvalidLocalCoordinates() {
        OfflineChunkSnapshot chunk = chunk(List.of("create:shaft"), List.of());
        InfrastructureClassificationContext wrong = new InfrastructureClassificationContext(
                "world:" + HASH, OVERWORLD, 1, 0, "region/r.1.0.mca", HASH, 7, List.of());
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new CreateInfrastructureClassifier().classify(wrong, chunk));
        OfflineChunkSection section = chunk.sections().get(0);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> section.blockStateAt(16, 0, 0));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new OfflineChunkSnapshot(3_465, 0, 0, 2, 1, 1, 7,
                        List.of(section, section), List.of()));
    }

    private static InfrastructureClassificationContext context(List<DeploymentBoundingBox> bounds) {
        return new InfrastructureClassificationContext(
                "world:" + HASH, OVERWORLD, 0, 0, "region/r.0.0.mca", HASH, 7, bounds);
    }

    private static OfflineChunkSnapshot chunk(List<String> resourceIds, List<OfflineBlockEntity> blockEntities) {
        List<OfflineBlockState> palette = new ArrayList<>();
        palette.add(new OfflineBlockState(ResourceId.parse("minecraft:air"), Map.of()));
        resourceIds.forEach(id -> palette.add(new OfflineBlockState(ResourceId.parse(id),
                id.equals("create:shaft") ? Map.of("axis", "x") : Map.of())));
        long[] packed = pack(palette.size(), resourceIds.size());
        return new OfflineChunkSnapshot(3_465, 0, 0, 2, 1, 1, 7,
                List.of(new OfflineChunkSection(0, palette, packed)), blockEntities);
    }

    private static long[] pack(int paletteSize, int populatedBlocks) {
        if (paletteSize == 1) return new long[0];
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        int valuesPerLong = 64 / bits;
        long[] packed = new long[OfflineChunkSection.expectedPackedLongs(paletteSize)];
        for (int blockIndex = 0; blockIndex < populatedBlocks; blockIndex++) {
            int longIndex = blockIndex / valuesPerLong;
            int offset = (blockIndex % valuesPerLong) * bits;
            packed[longIndex] |= (long) (blockIndex + 1) << offset;
        }
        return packed;
    }
}
