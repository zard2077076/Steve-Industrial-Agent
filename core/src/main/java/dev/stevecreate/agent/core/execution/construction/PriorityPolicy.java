package dev.stevecreate.agent.core.execution.construction;

import java.util.Comparator;

/** Stable task ordering inside the already-verified dependency frontier. */
public enum PriorityPolicy {
    VERIFICATION_FIRST,
    MATERIAL_FIRST,
    TOPOLOGICAL_THEN_ID;

    public Comparator<ConstructionTask> comparator() {
        return Comparator.comparingInt(this::priority)
                .thenComparing(value -> value.taskId().toString());
    }

    private int priority(ConstructionTask task) {
        if (this == TOPOLOGICAL_THEN_ID) return 0;
        boolean verification = task.kind() == TaskKind.VERIFY_STATE
                || task.kind() == TaskKind.VERIFY_OUTPUT;
        boolean material = task.kind().operatesOnMaterial();
        if (this == VERIFICATION_FIRST) return verification ? 0 : material ? 1 : 2;
        return material ? 0 : verification ? 1 : 2;
    }
}
