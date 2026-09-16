package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Loader-neutral, resource-typed settlement evidence for one player order.
 *
 * <p>Every material row is a quantity in the exact identity named by its
 * {@link ResourceId}. NBT/components remain part of the material plan and are
 * therefore not silently collapsed into tags here.</p>
 */
public record IndustrialCompletionReportV1(
        Map<ResourceId, Long> plannedMaterials,
        Map<ResourceId, Long> withdrawnMaterials,
        Map<ResourceId, Long> consumedMaterials,
        Map<ResourceId, Long> returnedMaterials,
        Map<ResourceId, Long> energyConsumed,
        Map<ResourceId, Long> fluidConsumed,
        Map<ResourceId, Long> outputs,
        long salvageTransferred,
        long duplicateWithdrawals,
        long duplicateReturns,
        long duplicateEnergySettlements,
        long duplicateOutputs,
        long unaccountedItems,
        long privateItemsTouched,
        boolean materialLedgerBalanced,
        boolean baselineRestored,
        String baselineHash) {
    public static final int MAX_RESOURCE_ROWS = 4_096;

    public IndustrialCompletionReportV1 {
        plannedMaterials = amounts(plannedMaterials, "plannedMaterials");
        withdrawnMaterials = amounts(withdrawnMaterials, "withdrawnMaterials");
        consumedMaterials = amounts(consumedMaterials, "consumedMaterials");
        returnedMaterials = amounts(returnedMaterials, "returnedMaterials");
        energyConsumed = amounts(energyConsumed, "energyConsumed");
        fluidConsumed = amounts(fluidConsumed, "fluidConsumed");
        outputs = amounts(outputs, "outputs");
        if (salvageTransferred < 0 || duplicateWithdrawals < 0 || duplicateReturns < 0
                || duplicateEnergySettlements < 0 || duplicateOutputs < 0
                || unaccountedItems < 0 || privateItemsTouched < 0) {
            throw new IllegalArgumentException("industrial completion counters cannot be negative");
        }
        Objects.requireNonNull(baselineHash, "baselineHash");
        if (!baselineHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("industrial baseline hash must be SHA-256");
        }
        boolean exact = balancedAmounts(plannedMaterials, withdrawnMaterials,
                consumedMaterials, returnedMaterials)
                && duplicateWithdrawals == 0 && duplicateReturns == 0
                && duplicateEnergySettlements == 0 && duplicateOutputs == 0
                && unaccountedItems == 0 && privateItemsTouched == 0;
        if (materialLedgerBalanced != exact) {
            throw new IllegalArgumentException("material-ledger balance claim is inconsistent");
        }
    }

    /** Constructs a report while deriving its deterministic evidence identity on demand. */
    public static IndustrialCompletionReportV1 create(
            Map<ResourceId, Long> plannedMaterials,
            Map<ResourceId, Long> withdrawnMaterials,
            Map<ResourceId, Long> consumedMaterials,
            Map<ResourceId, Long> returnedMaterials,
            Map<ResourceId, Long> energyConsumed,
            Map<ResourceId, Long> fluidConsumed,
            Map<ResourceId, Long> outputs,
            long salvageTransferred,
            long duplicateWithdrawals,
            long duplicateReturns,
            long duplicateEnergySettlements,
            long duplicateOutputs,
            long unaccountedItems,
            long privateItemsTouched,
            boolean baselineRestored,
            String baselineHash) {
        boolean balanced = balancedAmounts(plannedMaterials, withdrawnMaterials,
                consumedMaterials, returnedMaterials)
                && duplicateWithdrawals == 0 && duplicateReturns == 0
                && duplicateEnergySettlements == 0 && duplicateOutputs == 0
                && unaccountedItems == 0 && privateItemsTouched == 0;
        return new IndustrialCompletionReportV1(plannedMaterials, withdrawnMaterials,
                consumedMaterials, returnedMaterials, energyConsumed, fluidConsumed, outputs,
                salvageTransferred, duplicateWithdrawals, duplicateReturns,
                duplicateEnergySettlements, duplicateOutputs, unaccountedItems,
                privateItemsTouched, balanced, baselineRestored, baselineHash);
    }

    public boolean accepted(
            ResourceId expectedEnergyResource,
            long expectedEnergy,
            ResourceId expectedOutputResource,
            long expectedOutput) {
        Objects.requireNonNull(expectedEnergyResource, "expectedEnergyResource");
        Objects.requireNonNull(expectedOutputResource, "expectedOutputResource");
        if (expectedEnergy < 0 || expectedOutput < 1) return false;
        return materialLedgerBalanced && baselineRestored
                && energyConsumed.getOrDefault(expectedEnergyResource, 0L) == expectedEnergy
                && energyConsumed.size() == 1
                && energyConsumed.values().stream().mapToLong(Long::longValue).sum()
                        == expectedEnergy
                && outputs.size() == 1
                && outputs.getOrDefault(expectedOutputResource, 0L) == expectedOutput
                && outputs.values().stream().mapToLong(Long::longValue).sum() == expectedOutput
                && duplicateWithdrawals == 0 && duplicateReturns == 0
                && duplicateEnergySettlements == 0 && duplicateOutputs == 0
                && unaccountedItems == 0 && privateItemsTouched == 0;
    }

    /**
     * Accepts an exact resource-shaped settlement without assuming that every machine
     * consumes Forge Energy. Item-fuel machines therefore pass an empty energy map and
     * account their fuel in the ordinary material rows.
     */
    public boolean accepted(
            Map<ResourceId, Long> expectedEnergy,
            Map<ResourceId, Long> expectedOutputs) {
        Objects.requireNonNull(expectedEnergy, "expectedEnergy");
        Objects.requireNonNull(expectedOutputs, "expectedOutputs");
        return materialLedgerBalanced && baselineRestored
                && energyConsumed.equals(amounts(expectedEnergy, "expectedEnergy"))
                && outputs.equals(amounts(expectedOutputs, "expectedOutputs"))
                && !outputs.isEmpty()
                && duplicateWithdrawals == 0 && duplicateReturns == 0
                && duplicateEnergySettlements == 0 && duplicateOutputs == 0
                && unaccountedItems == 0 && privateItemsTouched == 0;
    }

    /** Stable hash for reports stored alongside a journal/checkpoint. */
    public String evidenceHash() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "planned", plannedMaterials);
        append(canonical, "withdrawn", withdrawnMaterials);
        append(canonical, "consumed", consumedMaterials);
        append(canonical, "returned", returnedMaterials);
        append(canonical, "energy", energyConsumed);
        append(canonical, "fluid", fluidConsumed);
        append(canonical, "outputs", outputs);
        canonical.append("|salvage=").append(salvageTransferred)
                .append("|dw=").append(duplicateWithdrawals)
                .append("|dr=").append(duplicateReturns)
                .append("|de=").append(duplicateEnergySettlements)
                .append("|do=").append(duplicateOutputs)
                .append("|unaccounted=").append(unaccountedItems)
                .append("|private=").append(privateItemsTouched)
                .append("|balanced=").append(materialLedgerBalanced)
                .append("|restored=").append(baselineRestored)
                .append("|baseline=").append(baselineHash);
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Map<ResourceId, Long> amounts(Map<ResourceId, Long> source, String name) {
        Objects.requireNonNull(source, name);
        if (source.size() > MAX_RESOURCE_ROWS) {
            throw new IllegalArgumentException(name + " exceeds its bounded row count");
        }
        LinkedHashMap<ResourceId, Long> ordered = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing(ResourceId::toString))).forEach(entry -> {
            Objects.requireNonNull(entry.getKey(), name + " resource");
            Long quantity = Objects.requireNonNull(entry.getValue(), name + " quantity");
            if (quantity < 0) throw new IllegalArgumentException(name + " quantity is negative");
            if (quantity > 1_000_000_000_000L) {
                throw new IllegalArgumentException(name + " quantity exceeds its bound");
            }
            if (quantity > 0) ordered.put(entry.getKey(), quantity);
        });
        return Collections.unmodifiableMap(ordered);
    }

    private static boolean balancedAmounts(
            Map<ResourceId, Long> planned,
            Map<ResourceId, Long> withdrawn,
            Map<ResourceId, Long> consumed,
            Map<ResourceId, Long> returned) {
        TreeSet<ResourceId> resources = new TreeSet<>(Comparator.comparing(ResourceId::toString));
        resources.addAll(planned.keySet());
        resources.addAll(withdrawn.keySet());
        resources.addAll(consumed.keySet());
        resources.addAll(returned.keySet());
        return resources.stream().allMatch(resource ->
                planned.getOrDefault(resource, 0L)
                        == withdrawn.getOrDefault(resource, 0L)
                && withdrawn.getOrDefault(resource, 0L)
                        == consumed.getOrDefault(resource, 0L)
                        + returned.getOrDefault(resource, 0L));
    }

    private static void append(StringBuilder builder, String label, Map<ResourceId, Long> values) {
        builder.append('|').append(label).append('=');
        values.forEach((resource, quantity) -> builder.append(resource).append(':')
                .append(quantity).append(','));
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
