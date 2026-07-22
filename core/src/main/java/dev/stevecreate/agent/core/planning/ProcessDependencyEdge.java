package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.Objects;

/** A quantity of an intermediate resource flowing from one recipe step to another. */
public record ProcessDependencyEdge(
        ResourceId producerStepId,
        ResourceId consumerStepId,
        ProcessResource resource) {
    public ProcessDependencyEdge {
        Objects.requireNonNull(producerStepId, "producerStepId");
        Objects.requireNonNull(consumerStepId, "consumerStepId");
        Objects.requireNonNull(resource, "resource");
        if (producerStepId.equals(consumerStepId)) {
            throw new IllegalArgumentException("A dependency edge cannot be self-referential");
        }
    }
}
