package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure offline block-state classification. It never reads a path or container payload. */
public final class CreateInfrastructureClassifier {
    private static final Set<String> POWER_SOURCES = Set.of(
            "water_wheel", "large_water_wheel", "steam_engine", "windmill_bearing");
    private static final Set<String> TRANSMISSION = Set.of(
            "shaft", "cogwheel", "large_cogwheel", "gearbox", "vertical_gearbox", "clutch",
            "gearshift", "sequenced_gearshift", "rotation_speed_controller", "stressometer", "speedometer");
    private static final Set<String> ITEM_LOGISTICS = Set.of(
            "belt", "chute", "smart_chute", "andesite_funnel", "brass_funnel", "andesite_tunnel",
            "brass_tunnel", "depot", "mechanical_arm", "portable_storage_interface");
    private static final Set<String> FLUID_LOGISTICS = Set.of(
            "fluid_pipe", "encased_fluid_pipe", "mechanical_pump", "hose_pulley", "portable_fluid_interface");
    private static final Set<String> PROCESSING = Set.of(
            "millstone", "crushing_wheel", "mechanical_press", "mechanical_mixer", "basin", "encased_fan",
            "mechanical_saw", "deployer", "mechanical_drill", "mechanical_harvester", "mechanical_plough");
    private static final Set<String> MOVING = Set.of(
            "mechanical_piston", "sticky_mechanical_piston", "gantry_shaft", "gantry_carriage", "rope_pulley",
            "cart_assembler", "sticker", "linear_chassis", "secondary_linear_chassis", "radial_chassis",
            "mechanical_bearing", "clockwork_bearing");
    private static final Set<String> CREATE_STORAGE = Set.of("item_vault", "fluid_tank", "creative_fluid_tank");
    private static final Set<String> NATURAL_CREATE_BLOCKS = Set.of(
            "asurine", "crimsite", "limestone", "ochrum", "scoria", "scorchia", "veridium",
            "zinc_ore", "deepslate_zinc_ore", "raw_zinc_block");
    private static final Set<String> VANILLA_STORAGE = Set.of("chest", "trapped_chest", "barrel", "ender_chest");
    private static final Set<String> STORAGE_HINTS = Set.of(
            "chest", "barrel", "crate", "cabinet", "locker", "vault", "tank", "storage", "cache", "drawer");
    private static final Set<String> IGNORED = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    public ClassifiedChunkInfrastructure classify(
            InfrastructureClassificationContext context,
            OfflineChunkSnapshot chunk) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(chunk, "chunk");
        if (Math.floorDiv(chunk.chunkX(), 32) != context.regionX()
                || Math.floorDiv(chunk.chunkZ(), 32) != context.regionZ()
                || chunk.parseGeneration() != context.parseGeneration()) {
            throw new IllegalArgumentException("chunk does not match classification context");
        }
        Map<BlockPos3i, ResourceId> blockEntities = new HashMap<>();
        for (OfflineBlockEntity entity : chunk.blockEntities()) {
            if (blockEntities.put(entity.position(), entity.resourceId()) != null) {
                throw new IllegalArgumentException("duplicate block entity position");
            }
        }
        List<InfrastructureFinding> findings = new ArrayList<>();
        for (OfflineChunkSection section : chunk.sections()) {
            for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        OfflineBlockState state = section.blockStateAt(localX, localY, localZ);
                        if (IGNORED.contains(state.resourceId().toString())) continue;
                        BlockPos3i position = new BlockPos3i(
                                Math.addExact(Math.multiplyExact(chunk.chunkX(), 16), localX),
                                Math.addExact(Math.multiplyExact(section.sectionY(), 16), localY),
                                Math.addExact(Math.multiplyExact(chunk.chunkZ(), 16), localZ));
                        InfrastructureFinding finding = classifyState(
                                context, chunk, state, position, blockEntities.get(position));
                        if (finding != null) findings.add(finding);
                    }
                }
            }
        }
        findings.sort(ClassifiedChunkInfrastructure.order());
        return new ClassifiedChunkInfrastructure(chunk.chunkX(), chunk.chunkZ(), findings, List.of());
    }

    private static InfrastructureFinding classifyState(
            InfrastructureClassificationContext context,
            OfflineChunkSnapshot chunk,
            OfflineBlockState state,
            BlockPos3i position,
            ResourceId blockEntityType) {
        ResourceId resource = state.resourceId();
        String path = resource.path();
        boolean create = resource.namespace().equals("create");
        if (create && NATURAL_CREATE_BLOCKS.contains(path)) return null;
        boolean explicitStorage = create && CREATE_STORAGE.contains(path)
                || resource.namespace().equals("minecraft")
                    && (VANILLA_STORAGE.contains(path) || path.endsWith("_shulker_box"));

        List<SurveyEvidence> evidence = evidence(context, state, blockEntityType);
        if (explicitStorage) {
            boolean withinCandidate = context.candidateBounds().stream().anyMatch(bounds -> bounds.contains(position));
            return new StorageFinding(location(context, chunk, position, List.of(
                    "CONTENTS_NOT_READ", "FUTURE_CONTENT_AUTHORIZATION_REQUIRED")),
                    resource, blockEntityType != null, withinCandidate, false, true,
                    SurveyConfidence.OBSERVED, evidence);
        }
        if (POWER_SOURCES.contains(path)) {
            return new PowerFinding(location(context, chunk, position, List.of(
                    "LIVE_RPM_UNKNOWN", "LIVE_STRESS_UNKNOWN")), resource,
                    SurveyFindingCategory.POWER_SOURCE, SurveyConfidence.OBSERVED, false, false, evidence);
        }
        if (TRANSMISSION.contains(path)) {
            return new PowerFinding(location(context, chunk, position, List.of(
                    "LIVE_RPM_UNKNOWN", "LIVE_STRESS_UNKNOWN")), resource,
                    SurveyFindingCategory.TRANSMISSION, SurveyConfidence.OBSERVED, false, false, evidence);
        }
        if (ITEM_LOGISTICS.contains(path)) {
            return new LogisticsFinding(location(context, chunk, position, List.of("RUNTIME_CONNECTIVITY_UNKNOWN")),
                    resource, GenericResourceType.ITEM, SurveyConfidence.OBSERVED, false, evidence);
        }
        if (FLUID_LOGISTICS.contains(path)) {
            return new LogisticsFinding(location(context, chunk, position, List.of("RUNTIME_CONNECTIVITY_UNKNOWN")),
                    resource, GenericResourceType.FLUID, SurveyConfidence.OBSERVED, false, evidence);
        }
        SurveyFindingCategory category = PROCESSING.contains(path)
                ? SurveyFindingCategory.PROCESSING
                : MOVING.contains(path) ? SurveyFindingCategory.MOVING_STRUCTURE
                : SurveyFindingCategory.OTHER_INFRASTRUCTURE;
        if (create) {
            return new CreateMachineFinding(location(context, chunk, position,
                    category == SurveyFindingCategory.OTHER_INFRASTRUCTURE
                            ? List.of("CREATE_BLOCK_OBSERVED_CATEGORY_GENERIC") : List.of("OFFLINE_OBSERVATION_ONLY")),
                    resource, category, SurveyConfidence.OBSERVED, evidence);
        }
        if (blockEntityType != null && storageHint(blockEntityType.path())) {
            boolean withinCandidate = context.candidateBounds().stream().anyMatch(bounds -> bounds.contains(position));
            return new StorageFinding(location(context, chunk, position, List.of(
                    "CONTENTS_NOT_READ", "STORAGE_CAPABILITY_REQUIRES_RUNTIME_CONFIRMATION",
                    "FUTURE_CONTENT_AUTHORIZATION_REQUIRED")),
                    resource, true, withinCandidate, false, true,
                    SurveyConfidence.DERIVED_LOW_CONFIDENCE, evidence);
        }
        return null;
    }

    private static List<SurveyEvidence> evidence(
            InfrastructureClassificationContext context,
            OfflineBlockState state,
            ResourceId blockEntityType) {
        List<SurveyEvidence> evidence = new ArrayList<>();
        evidence.add(new SurveyEvidence(SurveyEvidenceSource.BLOCK_PALETTE,
                context.relativeSourcePath(), context.fileFingerprint(), context.parseGeneration(),
                SurveyConfidence.OBSERVED, "resource=" + state.resourceId(), true));
        if (!state.properties().isEmpty()) {
            String properties = state.properties().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + "," + right).orElseThrow();
            evidence.add(new SurveyEvidence(SurveyEvidenceSource.ORIENTATION_PROPERTY,
                    context.relativeSourcePath(), context.fileFingerprint(), context.parseGeneration(),
                    SurveyConfidence.OBSERVED, properties, true));
        }
        if (blockEntityType != null) {
            evidence.add(new SurveyEvidence(SurveyEvidenceSource.BLOCK_ENTITY_TYPE,
                    context.relativeSourcePath(), context.fileFingerprint(), context.parseGeneration(),
                    SurveyConfidence.OBSERVED, "blockEntityType=" + blockEntityType, true));
        }
        return List.copyOf(evidence);
    }

    private static SurveyLocation location(
            InfrastructureClassificationContext context,
            OfflineChunkSnapshot chunk,
            BlockPos3i position,
            List<String> limitations) {
        return new SurveyLocation(context.worldIdentity(), context.dimension(), context.regionX(), context.regionZ(),
                chunk.chunkX(), chunk.chunkZ(), position, context.fileFingerprint(), context.parseGeneration(),
                SurveyEvidenceSource.PERSISTED_BLOCK_STATE, limitations);
    }

    private static boolean storageHint(String path) {
        return STORAGE_HINTS.stream().anyMatch(path::contains);
    }
}
