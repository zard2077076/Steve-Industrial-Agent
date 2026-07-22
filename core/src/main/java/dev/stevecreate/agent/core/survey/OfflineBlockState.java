package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Map;
import java.util.Objects;

public record OfflineBlockState(ResourceId resourceId, Map<String, String> properties) {
    public OfflineBlockState {
        Objects.requireNonNull(resourceId, "resourceId");
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        if (properties.size() > 128 || properties.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 256
                        || entry.getValue() == null || entry.getValue().isBlank() || entry.getValue().length() > 1_024)) {
            throw new IllegalArgumentException("block-state properties are invalid");
        }
    }
}
