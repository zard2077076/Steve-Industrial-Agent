package dev.stevecreate.agent.core.recovery;

import dev.stevecreate.agent.core.execution.SessionWorldChangeReference;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable loader-neutral journal of bounded world and resource changes caused by one session.
 *
 * <p>The journal can plan only conservative block restoration. It never executes a world
 * mutation and never proposes resource compensation after an input was injected or processing
 * became irreversible.</p>
 */
public final class WorldChangeJournal {
    public static final int MAX_ENTRIES = 4_096;
    public static final int MAX_ROLLBACK_OPERATIONS = 64;
    public static final int MAX_AFFECTED_POSITIONS = 64;
    public static final int MAX_BLOCK_PROPERTIES = 64;
    public static final int MAX_PROPERTY_VALUE_LENGTH = 256;
    public static final int MAX_JOURNAL_DATA_LENGTH = 65_536;
    public static final int MAX_WARNING_DETAIL_LENGTH = 512;

    private static final Pattern PROPERTY_NAME = Pattern.compile("[a-z0-9_.-]+");

    private final ResourceId sessionId;
    private final List<Entry> entries;

    public WorldChangeJournal(ResourceId sessionId, List<? extends Entry> entries) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("World change count exceeds " + MAX_ENTRIES);
        }
        List<Entry> copy = new ArrayList<>(entries.size());
        Set<ResourceId> changeIds = new HashSet<>();
        long previousTick = -1;
        for (Entry value : entries) {
            Entry entry = Objects.requireNonNull(value, "entries element");
            if (!entry.sessionId().equals(sessionId)) {
                throw new IllegalArgumentException(
                        "World change belongs to " + entry.sessionId() + " instead of " + sessionId);
            }
            if (!changeIds.add(entry.changeId())) {
                throw new IllegalArgumentException("Duplicate world change id: " + entry.changeId());
            }
            if (entry.recordedTick() < previousTick) {
                throw new IllegalArgumentException("World change ticks must be monotonic");
            }
            previousTick = entry.recordedTick();
            copy.add(entry);
        }
        this.entries = Collections.unmodifiableList(copy);
    }

    public static WorldChangeJournal empty(ResourceId sessionId) {
        return new WorldChangeJournal(sessionId, List.of());
    }

    public ResourceId sessionId() {
        return sessionId;
    }

    public List<Entry> entries() {
        return entries;
    }

    public WorldChangeJournal append(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        List<Entry> next = new ArrayList<>(entries);
        next.add(entry);
        return new WorldChangeJournal(sessionId, next);
    }

    public List<BlockPos3i> modifiedPositions() {
        LinkedHashSet<BlockPos3i> positions = new LinkedHashSet<>();
        for (Entry entry : entries) {
            positions.addAll(entry.affectedPositions());
        }
        return List.copyOf(positions);
    }

    public List<SessionWorldChangeReference> references() {
        return entries.stream().map(Entry::reference).toList();
    }

    /**
     * Refreshes only the block-entity payload of the last block change at each modified position.
     *
     * <p>This is for a pre-resource recovery checkpoint after loader-managed block entities have
     * naturally stabilized. Registered block identity and every block-state property must still
     * exactly match the action's after-state. Change identities, source, ticks, before-states and
     * references are retained, so this cannot hide a block-state drift or create new work.</p>
     */
    public WorldChangeJournal stabilizeBlockEntityAfterStates(
            Map<BlockPos3i, WorldBlockSnapshot> currentStates) {
        if (entries.stream().anyMatch(entry -> !(entry instanceof BlockChange))) {
            throw new IllegalArgumentException(
                    "Only a block-only journal can be stabilized for recovery");
        }
        Map<BlockPos3i, WorldBlockSnapshot> current = copyCurrentStates(currentStates);
        List<BlockPos3i> positions = modifiedPositions();
        if (current.size() != positions.size() || !current.keySet().containsAll(positions)) {
            throw new IllegalArgumentException(
                    "Recovery stabilization requires every modified block position exactly once");
        }

        List<Entry> next = new ArrayList<>(entries);
        Set<BlockPos3i> stabilized = new HashSet<>();
        for (int index = next.size() - 1; index >= 0; index--) {
            BlockChange block = (BlockChange) next.get(index);
            if (!stabilized.add(block.position())) {
                continue;
            }
            WorldBlockSnapshot observed = current.get(block.position());
            if (!block.after().blockId().equals(observed.blockId())
                    || !block.after().properties().equals(observed.properties())) {
                throw new IllegalArgumentException(
                        "Recovery stabilization found block-state drift at " + block.position());
            }
            WorldBlockSnapshot stableAfter = new WorldBlockSnapshot(
                    block.after().blockId(),
                    block.after().properties(),
                    observed.blockEntityData());
            if (!stableAfter.equals(block.after())) {
                next.set(index, new BlockChange(
                        block.changeId(),
                        block.sessionId(),
                        block.sourceStepId(),
                        block.recordedTick(),
                        block.position(),
                        block.before(),
                        stableAfter));
            }
        }
        return new WorldChangeJournal(sessionId, next);
    }

    @Override
    public boolean equals(Object value) {
        return this == value
                || (value instanceof WorldChangeJournal other
                        && sessionId.equals(other.sessionId)
                        && entries.equals(other.entries));
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, entries);
    }

    /**
     * Plans a reverse-order rollback without reading or mutating a world.
     *
     * <p>If any resource was injected or real processing completed, block restoration is skipped
     * entirely because removing the topology could destroy or duplicate resources. Otherwise a
     * block is restorable only when its current complete snapshot still equals the session's
     * recorded after-state.</p>
     */
    public RollbackPlan planRollback(Map<BlockPos3i, WorldBlockSnapshot> currentStates) {
        Map<BlockPos3i, WorldBlockSnapshot> current = copyCurrentStates(currentStates);
        List<RollbackWarning> warnings = resourceWarnings();
        if (!warnings.isEmpty()) {
            return new RollbackPlan(sessionId, List.of(), warnings);
        }

        List<RollbackOperation> operations = new ArrayList<>();
        Map<BlockPos3i, WorldBlockSnapshot> simulated = new LinkedHashMap<>(current);
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            if (!(entry instanceof BlockChange block)) {
                continue;
            }
            if (operations.size() >= MAX_ROLLBACK_OPERATIONS) {
                warnings.add(new RollbackWarning(
                        block.changeId(),
                        RollbackWarningCode.ROLLBACK_LIMIT_REACHED,
                        Optional.of(block.position()),
                        "Conservative rollback stopped at the "
                                + MAX_ROLLBACK_OPERATIONS + " operation limit"));
                continue;
            }
            WorldBlockSnapshot observed = simulated.get(block.position());
            if (observed == null) {
                warnings.add(new RollbackWarning(
                        block.changeId(),
                        RollbackWarningCode.CURRENT_STATE_UNAVAILABLE,
                        Optional.of(block.position()),
                        "Current block state was not supplied; no rollback was planned"));
                continue;
            }
            if (!observed.equals(block.after())) {
                warnings.add(new RollbackWarning(
                        block.changeId(),
                        RollbackWarningCode.CURRENT_STATE_CHANGED,
                        Optional.of(block.position()),
                        "Current block or block-entity state differs from the session after-state"));
                continue;
            }
            operations.add(new RollbackOperation(
                    block.changeId(),
                    block.position(),
                    block.after(),
                    block.before()));
            simulated.put(block.position(), block.before());
        }
        return new RollbackPlan(sessionId, operations, warnings);
    }

    private List<RollbackWarning> resourceWarnings() {
        List<RollbackWarning> warnings = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry instanceof InjectedResourceChange input) {
                warnings.add(new RollbackWarning(
                        input.changeId(),
                        RollbackWarningCode.INJECTED_RESOURCE_NOT_RECREATED,
                        Optional.of(input.position()),
                        "Injected resource changes block rollback. "
                                + "No item or resource compensation was created"));
            } else if (entry instanceof IrreversibleProcessingChange processing) {
                warnings.add(new RollbackWarning(
                        processing.changeId(),
                        RollbackWarningCode.IRREVERSIBLE_PROCESSING_NOT_REVERSED,
                        Optional.empty(),
                        "Completed processing changes block rollback. "
                                + "No item or resource compensation was created"));
            }
        }
        return warnings;
    }

    private static Map<BlockPos3i, WorldBlockSnapshot> copyCurrentStates(
            Map<BlockPos3i, WorldBlockSnapshot> values) {
        Objects.requireNonNull(values, "currentStates");
        if (values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Current-state count exceeds " + MAX_ENTRIES);
        }
        Map<BlockPos3i, WorldBlockSnapshot> copy = new LinkedHashMap<>();
        for (Map.Entry<BlockPos3i, WorldBlockSnapshot> entry : values.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "currentStates key"),
                    Objects.requireNonNull(entry.getValue(), "currentStates value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    public sealed interface Entry
            permits BlockChange, InjectedResourceChange, IrreversibleProcessingChange {
        ResourceId changeId();

        ResourceId sessionId();

        ResourceId sourceStepId();

        long recordedTick();

        List<BlockPos3i> affectedPositions();

        boolean potentiallyReversible();

        default SessionWorldChangeReference reference() {
            return new SessionWorldChangeReference(
                    changeId(), sourceStepId(), recordedTick(), potentiallyReversible());
        }
    }

    public record BlockChange(
            ResourceId changeId,
            ResourceId sessionId,
            ResourceId sourceStepId,
            long recordedTick,
            BlockPos3i position,
            WorldBlockSnapshot before,
            WorldBlockSnapshot after) implements Entry {
        public BlockChange {
            validateCommon(changeId, sessionId, sourceStepId, recordedTick);
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            if (before.equals(after)) {
                throw new IllegalArgumentException("Block change before and after states must differ");
            }
        }

        @Override
        public List<BlockPos3i> affectedPositions() {
            return List.of(position);
        }

        @Override
        public boolean potentiallyReversible() {
            return true;
        }
    }

    public record InjectedResourceChange(
            ResourceId changeId,
            ResourceId sessionId,
            ResourceId sourceStepId,
            long recordedTick,
            BlockPos3i position,
            ProcessResource resource) implements Entry {
        public InjectedResourceChange {
            validateCommon(changeId, sessionId, sourceStepId, recordedTick);
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(resource, "resource");
        }

        @Override
        public List<BlockPos3i> affectedPositions() {
            return List.of(position);
        }

        @Override
        public boolean potentiallyReversible() {
            return false;
        }
    }

    public record IrreversibleProcessingChange(
            ResourceId changeId,
            ResourceId sessionId,
            ResourceId sourceStepId,
            long recordedTick,
            ResourceId recipeId,
            List<ProcessResource> consumedInputs,
            List<ProcessResource> producedOutputs,
            List<BlockPos3i> affectedPositions) implements Entry {
        public IrreversibleProcessingChange {
            validateCommon(changeId, sessionId, sourceStepId, recordedTick);
            Objects.requireNonNull(recipeId, "recipeId");
            consumedInputs = copyResources(consumedInputs, "consumedInputs");
            producedOutputs = copyResources(producedOutputs, "producedOutputs");
            affectedPositions = copyPositions(affectedPositions);
        }

        @Override
        public boolean potentiallyReversible() {
            return false;
        }
    }

    public record WorldBlockSnapshot(
            ResourceId blockId,
            Map<String, String> properties,
            Optional<JournalData> blockEntityData) {
        public WorldBlockSnapshot {
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(properties, "properties");
            if (properties.size() > MAX_BLOCK_PROPERTIES) {
                throw new IllegalArgumentException(
                        "Block-state property count exceeds " + MAX_BLOCK_PROPERTIES);
            }
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                String name = Objects.requireNonNull(entry.getKey(), "properties key");
                if (!PROPERTY_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("Invalid block-state property name: " + name);
                }
                String value = Objects.requireNonNull(entry.getValue(), "properties value");
                if (value.isBlank() || value.length() > MAX_PROPERTY_VALUE_LENGTH) {
                    throw new IllegalArgumentException(
                            "Block-state property value must contain 1 to "
                                    + MAX_PROPERTY_VALUE_LENGTH + " characters");
                }
                copy.put(name, value);
            }
            properties = Collections.unmodifiableMap(copy);
            blockEntityData = Objects.requireNonNull(blockEntityData, "blockEntityData");
        }
    }

    public record JournalData(ResourceId schemaId, String value) {
        public JournalData {
            Objects.requireNonNull(schemaId, "schemaId");
            Objects.requireNonNull(value, "value");
            if (value.isBlank() || value.length() > MAX_JOURNAL_DATA_LENGTH) {
                throw new IllegalArgumentException(
                        "Journal data must contain 1 to " + MAX_JOURNAL_DATA_LENGTH + " characters");
            }
        }
    }

    public record RollbackOperation(
            ResourceId changeId,
            BlockPos3i position,
            WorldBlockSnapshot expectedCurrentState,
            WorldBlockSnapshot restoreState) {
        public RollbackOperation {
            Objects.requireNonNull(changeId, "changeId");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(expectedCurrentState, "expectedCurrentState");
            Objects.requireNonNull(restoreState, "restoreState");
        }
    }

    public record RollbackPlan(
            ResourceId sessionId,
            List<RollbackOperation> operations,
            List<RollbackWarning> warnings) {
        public RollbackPlan {
            Objects.requireNonNull(sessionId, "sessionId");
            operations = copyList(operations, "operations", MAX_ROLLBACK_OPERATIONS);
            warnings = copyList(warnings, "warnings", MAX_ENTRIES);
        }
    }

    public enum RollbackWarningCode {
        CURRENT_STATE_UNAVAILABLE,
        CURRENT_STATE_CHANGED,
        INJECTED_RESOURCE_NOT_RECREATED,
        IRREVERSIBLE_PROCESSING_NOT_REVERSED,
        ROLLBACK_LIMIT_REACHED,
        RESTORE_REJECTED
    }

    public record RollbackWarning(
            ResourceId changeId,
            RollbackWarningCode code,
            Optional<BlockPos3i> position,
            String detail) {
        public RollbackWarning {
            Objects.requireNonNull(changeId, "changeId");
            Objects.requireNonNull(code, "code");
            position = Objects.requireNonNull(position, "position");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank() || detail.length() > MAX_WARNING_DETAIL_LENGTH) {
                throw new IllegalArgumentException(
                        "Rollback warning detail must contain 1 to "
                                + MAX_WARNING_DETAIL_LENGTH + " characters");
            }
        }
    }

    public record RollbackReport(
            ResourceId sessionId,
            int journalEntryCount,
            int plannedOperationCount,
            List<BlockPos3i> restoredPositions,
            List<RollbackWarning> warnings) {
        public RollbackReport {
            Objects.requireNonNull(sessionId, "sessionId");
            if (journalEntryCount < 0 || journalEntryCount > MAX_ENTRIES) {
                throw new IllegalArgumentException("Invalid journalEntryCount");
            }
            if (plannedOperationCount < 0 || plannedOperationCount > MAX_ROLLBACK_OPERATIONS) {
                throw new IllegalArgumentException("Invalid plannedOperationCount");
            }
            restoredPositions = copyList(
                    restoredPositions, "restoredPositions", MAX_ROLLBACK_OPERATIONS);
            if (restoredPositions.size() > plannedOperationCount) {
                throw new IllegalArgumentException(
                        "Restored position count exceeds planned operation count");
            }
            warnings = copyList(warnings, "warnings", MAX_ENTRIES);
        }

        public boolean fullyRestored() {
            return warnings.isEmpty() && restoredPositions.size() == plannedOperationCount;
        }
    }

    private static void validateCommon(
            ResourceId changeId,
            ResourceId sessionId,
            ResourceId sourceStepId,
            long recordedTick) {
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(sourceStepId, "sourceStepId");
        if (recordedTick < 0) {
            throw new IllegalArgumentException("recordedTick must not be negative");
        }
    }

    private static List<ProcessResource> copyResources(
            List<ProcessResource> values,
            String name) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty() || values.size() > 32) {
            throw new IllegalArgumentException(name + " count must be between 1 and 32");
        }
        return copyList(values, name, 32);
    }

    private static List<BlockPos3i> copyPositions(List<BlockPos3i> values) {
        List<BlockPos3i> copy = copyList(
                values, "affectedPositions", MAX_AFFECTED_POSITIONS);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("affectedPositions must not be empty");
        }
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("affectedPositions contains duplicates");
        }
        return copy;
    }

    private static <T> List<T> copyList(List<T> values, String name, int maximum) {
        Objects.requireNonNull(values, name);
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " count exceeds " + maximum);
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableList(copy);
    }
}
