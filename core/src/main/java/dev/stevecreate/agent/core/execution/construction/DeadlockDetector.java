package dev.stevecreate.agent.core.execution.construction;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded wait-for cycle detector for the 2-5 worker fleet. */
public final class DeadlockDetector {
    private DeadlockDetector() {}

    public static Optional<Deadlock> detect(Map<ResourceId, ResourceId> waitsForWorker) {
        Objects.requireNonNull(waitsForWorker, "waitsForWorker");
        if (waitsForWorker.size() > BotFleetCoordinator.MAX_WORKERS) {
            throw new IllegalArgumentException("wait-for graph exceeds the fleet bound");
        }
        List<ResourceId> starts = waitsForWorker.keySet().stream()
                .sorted(Comparator.comparing(ResourceId::toString))
                .toList();
        for (ResourceId start : starts) {
            Map<ResourceId, Integer> index = new LinkedHashMap<>();
            List<ResourceId> path = new ArrayList<>();
            ResourceId current = start;
            while (current != null && waitsForWorker.containsKey(current)) {
                Integer prior = index.putIfAbsent(current, path.size());
                if (prior != null) {
                    List<ResourceId> cycle = new ArrayList<>(path.subList(prior, path.size()));
                    cycle.add(current);
                    return Optional.of(new Deadlock(cycle));
                }
                path.add(current);
                current = waitsForWorker.get(current);
            }
        }
        return Optional.empty();
    }

    public record Deadlock(List<ResourceId> workerCycle) {
        public Deadlock {
            workerCycle = List.copyOf(Objects.requireNonNull(workerCycle, "workerCycle"));
            if (workerCycle.size() < 3
                    || !workerCycle.get(0).equals(workerCycle.get(workerCycle.size() - 1))
                    || new LinkedHashSet<>(workerCycle).size() != workerCycle.size() - 1) {
                throw new IllegalArgumentException("Deadlock must contain one exact closed worker cycle");
            }
        }

        public Set<ResourceId> workers() {
            return Set.copyOf(workerCycle.subList(0, workerCycle.size() - 1));
        }
    }
}
