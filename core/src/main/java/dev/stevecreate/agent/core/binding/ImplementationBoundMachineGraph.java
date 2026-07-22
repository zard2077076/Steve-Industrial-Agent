package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.VerifiedLogicalPlan;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable implementation-bound logical graph; it carries no layout or execution authority. */
public final class ImplementationBoundMachineGraph {
    private final ResourceId id;
    private final VerifiedLogicalPlan logicalPlan;
    private final Map<ResourceId, BoundMachineNode> boundProcessNodes;
    private final String runtimeFingerprint;
    private final long catalogReloadGeneration;
    private final List<String> bindingTrace;

    public ImplementationBoundMachineGraph(
            ResourceId id,
            VerifiedLogicalPlan logicalPlan,
            List<BoundMachineNode> boundProcessNodes,
            String runtimeFingerprint,
            long catalogReloadGeneration,
            List<String> bindingTrace) {
        this.id = Objects.requireNonNull(id, "id");
        this.logicalPlan = Objects.requireNonNull(logicalPlan, "logicalPlan");
        Objects.requireNonNull(boundProcessNodes, "boundProcessNodes");
        TreeMap<ResourceId, BoundMachineNode> indexed = new TreeMap<>(Comparator.comparing(
                ResourceId::toString));
        for (BoundMachineNode node : boundProcessNodes) {
            BoundMachineNode value = Objects.requireNonNull(node, "boundProcessNodes element");
            if (indexed.putIfAbsent(value.logicalNodeId(), value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate implementation binding for node " + value.logicalNodeId());
            }
        }
        this.boundProcessNodes = Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
        this.runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint");
        if (catalogReloadGeneration < 0) {
            throw new IllegalArgumentException("catalogReloadGeneration cannot be negative");
        }
        this.catalogReloadGeneration = catalogReloadGeneration;
        Objects.requireNonNull(bindingTrace, "bindingTrace");
        this.bindingTrace = bindingTrace.stream()
                .map(value -> requireText(value, "bindingTrace element"))
                .toList();
        if (this.bindingTrace.isEmpty()) {
            throw new IllegalArgumentException("bindingTrace cannot be empty");
        }
    }

    public ResourceId id() {
        return id;
    }

    public VerifiedLogicalPlan logicalPlan() {
        return logicalPlan;
    }

    public Map<ResourceId, BoundMachineNode> boundProcessNodes() {
        return boundProcessNodes;
    }

    public String runtimeFingerprint() {
        return runtimeFingerprint;
    }

    public long catalogReloadGeneration() {
        return catalogReloadGeneration;
    }

    public List<String> bindingTrace() {
        return bindingTrace;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
