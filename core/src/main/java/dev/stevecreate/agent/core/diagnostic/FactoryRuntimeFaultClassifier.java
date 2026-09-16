package dev.stevecreate.agent.core.diagnostic;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Maps durable, typed runtime refusal codes to the six player-facing health categories.
 *
 * <p>This deliberately uses an explicit allowlist instead of keyword guessing. A status
 * such as {@code MATERIALS_RESERVED} contains a material word but is positive evidence,
 * while a new status the diagnostic layer does not understand must remain UNKNOWN.</p>
 */
public final class FactoryRuntimeFaultClassifier {
    private static final List<Rule> RULES = List.of(
            rule(FactoryHealthCategory.MATERIAL_SUPPLY,
                    "MATERIALS_INSUFFICIENT", "WAREHOUSE_MATERIALS_INSUFFICIENT",
                    "MATERIAL_SOURCE_UNAVAILABLE", "MATERIAL_STAGING_UNAVAILABLE",
                    "STAGING_MATERIAL_MISSING", "STAGING_MATERIAL_UNAVAILABLE",
                    "SOURCE_CONTAINER_MISSING", "SOURCE_CHEST_MISSING",
                    "NO_WAREHOUSE_SOURCE", "TERRAIN_FILL_MATERIAL_UNAVAILABLE"),
            rule(FactoryHealthCategory.OUTPUT_CAPACITY,
                    "DESTINATION_CAPACITY_INSUFFICIENT", "RETURN_PENDING",
                    "RETURN_PENDING_SOURCE_FULL_OR_CHANGED", "OUTPUT_DELIVERY_FAILED",
                    "COMPOSITE_OUTPUT_RETURN_PENDING", "FLUID_RETURN_PENDING",
                    "CANCELLED_RETURN_PENDING", "COMPOSITE_CANCELLED_RETURN_PENDING",
                    "SALVAGE_CAPACITY_OR_DESTINATION_CHANGED",
                    "SALVAGE_DESTINATION_UNAVAILABLE", "STAGING_CONTAINER_FULL_OR_MISSING"),
            rule(FactoryHealthCategory.LOGISTICS_ROUTE,
                    "CARRY_FAILED", "CARRY_HAS_NO_SOURCE", "CARRY_RESERVATION_REFUSED",
                    "CARRY_SOURCE_REFUSED", "MATERIAL_COURIER_FAILED",
                    "MATERIAL_COURIER_RECOVERY_DIVERGED", "MATERIAL_COURIER_START_REFUSED",
                    "MATERIAL_SOURCE_OUTSIDE_LOGISTICS_REGION", "MATERIAL_SOURCE_OUT_OF_RANGE",
                    "SITE_TOO_FAR_FROM_WAREHOUSE", "WAREHOUSE_SOURCE_OUT_OF_REACH",
                    "OUTPUT_DELIVERY_FAILED"),
            rule(FactoryHealthCategory.ENERGY_SUPPLY,
                    "ENERGY_CONSUMPTION_MISMATCH", "ENERGY_SETTLED_NOT_ACCOUNTED",
                    "MOLD_INSTALLED_NOT_POWERED", "POWER_CHUNK_NOT_LOADED",
                    "POWER_NOT_YET_VERIFIED", "POWER_ROUTE_MATERIAL_MAPPING_REQUIRED",
                    "POWER_SITE_BLOCKED", "WIRE_CONNECTION_FAILED",
                    "WIRE_NETWORK_EVIDENCE_MISMATCH", "THERMAL_GENERATION_STOP_FAILED",
                    "THERMAL_SOURCE_PLACEMENT_FAILED", "OVERSTRESSED"),
            rule(FactoryHealthCategory.STRUCTURE_INTEGRITY,
                    "BASELINE_RESTORE_FAILED", "BLOCK_PLACEMENT_BLOCKED",
                    "BLOCK_PLACEMENT_FAILED", "COMPOSITE_INFRASTRUCTURE_DIVERGED",
                    "COMPOSITE_LAYOUT_UNRECOVERABLE", "COMPOSITE_SITE_CELLS_OVERLAP",
                    "COMPOSITE_SITE_NOT_EMPTY", "CONNECTOR_PLACEMENT_FAILED",
                    "CONSTRUCTION_LOGISTICS_CHEST_FAILED", "CONSTRUCTION_LOGISTICS_SPACE_UNAVAILABLE",
                    "GENERATOR_PLACEMENT_FAILED", "MOLD_INSERTION_FAILED",
                    "MULTIBLOCK_CHANGED", "MULTIBLOCK_FORMATION_FAILED",
                    "MULTIBLOCK_MASTER_LOST", "MULTIBLOCK_MASTER_NOT_FOUND",
                    "ORDER_SITE_NOT_EMPTY", "POST_CLEARANCE_SITE_CHANGED",
                    "PREPARED_SITE_NOT_CURRENT", "PREPARED_SITE_REVALIDATION_FAILED",
                    "RESTORE_FAILED", "SECOND_MACHINE_FORMATION_FAILED",
                    "SITE_SELECTION_STALE", "SITE_SURVEY_STALE"),
            rule(FactoryHealthCategory.RESERVATION_INTEGRITY,
                    "COMPOSITE_MATERIAL_ENTRY_MISSING",
                    "COMPOSITE_MATERIAL_LEDGER_RECONCILIATION_REQUIRED",
                    "DELIVERY_DIVERGED_RETURNED", "MATERIAL_DELIVERY_RECONCILIATION_REQUIRED",
                    "MATERIAL_LEDGER_MISSING", "MATERIAL_LEDGER_RECONCILIATION_REQUIRED",
                    "MATERIAL_LEDGER_UNBALANCED", "MATERIAL_PLAN_CHANGED",
                    "MATERIAL_RECONCILIATION_REQUIRED", "MATERIAL_RESERVATION_EXPIRED",
                    "MATERIAL_RESERVATION_MISSING", "MATERIAL_SOURCE_CHANGED",
                    "RELOAD_RECONCILIATION_REQUIRED", "RESERVED_MATERIAL_GONE",
                    "RETURN_RECONCILIATION_REQUIRED", "SOURCE_SNAPSHOT_CHANGED",
                    "WAREHOUSE_RESERVATION_DIVERGED", "WITHDRAWAL_DIVERGED",
                    "WITHDRAWN_MATERIAL_RECOVERY_REQUIRED"));

    private FactoryRuntimeFaultClassifier() {}

    public static Set<FactoryHealthCategory> classify(String runtimeStatusCode) {
        Objects.requireNonNull(runtimeStatusCode, "runtimeStatusCode");
        String normalized = runtimeStatusCode.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return Set.of();
        EnumSet<FactoryHealthCategory> categories = EnumSet.noneOf(FactoryHealthCategory.class);
        for (Rule rule : RULES) {
            if (rule.prefixes().stream().anyMatch(prefix -> matches(normalized, prefix))) {
                categories.add(rule.category());
            }
        }
        return Set.copyOf(categories);
    }

    private static boolean matches(String status, String prefix) {
        return status.equals(prefix) || status.startsWith(prefix + ":");
    }

    private static Rule rule(FactoryHealthCategory category, String... prefixes) {
        return new Rule(category, List.of(prefixes));
    }

    private record Rule(FactoryHealthCategory category, List<String> prefixes) {}
}
