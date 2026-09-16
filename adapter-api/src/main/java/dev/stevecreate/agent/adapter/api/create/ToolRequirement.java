package dev.stevecreate.agent.adapter.api.create;

import dev.stevecreate.agent.core.planning.RecipeIngredient;
import java.util.Objects;
import java.util.Optional;

/** Tool identity and consumption semantics, independent of any player inventory. */
public record ToolRequirement(
        Optional<RecipeIngredient> tool,
        Consumption consumption,
        boolean stateReadbackRequired) {
    public enum Consumption {
        NONE,
        CONSUMED,
        RETAINED
    }

    public ToolRequirement {
        tool = Objects.requireNonNull(tool, "tool");
        tool.ifPresent(value -> Objects.requireNonNull(value, "tool value"));
        Objects.requireNonNull(consumption, "consumption");
        if (tool.isEmpty() != (consumption == Consumption.NONE)) {
            throw new IllegalArgumentException("Tool presence must match its consumption policy");
        }
        if (tool.isPresent() && !stateReadbackRequired) {
            throw new IllegalArgumentException("A tool must be observed before and after processing");
        }
    }

    public static ToolRequirement none() {
        return new ToolRequirement(Optional.empty(), Consumption.NONE, false);
    }
}
