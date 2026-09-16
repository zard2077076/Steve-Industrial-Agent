package dev.stevecreate.agent.core.siteprep;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded site-preparation DAG. It does not replace ConstructionTaskGraph. */
public record TerrainPreparationTaskGraph(
        String graphIdentity,
        String planHash,
        String siteSnapshotHash,
        String approvalTokenIdentity,
        List<TerrainPreparationTask> tasks,
        int maximumMutations) {
    public TerrainPreparationTaskGraph {
        graphIdentity = SitePreparationHashes.text(graphIdentity, "graphIdentity");
        planHash = SitePreparationHashes.hash(planHash, "planHash");
        siteSnapshotHash = SitePreparationHashes.hash(siteSnapshotHash, "siteSnapshotHash");
        approvalTokenIdentity = SitePreparationHashes.text(
                approvalTokenIdentity, "approvalTokenIdentity");
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        if (tasks.isEmpty() || tasks.size() > 64 || maximumMutations < 0
                || maximumMutations > 4_096) {
            throw new IllegalArgumentException("terrain graph is empty or unbounded");
        }
        validate(tasks);
    }

    private static void validate(List<TerrainPreparationTask> tasks) {
        Map<String, TerrainPreparationTask> indexed = new HashMap<>();
        for (TerrainPreparationTask task : tasks) {
            if (indexed.put(task.taskIdentity(), task) != null) {
                throw new IllegalArgumentException("duplicate terrain task");
            }
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        indexed.keySet().forEach(id -> {
            indegree.put(id, 0);
            successors.put(id, new ArrayList<>());
        });
        tasks.forEach(task -> task.predecessorTaskIdentities().forEach(predecessor -> {
            if (!indexed.containsKey(predecessor) || predecessor.equals(task.taskIdentity())) {
                throw new IllegalArgumentException("invalid terrain task predecessor");
            }
            indegree.compute(task.taskIdentity(), (key, value) -> value + 1);
            successors.get(predecessor).add(task.taskIdentity());
        }));
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, value) -> {
            if (value == 0) ready.add(id);
        });
        Set<String> visited = new HashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            visited.add(id);
            for (String successor : successors.get(id)) {
                if (indegree.compute(successor, (key, value) -> value - 1) == 0) {
                    ready.add(successor);
                }
            }
        }
        if (visited.size() != tasks.size()) {
            throw new IllegalArgumentException("terrain task graph contains a cycle");
        }
    }
}
