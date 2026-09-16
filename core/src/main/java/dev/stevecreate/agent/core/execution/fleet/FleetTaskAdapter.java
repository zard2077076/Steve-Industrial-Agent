package dev.stevecreate.agent.core.execution.fleet;

import dev.stevecreate.agent.core.execution.construction.BotWorkerCapability;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Typed, graph-neutral scheduling view. Implementations expose only tasks that their own domain
 * has already admitted; this interface never converts one domain graph into another.
 */
public interface FleetTaskAdapter<G, T> {
    ResourceId graphId(G graph);

    String graphFingerprint(G graph);

    Map<ResourceId, T> tasks(G graph);

    ResourceId taskId(T task);

    Set<ResourceId> predecessorTaskIds(G graph, T task);

    Set<ResourceId> requiredWorkLeaseIds(G graph, T task);

    Set<BotWorkerCapability> requiredCapabilities(G graph, T task);

    int priority(G graph, T task);

    int maximumAttempts(G graph, T task);

    /** Whether this exact typed failure may enter the bounded reconciliation/reassignment path. */
    boolean allowsReassignment(G graph, T task, ResourceId failureCode);

    Set<ResourceId> requiredReconciliationEvidenceIds(G graph, T task);

    /**
     * Exact predecessor whose worker must retain a domain-owned carried reservation for this task.
     * Empty means that normal health/capability/load ordering applies.
     */
    default Optional<ResourceId> workerContinuityPredecessorId(G graph, T task) {
        return Optional.empty();
    }
}
