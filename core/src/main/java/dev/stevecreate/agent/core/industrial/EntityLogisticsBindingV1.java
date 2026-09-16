package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Exact loader-neutral identity of the already-authorized Bot logistics runtime. */
public record EntityLogisticsBindingV1(
        ResourceId sessionId,
        ResourceId taskGraphId,
        String taskGraphFingerprint,
        List<ResourceId> workerIds,
        List<ResourceId> assignmentIds,
        long generation,
        long observedTick) {
    public EntityLogisticsBindingV1 {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(taskGraphId, "taskGraphId");
        if (taskGraphFingerprint == null || !taskGraphFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("entity-logistics graph fingerprint is invalid");
        }
        workerIds = boundedIds(workerIds, "workerIds", 2, 5);
        assignmentIds = boundedIds(assignmentIds, "assignmentIds", 1, 4_096);
        if (generation < 0 || observedTick < 0) {
            throw new IllegalArgumentException("entity-logistics generation/tick is invalid");
        }
    }

    public String fingerprint() {
        String canonical = sessionId + "|" + taskGraphId + "|" + taskGraphFingerprint
                + "|workers=" + workerIds + "|assignments=" + assignmentIds
                + "|generation=" + generation;
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static List<ResourceId> boundedIds(
            List<ResourceId> source, String name, int minimum, int maximum) {
        Objects.requireNonNull(source, name);
        List<ResourceId> ordered = source.stream().map(value -> Objects.requireNonNull(value, name))
                .distinct().sorted(Comparator.comparing(ResourceId::toString)).toList();
        if (ordered.size() != source.size() || ordered.size() < minimum || ordered.size() > maximum) {
            throw new IllegalArgumentException(name + " is duplicate, empty or unbounded");
        }
        return ordered;
    }
}
