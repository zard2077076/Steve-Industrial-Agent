package dev.stevecreate.agent.core.industrial;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable loader-neutral authority shared by Create, IE and future industrial player orders. */
public record IndustrialPlayerOrderV1(
        UUID orderId,
        UUID projectId,
        UUID ownerId,
        String worldIdentity,
        ResourceId dimension,
        ResourceId orderType,
        ResourceId target,
        ResourceId recipeId,
        BlockPos3i anchor,
        String planHash,
        String runtimeFingerprint,
        String baselineHash,
        IndustrialLifecyclePhase phase,
        String stage,
        Set<ResourceId> durableEffects,
        long createdAt,
        long updatedAt,
        long generation,
        Optional<IndustrialCompletionReportV1> report) {
    public static final int MAX_EFFECTS = 64;

    public IndustrialPlayerOrderV1 {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        if (worldIdentity.isBlank() || worldIdentity.length() > 2_048) {
            throw new IllegalArgumentException("industrial order world identity is invalid");
        }
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(anchor, "anchor");
        requireHash(planHash, "planHash");
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        if (runtimeFingerprint.isBlank() || runtimeFingerprint.length() > 16_384) {
            throw new IllegalArgumentException("industrial order runtime fingerprint is invalid");
        }
        requireHash(baselineHash, "baselineHash");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(stage, "stage");
        if (stage.isBlank() || stage.length() > 256) {
            throw new IllegalArgumentException("industrial order stage is blank or unbounded");
        }
        Objects.requireNonNull(durableEffects, "durableEffects");
        if (durableEffects.size() > MAX_EFFECTS || durableEffects.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("industrial order effects exceed their bound");
        }
        durableEffects = Set.copyOf(durableEffects);
        if (createdAt < 0 || updatedAt < createdAt || generation < 0) {
            throw new IllegalArgumentException("industrial order timing is invalid");
        }
        report = Objects.requireNonNull(report, "report");
        if (report.isPresent() && phase != IndustrialLifecyclePhase.COMPLETED
                && phase != IndustrialLifecyclePhase.RECOVERED) {
            throw new IllegalArgumentException("completion report requires a terminal order phase");
        }
    }

    public IndustrialPlayerOrderV1 checkpoint(
            IndustrialLifecyclePhase nextPhase,
            String nextStage,
            Set<ResourceId> nextEffects,
            long now) {
        Objects.requireNonNull(nextPhase, "nextPhase");
        Objects.requireNonNull(nextStage, "nextStage");
        if (now < updatedAt) throw new IllegalArgumentException("order time moved backwards");
        if (!canTransition(phase, nextPhase)) {
            throw new IllegalArgumentException(
                    "industrial order phase transition is not admitted: "
                            + phase + " -> " + nextPhase);
        }
        return new IndustrialPlayerOrderV1(orderId, projectId, ownerId, worldIdentity, dimension,
                orderType, target, recipeId, anchor, planHash, runtimeFingerprint, baselineHash,
                nextPhase, nextStage, nextEffects, createdAt, now, generation + 1, report);
    }

    /** Safety exits are always allowed from a non-terminal phase; normal work may not regress. */
    private static boolean canTransition(
            IndustrialLifecyclePhase current, IndustrialLifecyclePhase next) {
        if (current == next) return true;
        if (current == IndustrialLifecyclePhase.COMPLETED
                || current == IndustrialLifecyclePhase.RECOVERED
                || current == IndustrialLifecyclePhase.CANCELLED
                || current == IndustrialLifecyclePhase.FAILED) return false;
        if (next == IndustrialLifecyclePhase.PAUSED
                || next == IndustrialLifecyclePhase.CANCELLED
                || next == IndustrialLifecyclePhase.FAILED) return true;
        if (current == IndustrialLifecyclePhase.PAUSED) {
            return next == IndustrialLifecyclePhase.PLANNED
                    || next == IndustrialLifecyclePhase.PLACED
                    || next == IndustrialLifecyclePhase.FORMED
                    || next == IndustrialLifecyclePhase.CONNECTED
                    || next == IndustrialLifecyclePhase.CONFIGURED
                    || next == IndustrialLifecyclePhase.READY
                    || next == IndustrialLifecyclePhase.RUNNING
                    || next == IndustrialLifecyclePhase.DISMANTLING;
        }
        return switch (current) {
            case DISCOVERED -> next == IndustrialLifecyclePhase.PLANNED;
            case PLANNED -> next == IndustrialLifecyclePhase.PLACED
                    || next == IndustrialLifecyclePhase.FORMED
                    || next == IndustrialLifecyclePhase.CONNECTED
                    || next == IndustrialLifecyclePhase.CONFIGURED
                    || next == IndustrialLifecyclePhase.READY
                    || next == IndustrialLifecyclePhase.RUNNING
                    || next == IndustrialLifecyclePhase.DISMANTLING;
            case PLACED -> next == IndustrialLifecyclePhase.FORMED
                    || next == IndustrialLifecyclePhase.CONNECTED
                    || next == IndustrialLifecyclePhase.CONFIGURED
                    || next == IndustrialLifecyclePhase.READY
                    || next == IndustrialLifecyclePhase.RUNNING
                    || next == IndustrialLifecyclePhase.DISMANTLING;
            case FORMED -> next == IndustrialLifecyclePhase.CONNECTED
                    || next == IndustrialLifecyclePhase.CONFIGURED
                    || next == IndustrialLifecyclePhase.READY
                    || next == IndustrialLifecyclePhase.RUNNING
                    || next == IndustrialLifecyclePhase.DISMANTLING;
            case CONNECTED -> next == IndustrialLifecyclePhase.CONFIGURED
                    || next == IndustrialLifecyclePhase.READY
                    || next == IndustrialLifecyclePhase.RUNNING
                    || next == IndustrialLifecyclePhase.DISMANTLING;
            case CONFIGURED -> next == IndustrialLifecyclePhase.READY
                    || next == IndustrialLifecyclePhase.RUNNING
                    || next == IndustrialLifecyclePhase.DISMANTLING;
            case READY -> next == IndustrialLifecyclePhase.RUNNING
                    || next == IndustrialLifecyclePhase.DISMANTLING;
            case RUNNING -> next == IndustrialLifecyclePhase.DISMANTLING;
            case DISMANTLING -> next == IndustrialLifecyclePhase.RECOVERED;
            case RECOVERED, COMPLETED, PAUSED, CANCELLED, FAILED -> false;
        };
    }

    public IndustrialPlayerOrderV1 withReport(
            IndustrialCompletionReportV1 nextReport, long now) {
        Objects.requireNonNull(nextReport, "nextReport");
        if (now < updatedAt) throw new IllegalArgumentException("order time moved backwards");
        return new IndustrialPlayerOrderV1(orderId, projectId, ownerId, worldIdentity, dimension,
                orderType, target, recipeId, anchor, planHash, runtimeFingerprint, baselineHash,
                IndustrialLifecyclePhase.COMPLETED, "REPORT_GENERATED", durableEffects,
                createdAt, now, generation + 1, Optional.of(nextReport));
    }

    private static void requireHash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " must be SHA-256");
    }
}
