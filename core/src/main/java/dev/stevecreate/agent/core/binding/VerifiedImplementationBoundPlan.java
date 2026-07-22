package dev.stevecreate.agent.core.binding;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Non-executable binding result available only after the complete independent verifier passes. */
public final class VerifiedImplementationBoundPlan {
    private final ResourceId id;
    private final ImplementationBoundMachineGraph graph;
    private final Map<BindingVerificationCheck, BindingVerificationEvidence> evidence;

    VerifiedImplementationBoundPlan(
            ImplementationBoundMachineGraph graph,
            List<BindingVerificationEvidence> evidence) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.id = ResourceId.parse("binding:verified_" + graph.id().path());
        Objects.requireNonNull(evidence, "evidence");
        EnumMap<BindingVerificationCheck, BindingVerificationEvidence> indexed =
                new EnumMap<>(BindingVerificationCheck.class);
        for (BindingVerificationEvidence item : evidence) {
            BindingVerificationEvidence value = Objects.requireNonNull(item, "evidence element");
            if (indexed.putIfAbsent(value.check(), value) != null) {
                throw new IllegalArgumentException("Duplicate binding verification check " + value.check());
            }
        }
        if (indexed.size() != BindingVerificationCheck.values().length) {
            throw new IllegalArgumentException("A verified binding requires the complete checklist");
        }
        Map<BindingVerificationCheck, BindingVerificationEvidence> ordered = new LinkedHashMap<>();
        for (BindingVerificationCheck check : BindingVerificationCheck.values()) {
            ordered.put(check, indexed.get(check));
        }
        this.evidence = Collections.unmodifiableMap(ordered);
    }

    public ResourceId id() {
        return id;
    }

    public ImplementationBoundMachineGraph graph() {
        return graph;
    }

    public Map<BindingVerificationCheck, BindingVerificationEvidence> evidence() {
        return evidence;
    }
}
