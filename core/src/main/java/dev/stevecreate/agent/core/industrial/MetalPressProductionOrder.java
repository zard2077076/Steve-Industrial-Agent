package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Loader-neutral durable authority for one exact IE Metal Press iron-plate order. */
public record MetalPressProductionOrder(
        UUID orderId,
        UUID projectId,
        UUID playerId,
        ResourceId dimension,
        BlockPos3i machineOrigin,
        BlockPos3i materialSource,
        ResourceId recipeId,
        String planHash,
        String runtimeFingerprint,
        String baselineHash,
        long expectedEnergyFe,
        MetalPressOrderStage stage,
        Set<MetalPressDurableEffect> durableEffects,
        long energyAtInputFe,
        long measuredEnergyConsumedFe,
        long exactOutputCount,
        String statusCode,
        long createdAt,
        long updatedAt,
        long generation,
        Optional<MetalPressCompletionReport> report) {
    public MetalPressProductionOrder {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(machineOrigin, "machineOrigin");
        Objects.requireNonNull(materialSource, "materialSource");
        Objects.requireNonNull(recipeId, "recipeId");
        requireHash(planHash, "planHash");
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank() || runtimeFingerprint.length() > 1_024) {
            throw new IllegalArgumentException("runtime fingerprint is blank or unbounded");
        }
        requireHash(baselineHash, "baselineHash");
        if (expectedEnergyFe != 2_400) {
            throw new IllegalArgumentException("this order contract requires exactly 2400 FE");
        }
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(durableEffects, "durableEffects");
        durableEffects = durableEffects.isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(durableEffects));
        if (energyAtInputFe < 0 || energyAtInputFe > 100_000
                || measuredEnergyConsumedFe < 0 || measuredEnergyConsumedFe > expectedEnergyFe
                || exactOutputCount < 0 || exactOutputCount > 1) {
            throw new IllegalArgumentException("production evidence is out of bounds");
        }
        Objects.requireNonNull(statusCode, "statusCode");
        if (statusCode.isBlank() || statusCode.length() > 1_024
                || createdAt < 0 || updatedAt < createdAt || generation < 0) {
            throw new IllegalArgumentException("order timing or status is invalid");
        }
        report = Objects.requireNonNull(report, "report");
        if (durableEffects.contains(MetalPressDurableEffect.ENERGY_SETTLEMENT)
                != (measuredEnergyConsumedFe == expectedEnergyFe)) {
            throw new IllegalArgumentException("energy effect and measured consumption diverge");
        }
        if (durableEffects.contains(MetalPressDurableEffect.OUTPUT_CLAIM)
                != (exactOutputCount == 1)) {
            throw new IllegalArgumentException("output effect and count diverge");
        }
        if (durableEffects.contains(MetalPressDurableEffect.COMPLETION_REPORT)
                != report.isPresent()) {
            throw new IllegalArgumentException("report effect and report payload diverge");
        }
        if (durableEffects.contains(MetalPressDurableEffect.INPUT_ADMISSION)
                != (energyAtInputFe >= expectedEnergyFe)) {
            throw new IllegalArgumentException("input effect and admission energy diverge");
        }
    }

    public static MetalPressProductionOrder create(
            UUID orderId, UUID projectId, UUID playerId, ResourceId dimension,
            BlockPos3i machineOrigin, BlockPos3i materialSource, ResourceId recipeId,
            String planHash, String runtimeFingerprint, String baselineHash, long now) {
        return new MetalPressProductionOrder(orderId, projectId, playerId, dimension,
                machineOrigin, materialSource, recipeId, planHash, runtimeFingerprint,
                baselineHash, 2_400, MetalPressOrderStage.MATERIAL_SOURCE_SELECTION,
                Set.of(), 0, 0, 0, "MATERIAL_SOURCE_SELECTION_REQUIRED", now, now, 0,
                Optional.empty());
    }

    /** Common player-order projection; the frozen Metal Press wire/state schema is unchanged. */
    public IndustrialPlayerOrderV1 toIndustrialPlayerOrderV1() {
        IndustrialLifecyclePhase genericPhase = switch (stage) {
            case MATERIAL_SOURCE_SELECTION, MATERIALS_RESERVED -> IndustrialLifecyclePhase.PLANNED;
            case MATERIALS_WITHDRAWN, MATERIALS_DELIVERED, STRUCTURE_BUILT -> IndustrialLifecyclePhase.PLACED;
            case MULTIBLOCK_FORMED, MOLD_INSTALLED, POWER_NETWORK_BUILT, POWER_VERIFIED,
                    INPUT_QUEUED -> IndustrialLifecyclePhase.READY;
            case PROCESSING, ENERGY_SETTLED, OUTPUT_OBSERVED -> IndustrialLifecyclePhase.RUNNING;
            case TEARDOWN, BASELINE_RESTORED, MATERIALS_RETURNED -> IndustrialLifecyclePhase.DISMANTLING;
            case REPORT_GENERATED, COMPLETED -> IndustrialLifecyclePhase.COMPLETED;
            case PAUSED -> IndustrialLifecyclePhase.PAUSED;
            case CANCELLED -> IndustrialLifecyclePhase.FAILED;
        };
        Set<ResourceId> effects = durableEffects.stream()
                .map(value -> ResourceId.parse("steve_industrial:effect_"
                        + value.name().toLowerCase(java.util.Locale.ROOT)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Optional<IndustrialCompletionReportV1> genericReport = report.map(
                MetalPressCompletionReport::toIndustrialReport);
        return new IndustrialPlayerOrderV1(orderId, projectId, playerId, dimension.toString(), dimension,
                ResourceId.parse("steve_industrial:metal_press"),
                ResourceId.parse("immersiveengineering:plate_iron"), recipeId, machineOrigin,
                planHash, runtimeFingerprint, baselineHash, genericPhase, stage.name(), effects,
                createdAt, updatedAt, generation, genericReport);
    }

    private static void requireHash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() != 64) throw new IllegalArgumentException(name + " must be SHA-256");
    }
}
