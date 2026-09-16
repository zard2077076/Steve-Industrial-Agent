package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete loader-neutral persistence payload for every player-project material row. */
public record ProjectMaterialLedgerSnapshot(
        Map<ResourceId, MaterialRequirementPlan> plans,
        Map<ResourceId, MaterialSourceBinding> sources,
        Map<ResourceId, MaterialAllocation> allocations,
        Map<ResourceId, MaterialTransaction> transactions,
        Set<ResourceId> releasedAllocations,
        long generation,
        long savedTick,
        List<String> evidence) {
    public ProjectMaterialLedgerSnapshot {
        plans = Map.copyOf(Objects.requireNonNull(plans, "plans"));
        sources = Map.copyOf(Objects.requireNonNull(sources, "sources"));
        allocations = Map.copyOf(Objects.requireNonNull(allocations, "allocations"));
        transactions = Map.copyOf(Objects.requireNonNull(transactions, "transactions"));
        releasedAllocations = Set.copyOf(Objects.requireNonNull(releasedAllocations, "releasedAllocations"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (plans.size() > 128 || sources.size() > 1_024 || allocations.size() > 4_096
                || transactions.size() > 4_096 || evidence.size() > 4_096
                || generation < 0 || savedTick < 0) {
            throw new IllegalArgumentException("material ledger snapshot exceeds its bounds");
        }
    }
}
