package dev.stevecreate.agent.core.player;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;

/** Structured client intent. The server must resolve it against its current catalog. */
public record ProductionIntent(
        ResourceId target,
        long quantity,
        ProductionMode productionMode,
        PlayerExecutionMode executionMode) {
    public ProductionIntent {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(productionMode, "productionMode");
        Objects.requireNonNull(executionMode, "executionMode");
        if (quantity < 1 || quantity > GoalCatalogEntry.MAX_STACK_BUDGET) {
            throw new IllegalArgumentException("quantity must be between 1 and 64");
        }
    }
}
