package dev.stevecreate.agent.core.deployment;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Bounded read-only resource and capacity observations for PW-06. */
public record DeploymentBudgetContext(
        Map<ResourceId, Long> intermediateProductRequirements,
        Map<ResourceId, Long> powerComponentRequirements,
        Map<ResourceId, Long> logisticsComponentRequirements,
        long availablePowerCapacity,
        long maximumConstructionTicks,
        long rollbackExtraSpaceBytes,
        long journalBytesPerAffectedBlock,
        ResourceSourcePolicy resourceSourcePolicy,
        boolean resourceEvidenceReadOnly) {
    private static final Comparator<ResourceId> ID_ORDER = Comparator.comparing(ResourceId::toString);

    public DeploymentBudgetContext {
        intermediateProductRequirements = quantities(
                intermediateProductRequirements, "intermediateProductRequirements");
        powerComponentRequirements = quantities(
                powerComponentRequirements, "powerComponentRequirements");
        logisticsComponentRequirements = quantities(
                logisticsComponentRequirements, "logisticsComponentRequirements");
        if (availablePowerCapacity < 0 || maximumConstructionTicks < 1
                || maximumConstructionTicks > 100_000_000L || rollbackExtraSpaceBytes < 0
                || journalBytesPerAffectedBlock < 1 || journalBytesPerAffectedBlock > 1_048_576L) {
            throw new IllegalArgumentException("deployment budget context value is outside its bound");
        }
        Objects.requireNonNull(resourceSourcePolicy, "resourceSourcePolicy");
    }

    private static Map<ResourceId, Long> quantities(Map<ResourceId, Long> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > 4_096) throw new IllegalArgumentException(name + " exceeds its bound");
        TreeMap<ResourceId, Long> sorted = new TreeMap<>(ID_ORDER);
        values.forEach((resource, quantity) -> {
            Objects.requireNonNull(resource, name + " resource");
            if (quantity == null || quantity < 1 || quantity > 1_000_000_000_000L) {
                throw new IllegalArgumentException(name + " quantity is outside its bound");
            }
            sorted.put(resource, quantity);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
