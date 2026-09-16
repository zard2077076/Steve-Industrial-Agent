package dev.stevecreate.agent.adapter.api.create;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ordered Create roll pool; individual outputs retain independent chance semantics. */
public record MultiOutputSemantics(
        List<CapabilityOutput> outputs,
        boolean independentRolls,
        boolean outputOrderPreserved) {
    public MultiOutputSemantics {
        outputs = CapabilityContracts.list(outputs, "outputs");
        if (!independentRolls || !outputOrderPreserved) {
            throw new IllegalArgumentException(
                    "Runtime output pools require ordered independent rolls");
        }
        Set<Integer> indices = new HashSet<>();
        int primaryCount = 0;
        int previousIndex = -1;
        for (CapabilityOutput output : outputs) {
            if (output.outputIndex() <= previousIndex || !indices.add(output.outputIndex())) {
                throw new IllegalArgumentException("Output indices must be increasing and unique");
            }
            previousIndex = output.outputIndex();
            if (output.primary()) {
                primaryCount++;
            }
        }
        if (!outputs.isEmpty() && (primaryCount != 1
                || !outputs.get(0).primary()
                || outputs.get(0).outputIndex() != 0)) {
            throw new IllegalArgumentException("Exactly output[0] must be the primary output");
        }
    }
}
