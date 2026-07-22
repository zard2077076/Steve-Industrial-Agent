package dev.stevecreate.agent.core.recovery;

import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Serializable graph identity and full-topology fingerprint used for trusted recovery. */
public record RecoveryGraphSnapshot(
        ResourceId graphId,
        List<ResourceId> nodeIds,
        List<ResourceId> portIds,
        List<ResourceId> edgeIds,
        String fingerprint) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public RecoveryGraphSnapshot {
        Objects.requireNonNull(graphId, "graphId");
        nodeIds = copyIds(nodeIds, "nodeIds", UnifiedMachineGraph.MAX_NODES, true);
        portIds = copyIds(portIds, "portIds", UnifiedMachineGraph.MAX_PORTS, false);
        edgeIds = copyIds(edgeIds, "edgeIds", UnifiedMachineGraph.MAX_EDGES, false);
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (!SHA_256.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("Graph fingerprint must be lowercase SHA-256 hex");
        }
    }

    public static RecoveryGraphSnapshot capture(UnifiedMachineGraph graph) {
        Objects.requireNonNull(graph, "graph");
        return new RecoveryGraphSnapshot(
                graph.id(),
                List.copyOf(graph.nodes().keySet()),
                List.copyOf(graph.ports().keySet()),
                List.copyOf(graph.edges().keySet()),
                RecoveryFingerprint.graph(graph));
    }

    private static List<ResourceId> copyIds(
            List<ResourceId> values,
            String name,
            int maximum,
            boolean requireNonEmpty) {
        Objects.requireNonNull(values, name);
        if ((requireNonEmpty && values.isEmpty()) || values.size() > maximum) {
            throw new IllegalArgumentException(name + " has an invalid count");
        }
        List<ResourceId> copy = new ArrayList<>(values.size());
        Set<ResourceId> unique = new HashSet<>();
        for (ResourceId value : values) {
            ResourceId id = Objects.requireNonNull(value, name + " element");
            if (!unique.add(id)) {
                throw new IllegalArgumentException(name + " contains duplicate id " + id);
            }
            copy.add(id);
        }
        copy.sort(Comparator.comparing(ResourceId::toString));
        return List.copyOf(copy);
    }
}
