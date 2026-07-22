package dev.stevecreate.agent.core.recovery;

import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionSession;
import dev.stevecreate.agent.core.execution.GenericExecutionSessionStatus;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Discovers a persisted session but exposes it only after an exact bounded world rescan. */
public final class SessionRecoveryReconciler {
    public static final ResourceId STALE_SESSION_FAILURE =
            new ResourceId("steve_industrial", "recovery/stale_session");
    public static final int MAX_TRUSTED_PLANS = 128;

    private SessionRecoveryReconciler() {
    }

    public static Discovery discover(
            RecoveryCheckpoint checkpoint,
            Map<ResourceId, GenericExecutionPlan> trustedPlans) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Map<ResourceId, GenericExecutionPlan> plans = copyPlans(trustedPlans);
        GenericExecutionPlan trustedPlan = plans.get(checkpoint.plan().planId());
        if (trustedPlan == null) {
            return stale(
                    checkpoint,
                    StaleSessionReason.UNKNOWN_PLAN,
                    Optional.empty(),
                    "No trusted runtime plan is registered for " + checkpoint.plan().planId());
        }

        RecoveryPlanSnapshot current = RecoveryPlanSnapshot.capture(trustedPlan);
        if (!current.graph().equals(checkpoint.plan().graph())) {
            return stale(
                    checkpoint,
                    StaleSessionReason.GRAPH_CHANGED,
                    Optional.empty(),
                    "Trusted graph identity or topology fingerprint changed");
        }
        if (!current.equals(checkpoint.plan())) {
            return stale(
                    checkpoint,
                    StaleSessionReason.PLAN_CHANGED,
                    Optional.empty(),
                    "Trusted process or execution-plan fingerprint changed");
        }
        if (checkpoint.session().status() != GenericExecutionSessionStatus.RUNNING) {
            return stale(
                    checkpoint,
                    StaleSessionReason.SESSION_TERMINAL,
                    Optional.empty(),
                    "Only an in-progress session can be recovered");
        }
        boolean unsafeResources = checkpoint.journal().entries().stream()
                .anyMatch(entry -> !(entry instanceof BlockChange));
        if (unsafeResources) {
            return stale(
                    checkpoint,
                    StaleSessionReason.RESOURCE_HISTORY_UNSAFE,
                    Optional.empty(),
                    "Injected or irreversibly processed resources prevent automatic resume");
        }

        try {
            checkpoint.session().restore(trustedPlan);
        } catch (IllegalArgumentException exception) {
            return stale(
                    checkpoint,
                    StaleSessionReason.SESSION_INVALID,
                    Optional.empty(),
                    "Persisted session does not match the trusted plan: " + exception.getMessage());
        }

        Map<BlockPos3i, WorldBlockSnapshot> expected = new LinkedHashMap<>();
        for (WorldChangeJournal.Entry entry : checkpoint.journal().entries()) {
            BlockChange block = (BlockChange) entry;
            expected.put(block.position(), block.after());
        }
        if (!List.copyOf(expected.keySet()).equals(checkpoint.modifiedPositions())) {
            return stale(
                    checkpoint,
                    StaleSessionReason.SESSION_INVALID,
                    Optional.empty(),
                    "Safe checkpoint positions do not match its block journal");
        }
        return new RescanRequired(checkpoint, trustedPlan, expected);
    }

    public sealed interface Discovery permits RescanRequired, StaleSession {
    }

    public sealed interface Reconciliation permits Resumable, StaleSession {
    }

    /** A discovered candidate deliberately exposes positions, not an executable session. */
    public static final class RescanRequired implements Discovery {
        private final RecoveryCheckpoint checkpoint;
        private final GenericExecutionPlan trustedPlan;
        private final Map<BlockPos3i, WorldBlockSnapshot> expectedStates;

        private RescanRequired(
                RecoveryCheckpoint checkpoint,
                GenericExecutionPlan trustedPlan,
                Map<BlockPos3i, WorldBlockSnapshot> expectedStates) {
            this.checkpoint = checkpoint;
            this.trustedPlan = trustedPlan;
            this.expectedStates = Collections.unmodifiableMap(
                    new LinkedHashMap<>(expectedStates));
        }

        public ResourceId sessionId() {
            return checkpoint.session().sessionId();
        }

        public ResourceId planId() {
            return checkpoint.plan().planId();
        }

        public List<BlockPos3i> requiredPositions() {
            return checkpoint.modifiedPositions();
        }

        public long savedTick() {
            return checkpoint.savedTick();
        }

        public Reconciliation reconcile(
                Map<BlockPos3i, WorldBlockSnapshot> scannedStates) {
            Map<BlockPos3i, WorldBlockSnapshot> scanned = copyStates(scannedStates);
            for (Map.Entry<BlockPos3i, WorldBlockSnapshot> expected
                    : expectedStates.entrySet()) {
                WorldBlockSnapshot observed = scanned.get(expected.getKey());
                if (observed == null) {
                    return stale(
                            checkpoint,
                            StaleSessionReason.WORLD_STATE_MISSING,
                            Optional.of(expected.getKey()),
                            "Required recovery position was not scanned or is unavailable");
                }
                if (!observed.equals(expected.getValue())) {
                    return stale(
                            checkpoint,
                            StaleSessionReason.WORLD_STATE_CHANGED,
                            Optional.of(expected.getKey()),
                            "Current block or block-entity state differs from the saved after-state");
                }
            }
            try {
                return new Resumable(
                        checkpoint.session().restore(trustedPlan),
                        checkpoint.journal(),
                        List.copyOf(expectedStates.keySet()),
                        checkpoint.savedTick());
            } catch (IllegalArgumentException exception) {
                return stale(
                        checkpoint,
                        StaleSessionReason.SESSION_INVALID,
                        Optional.empty(),
                        "Persisted session became invalid during reconciliation: "
                                + exception.getMessage());
            }
        }
    }

    public record Resumable(
            GenericExecutionSession session,
            WorldChangeJournal journal,
            List<BlockPos3i> verifiedPositions,
            long savedTick) implements Reconciliation {
        public Resumable {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(journal, "journal");
            verifiedPositions = List.copyOf(verifiedPositions);
            if (!session.sessionId().equals(journal.sessionId())) {
                throw new IllegalArgumentException(
                        "Resumable session and journal identities differ");
            }
            if (savedTick < session.lastProgressTick()) {
                throw new IllegalArgumentException(
                        "Resumable savedTick predates session progress");
            }
        }

        /** Rebases only the step timeout window after this exact reconciliation succeeded. */
        public Resumable rebaseRecoveryTiming(long resumedTick) {
            if (resumedTick < savedTick) {
                throw new IllegalArgumentException(
                        "Recovery tick must not predate the reconciled checkpoint");
            }
            return new Resumable(
                    session.rebaseRecoveryTiming(resumedTick),
                    journal,
                    verifiedPositions,
                    resumedTick);
        }
    }

    public enum StaleSessionReason {
        UNKNOWN_PLAN,
        GRAPH_CHANGED,
        PLAN_CHANGED,
        SESSION_TERMINAL,
        RESOURCE_HISTORY_UNSAFE,
        SESSION_INVALID,
        WORLD_STATE_MISSING,
        WORLD_STATE_CHANGED
    }

    public record StaleSession(
            ResourceId sessionId,
            ResourceId failureCode,
            StaleSessionReason reason,
            Optional<BlockPos3i> position,
            String detail) implements Discovery, Reconciliation {
        public StaleSession {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(failureCode, "failureCode");
            Objects.requireNonNull(reason, "reason");
            position = Objects.requireNonNull(position, "position");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank() || detail.length() > 512) {
                throw new IllegalArgumentException(
                        "Stale-session detail must contain 1 to 512 characters");
            }
        }
    }

    private static StaleSession stale(
            RecoveryCheckpoint checkpoint,
            StaleSessionReason reason,
            Optional<BlockPos3i> position,
            String detail) {
        String bounded = detail.length() <= 512 ? detail : detail.substring(0, 512);
        return new StaleSession(
                checkpoint.session().sessionId(),
                STALE_SESSION_FAILURE,
                reason,
                position,
                bounded);
    }

    private static Map<ResourceId, GenericExecutionPlan> copyPlans(
            Map<ResourceId, GenericExecutionPlan> values) {
        Objects.requireNonNull(values, "trustedPlans");
        if (values.size() > MAX_TRUSTED_PLANS) {
            throw new IllegalArgumentException(
                    "trustedPlans count exceeds " + MAX_TRUSTED_PLANS);
        }
        Map<ResourceId, GenericExecutionPlan> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceId, GenericExecutionPlan> entry : values.entrySet()) {
            ResourceId id = Objects.requireNonNull(entry.getKey(), "trustedPlans key");
            GenericExecutionPlan plan = Objects.requireNonNull(
                    entry.getValue(), "trustedPlans value");
            if (!id.equals(plan.planId())) {
                throw new IllegalArgumentException(
                        "Trusted plan key does not match plan identity: " + id);
            }
            copy.put(id, plan);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<BlockPos3i, WorldBlockSnapshot> copyStates(
            Map<BlockPos3i, WorldBlockSnapshot> values) {
        Objects.requireNonNull(values, "scannedStates");
        if (values.size() > WorldChangeJournal.MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "scannedStates count exceeds " + WorldChangeJournal.MAX_ENTRIES);
        }
        Map<BlockPos3i, WorldBlockSnapshot> copy = new LinkedHashMap<>();
        for (Map.Entry<BlockPos3i, WorldBlockSnapshot> entry : values.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "scannedStates key"),
                    Objects.requireNonNull(entry.getValue(), "scannedStates value"));
        }
        return Collections.unmodifiableMap(copy);
    }
}
