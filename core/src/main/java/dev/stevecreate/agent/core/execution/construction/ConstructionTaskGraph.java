package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/** Immutable bounded DAG derived from exactly one VerifiedPhysicalPlan identity. */
public final class ConstructionTaskGraph {
    public static final int MAX_TASKS = ConstructionContractValues.MAX_IDS;
    public static final int MAX_DEPENDENCIES = 1_024;

    private final ResourceId graphId;
    private final ResourceId verifiedPhysicalPlanId;
    private final String runtimeFingerprint;
    private final String worldSnapshotFingerprint;
    private final Map<ResourceId, CapabilityExecutionDescriptor> descriptors;
    private final Map<ResourceId, ConstructionTask> tasks;
    private final List<TaskDependency> dependencies;
    private final List<ConstructionTask> topologicalOrder;

    public ConstructionTaskGraph(
            ResourceId graphId,
            ResourceId verifiedPhysicalPlanId,
            String runtimeFingerprint,
            String worldSnapshotFingerprint,
            List<CapabilityExecutionDescriptor> descriptors,
            List<ConstructionTask> tasks,
            List<TaskDependency> dependencies) {
        this.graphId = Objects.requireNonNull(graphId, "graphId");
        this.verifiedPhysicalPlanId = Objects.requireNonNull(
                verifiedPhysicalPlanId, "verifiedPhysicalPlanId");
        this.runtimeFingerprint = ConstructionContractValues.fingerprint(
                runtimeFingerprint, "runtimeFingerprint");
        this.worldSnapshotFingerprint = ConstructionContractValues.fingerprint(
                worldSnapshotFingerprint, "worldSnapshotFingerprint");
        this.descriptors = indexDescriptors(descriptors);
        this.tasks = indexTasks(tasks);
        this.dependencies = copyDependencies(dependencies);
        validateTasks();
        validateDependencies();
        this.topologicalOrder = Collections.unmodifiableList(computeTopologicalOrder());
    }

    public ResourceId graphId() {
        return graphId;
    }

    public ResourceId verifiedPhysicalPlanId() {
        return verifiedPhysicalPlanId;
    }

    public String runtimeFingerprint() {
        return runtimeFingerprint;
    }

    public String worldSnapshotFingerprint() {
        return worldSnapshotFingerprint;
    }

    public Map<ResourceId, CapabilityExecutionDescriptor> descriptors() {
        return descriptors;
    }

    public Map<ResourceId, ConstructionTask> tasks() {
        return tasks;
    }

    public List<TaskDependency> dependencies() {
        return dependencies;
    }

    public List<ConstructionTask> topologicalOrder() {
        return topologicalOrder;
    }

    public ConstructionTask task(ResourceId taskId) {
        ConstructionTask task = tasks.get(Objects.requireNonNull(taskId, "taskId"));
        if (task == null) {
            throw new IllegalArgumentException("Unknown construction task " + taskId);
        }
        return task;
    }

    public CapabilityExecutionDescriptor descriptor(ResourceId capabilityId) {
        CapabilityExecutionDescriptor descriptor = descriptors.get(
                Objects.requireNonNull(capabilityId, "capabilityId"));
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown execution capability " + capabilityId);
        }
        return descriptor;
    }

    public List<ConstructionTask> readyTasks(Map<ResourceId, TaskExecutionResult> completedResults) {
        Objects.requireNonNull(completedResults, "completedResults");
        if (completedResults.size() > tasks.size()) {
            throw new IllegalArgumentException("completedResults exceeds graph task count");
        }
        Set<ResourceId> completed = new LinkedHashSet<>();
        for (Map.Entry<ResourceId, TaskExecutionResult> entry : completedResults.entrySet()) {
            ResourceId taskId = Objects.requireNonNull(entry.getKey(), "completedResults key");
            TaskExecutionResult result = Objects.requireNonNull(
                    entry.getValue(), "completedResults value");
            if (!tasks.containsKey(taskId)
                    || !taskId.equals(result.taskId())
                    || !graphId.equals(result.graphId())
                    || result.outcome() != TaskExecutionOutcome.SUCCEEDED) {
                throw new IllegalArgumentException("Completed result is not a successful result for this graph/task "
                        + taskId);
            }
            completed.add(taskId);
        }
        Map<ResourceId, Set<ResourceId>> predecessors = predecessorMap();
        return topologicalOrder.stream()
                .filter(task -> !completed.contains(task.taskId()))
                .filter(task -> completed.containsAll(predecessors.get(task.taskId())))
                .toList();
    }

    private Map<ResourceId, CapabilityExecutionDescriptor> indexDescriptors(
            List<CapabilityExecutionDescriptor> values) {
        List<CapabilityExecutionDescriptor> copy = ConstructionContractValues.uniqueSorted(
                values, "descriptors", CapabilityExecutionDescriptor::capabilityId, true);
        Map<ResourceId, CapabilityExecutionDescriptor> indexed = new LinkedHashMap<>();
        copy.forEach(value -> indexed.put(value.capabilityId(), value));
        return Collections.unmodifiableMap(indexed);
    }

    private Map<ResourceId, ConstructionTask> indexTasks(List<ConstructionTask> values) {
        Objects.requireNonNull(values, "tasks");
        if (values.isEmpty() || values.size() > MAX_TASKS) {
            throw new IllegalArgumentException("tasks count must be between 1 and " + MAX_TASKS);
        }
        List<ConstructionTask> copy = ConstructionContractValues.uniqueSorted(
                values, "tasks", ConstructionTask::taskId, true);
        Map<ResourceId, ConstructionTask> indexed = new LinkedHashMap<>();
        copy.forEach(value -> indexed.put(value.taskId(), value));
        return Collections.unmodifiableMap(indexed);
    }

    private List<TaskDependency> copyDependencies(List<TaskDependency> values) {
        Objects.requireNonNull(values, "dependencies");
        if (values.size() > MAX_DEPENDENCIES) {
            throw new IllegalArgumentException("dependencies count exceeds " + MAX_DEPENDENCIES);
        }
        Set<String> keys = new HashSet<>();
        List<TaskDependency> copy = new ArrayList<>();
        for (TaskDependency dependency : values) {
            TaskDependency value = Objects.requireNonNull(dependency, "dependencies element");
            String key = value.predecessorTaskId() + "->" + value.successorTaskId()
                    + ":" + value.kind();
            if (!keys.add(key)) {
                throw new IllegalArgumentException("duplicate dependency " + key);
            }
            copy.add(value);
        }
        copy.sort(Comparator
                .comparing((TaskDependency value) -> value.predecessorTaskId().toString())
                .thenComparing(value -> value.successorTaskId().toString())
                .thenComparing(value -> value.kind().name()));
        return Collections.unmodifiableList(copy);
    }

    private void validateTasks() {
        for (ConstructionTask task : tasks.values()) {
            if (!task.source().verifiedPhysicalPlanId().equals(verifiedPhysicalPlanId)) {
                throw new IllegalArgumentException("Task " + task.taskId()
                        + " does not belong to verified plan " + verifiedPhysicalPlanId);
            }
            CapabilityExecutionDescriptor descriptor = descriptors.get(task.capabilityId());
            if (descriptor == null) {
                throw new IllegalArgumentException("Task " + task.taskId()
                        + " references unknown capability " + task.capabilityId());
            }
            if (!descriptor.runtimeFingerprint().equals(runtimeFingerprint)) {
                throw new IllegalArgumentException("Capability " + descriptor.capabilityId()
                        + " has a different runtime fingerprint");
            }
            for (ExecutionMode mode : task.allowedModes()) {
                if (!descriptor.supports(mode, task.kind())) {
                    throw new IllegalArgumentException("Capability " + descriptor.capabilityId()
                            + " does not support task " + task.taskId() + " in mode " + mode);
                }
            }
        }
    }

    private void validateDependencies() {
        for (TaskDependency dependency : dependencies) {
            ConstructionTask predecessor = tasks.get(dependency.predecessorTaskId());
            if (predecessor == null || !tasks.containsKey(dependency.successorTaskId())) {
                throw new IllegalArgumentException("Dependency references an unknown task: " + dependency);
            }
            if (!predecessor.postconditionIds().containsAll(dependency.requiredPostconditionIds())) {
                throw new IllegalArgumentException("Dependency requires evidence not produced by predecessor "
                        + predecessor.taskId());
            }
        }
    }

    private List<ConstructionTask> computeTopologicalOrder() {
        Map<ResourceId, Integer> indegree = new HashMap<>();
        Map<ResourceId, List<ResourceId>> successors = new HashMap<>();
        tasks.keySet().forEach(id -> {
            indegree.put(id, 0);
            successors.put(id, new ArrayList<>());
        });
        for (TaskDependency dependency : dependencies) {
            indegree.compute(dependency.successorTaskId(), (ignored, value) -> value + 1);
            successors.get(dependency.predecessorTaskId()).add(dependency.successorTaskId());
        }
        successors.values().forEach(values -> values.sort(Comparator.comparing(ResourceId::toString)));
        PriorityQueue<ResourceId> ready = new PriorityQueue<>(Comparator.comparing(ResourceId::toString));
        indegree.forEach((id, count) -> {
            if (count == 0) ready.add(id);
        });
        List<ConstructionTask> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            ResourceId id = ready.remove();
            ordered.add(tasks.get(id));
            for (ResourceId successor : successors.get(id)) {
                int remaining = indegree.compute(successor, (ignored, value) -> value - 1);
                if (remaining == 0) ready.add(successor);
            }
        }
        if (ordered.size() != tasks.size()) {
            throw new IllegalArgumentException("Construction task dependencies must form an acyclic graph");
        }
        return ordered;
    }

    private Map<ResourceId, Set<ResourceId>> predecessorMap() {
        Map<ResourceId, Set<ResourceId>> predecessors = new LinkedHashMap<>();
        tasks.keySet().forEach(id -> predecessors.put(id, new LinkedHashSet<>()));
        dependencies.forEach(value -> predecessors.get(value.successorTaskId())
                .add(value.predecessorTaskId()));
        return predecessors;
    }
}
