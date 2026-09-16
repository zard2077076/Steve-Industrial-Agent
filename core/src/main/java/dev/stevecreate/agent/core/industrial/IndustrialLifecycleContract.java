package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Startup, runtime observation, dismantle and recovery semantics for one implementation. */
public record IndustrialLifecycleContract(
        Set<ResourceId> startupConditions,
        Set<ResourceId> runtimeStatusSignals,
        List<IndustrialActionContract> actions,
        boolean dismantleSupported,
        boolean reloadRecoverySupported,
        String recoveryContract) {
    public IndustrialLifecycleContract {
        startupConditions = copyIds(startupConditions, "startupConditions", true);
        runtimeStatusSignals = copyIds(runtimeStatusSignals, "runtimeStatusSignals", true);
        Objects.requireNonNull(actions, "actions");
        if (actions.isEmpty() || actions.size() > 64) {
            throw new IllegalArgumentException("industrial lifecycle actions are empty or unbounded");
        }
        actions = actions.stream().sorted(Comparator.comparing(value -> value.actionId().toString()))
                .toList();
        if (actions.stream().map(IndustrialActionContract::actionId).distinct().count()
                != actions.size()) {
            throw new IllegalArgumentException("duplicate industrial lifecycle action");
        }
        if (dismantleSupported && actions.stream().noneMatch(value ->
                value.actionType() == IndustrialActionType.DISMANTLE)) {
            throw new IllegalArgumentException("dismantle support requires an exact action");
        }
        Objects.requireNonNull(recoveryContract, "recoveryContract");
        if (recoveryContract.isBlank() || recoveryContract.length() > 2_048) {
            throw new IllegalArgumentException("recovery contract is blank or unbounded");
        }
    }

    private static Set<ResourceId> copyIds(Set<ResourceId> values, String name, boolean required) {
        Objects.requireNonNull(values, name);
        if ((required && values.isEmpty()) || values.size() > 64) {
            throw new IllegalArgumentException(name + " is empty or unbounded");
        }
        return Set.copyOf(values);
    }
}
