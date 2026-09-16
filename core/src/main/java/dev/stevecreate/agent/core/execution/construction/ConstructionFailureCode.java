package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Locale;

/** Shared failure vocabulary; adapters may add namespaced detail codes without changing categories. */
public enum ConstructionFailureCode {
    BOT_EXECUTION_CAPABILITY_UNSUPPORTED(TaskFailureCategory.UNSUPPORTED_CAPABILITY),
    EXECUTOR_MODE_UNSUPPORTED(TaskFailureCategory.UNSUPPORTED_CAPABILITY),
    TASK_DEPENDENCY_INCOMPLETE(TaskFailureCategory.INTERNAL_CONTRACT_VIOLATION),
    TASK_PRECONDITION_UNSATISFIED(TaskFailureCategory.VERIFICATION_FAILED),
    RESERVATION_CONFLICT(TaskFailureCategory.RESERVATION_CONFLICT),
    MATERIAL_INSUFFICIENT(TaskFailureCategory.MATERIAL_UNAVAILABLE),
    MATERIAL_SOURCE_FORBIDDEN(TaskFailureCategory.HIGH_RISK_INTERACTION),
    WORKER_UNAVAILABLE(TaskFailureCategory.WORKER_UNAVAILABLE),
    NAVIGATION_BLOCKED(TaskFailureCategory.NAVIGATION_BLOCKED),
    PATH_OUTSIDE_AUTHORIZED_REGION(TaskFailureCategory.HIGH_RISK_INTERACTION),
    CHUNK_NOT_LOADED(TaskFailureCategory.CHUNK_UNAVAILABLE),
    WRONG_THREAD(TaskFailureCategory.INTERNAL_CONTRACT_VIOLATION),
    WORLD_STATE_CHANGED(TaskFailureCategory.STATE_CHANGED),
    OWNERSHIP_LOST(TaskFailureCategory.OWNERSHIP_LOST),
    DEADLOCK_DETECTED(TaskFailureCategory.RECOVERY_REFUSED),
    RETRY_EXHAUSTED(TaskFailureCategory.RECOVERY_REFUSED),
    RECOVERY_RECONCILIATION_REQUIRED(TaskFailureCategory.RECOVERY_REFUSED),
    RECOVERY_REFUSED_AFTER_RESOURCE_CONSUMPTION(TaskFailureCategory.RECOVERY_REFUSED),
    HIGH_RISK_INTERACTION_REFUSED(TaskFailureCategory.HIGH_RISK_INTERACTION),
    CANCELLED(TaskFailureCategory.CANCELLED),
    VERIFICATION_FAILED(TaskFailureCategory.VERIFICATION_FAILED),
    CLEANUP_CONFLICT(TaskFailureCategory.STATE_CHANGED);

    private final TaskFailureCategory category;
    private final ResourceId id;

    ConstructionFailureCode(TaskFailureCategory category) {
        this.category = category;
        this.id = ResourceId.parse("construction:" + name().toLowerCase(Locale.ROOT));
    }

    public TaskFailureCategory category() {
        return category;
    }

    public ResourceId id() {
        return id;
    }
}
