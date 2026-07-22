package dev.stevecreate.agent.core.recovery;

import dev.stevecreate.agent.core.execution.GenericExecutionPlan;
import dev.stevecreate.agent.core.execution.GenericExecutionSession;
import dev.stevecreate.agent.core.execution.GenericExecutionSessionStatus;
import dev.stevecreate.agent.core.execution.GenericStepRunState;
import dev.stevecreate.agent.core.execution.SessionFailure;
import dev.stevecreate.agent.core.execution.SessionWorldChangeReference;
import dev.stevecreate.agent.core.graph.UnifiedMachineGraph;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.BlockChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.InjectedResourceChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.IrreversibleProcessingChange;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.JournalData;
import dev.stevecreate.agent.core.recovery.WorldChangeJournal.WorldBlockSnapshot;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.EvidenceDiagnostic;
import dev.stevecreate.agent.core.verification.EvidenceValue;
import dev.stevecreate.agent.core.verification.VerificationEvidence;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.CRC32;

/** Versioned bounded binary codec for one recovery checkpoint. */
public final class RecoveryCheckpointCodec {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENCODED_BYTES = 4 * 1_024 * 1_024;

    private static final int MAGIC = 0x53494152; // SIAR
    private static final int FRAME_OVERHEAD = Integer.BYTES + Short.BYTES
            + Integer.BYTES + Long.BYTES;
    private static final int MAX_ID_CHARS = 512;
    private static final int MAX_ENUM_CHARS = 64;
    private static final int MAX_PROPERTY_NAME_CHARS = 256;

    private RecoveryCheckpointCodec() {
    }

    public static byte[] encode(RecoveryCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        try {
            LimitedOutputStream limited = new LimitedOutputStream(MAX_ENCODED_BYTES);
            try (DataOutputStream output = new DataOutputStream(limited)) {
                new Writer(output).checkpoint(checkpoint);
            }
            byte[] payload = limited.toByteArray();
            CRC32 checksum = new CRC32();
            checksum.update(payload);
            ByteArrayOutputStream framed = new ByteArrayOutputStream(
                    payload.length + FRAME_OVERHEAD);
            try (DataOutputStream output = new DataOutputStream(framed)) {
                output.writeInt(MAGIC);
                output.writeShort(FORMAT_VERSION);
                output.writeInt(payload.length);
                output.write(payload);
                output.writeLong(checksum.getValue());
            }
            return framed.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Recovery checkpoint exceeds or violates codec bounds", exception);
        }
    }

    public static RecoveryCheckpoint decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < FRAME_OVERHEAD
                || encoded.length > MAX_ENCODED_BYTES + FRAME_OVERHEAD) {
            throw new IllegalArgumentException("Recovery checkpoint frame length is invalid");
        }
        try (DataInputStream frame = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (frame.readInt() != MAGIC) {
                throw new IOException("Recovery checkpoint magic is invalid");
            }
            int version = frame.readUnsignedShort();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported recovery checkpoint version: " + version);
            }
            int payloadLength = frame.readInt();
            if (payloadLength < 0 || payloadLength > MAX_ENCODED_BYTES
                    || payloadLength != encoded.length - FRAME_OVERHEAD) {
                throw new IOException("Recovery checkpoint payload length is invalid");
            }
            byte[] payload = frame.readNBytes(payloadLength);
            if (payload.length != payloadLength) {
                throw new IOException("Recovery checkpoint payload is truncated");
            }
            long expectedChecksum = frame.readLong();
            if (frame.available() != 0) {
                throw new IOException("Recovery checkpoint has trailing frame bytes");
            }
            CRC32 checksum = new CRC32();
            checksum.update(payload);
            if (checksum.getValue() != expectedChecksum) {
                throw new IOException("Recovery checkpoint checksum does not match");
            }
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(payload))) {
                RecoveryCheckpoint checkpoint = new Reader(input).checkpoint();
                if (input.available() != 0) {
                    throw new IOException("Recovery checkpoint has trailing payload bytes");
                }
                return checkpoint;
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Invalid recovery checkpoint", exception);
        }
    }

    private static final class Writer {
        private final DataOutputStream output;

        private Writer(DataOutputStream output) {
            this.output = output;
        }

        private void checkpoint(RecoveryCheckpoint checkpoint) throws IOException {
            plan(checkpoint.plan());
            session(checkpoint.session());
            journal(checkpoint.journal());
            positions(checkpoint.modifiedPositions(), WorldChangeJournal.MAX_ENTRIES);
            output.writeLong(checkpoint.savedTick());
        }

        private void plan(RecoveryPlanSnapshot plan) throws IOException {
            id(plan.planId());
            graph(plan.graph());
            id(plan.recipeId());
            id(plan.recipeType());
            ids(plan.orderedStepIds(), GenericExecutionPlan.MAX_STEPS);
            string(plan.fingerprint(), 64);
        }

        private void graph(RecoveryGraphSnapshot graph) throws IOException {
            id(graph.graphId());
            ids(graph.nodeIds(), UnifiedMachineGraph.MAX_NODES);
            ids(graph.portIds(), UnifiedMachineGraph.MAX_PORTS);
            ids(graph.edgeIds(), UnifiedMachineGraph.MAX_EDGES);
            string(graph.fingerprint(), 64);
        }

        private void session(RecoverySessionSnapshot session) throws IOException {
            id(session.sessionId());
            id(session.planId());
            enumeration(session.status());
            output.writeInt(session.currentStepIndex());
            optional(session.currentStepId(), this::id);
            output.writeInt(session.currentAttempt());
            enumeration(session.stepRunState());
            output.writeLong(session.currentStepStartedTick());
            output.writeLong(session.startedTick());
            output.writeLong(session.lastProgressTick());
            ids(session.completedStepIds(), GenericExecutionPlan.MAX_STEPS);
            output.writeInt(session.evidence().size());
            for (VerificationEvidence evidence : session.evidence()) {
                evidence(evidence);
            }
            output.writeInt(session.worldChanges().size());
            for (SessionWorldChangeReference reference : session.worldChanges()) {
                worldChangeReference(reference);
            }
            optional(session.failure(), this::failure);
            optional(session.cancellationReason(), this::id);
            optionalLong(session.terminalTick());
        }

        private void evidence(VerificationEvidence evidence) throws IOException {
            id(evidence.evidenceId());
            string(evidence.kind().serializedName(), MAX_ENUM_CHARS);
            id(evidence.requirementId());
            id(evidence.sourceStepId());
            id(evidence.sourceId());
            id(evidence.targetId());
            evidenceValue(evidence.observedValue());
            evidenceValue(evidence.expectedValue());
            output.writeLong(evidence.observedTick());
            output.writeBoolean(evidence.passed());
            optional(evidence.diagnostic(), this::diagnostic);
        }

        private void evidenceValue(EvidenceValue value) throws IOException {
            id(value.schemaId());
            string(value.value(), EvidenceValue.MAX_VALUE_LENGTH);
        }

        private void diagnostic(EvidenceDiagnostic diagnostic) throws IOException {
            id(diagnostic.code());
            string(diagnostic.detail(), EvidenceDiagnostic.MAX_DETAIL_LENGTH);
        }

        private void failure(SessionFailure failure) throws IOException {
            id(failure.failureCode());
            id(failure.sourceStepId());
            output.writeLong(failure.failedTick());
            string(failure.detail(), SessionFailure.MAX_DETAIL_LENGTH);
        }

        private void worldChangeReference(SessionWorldChangeReference reference)
                throws IOException {
            id(reference.changeId());
            id(reference.sourceStepId());
            output.writeLong(reference.recordedTick());
            output.writeBoolean(reference.potentiallyReversible());
        }

        private void journal(WorldChangeJournal journal) throws IOException {
            id(journal.sessionId());
            output.writeInt(journal.entries().size());
            for (WorldChangeJournal.Entry entry : journal.entries()) {
                if (entry instanceof BlockChange block) {
                    output.writeByte(1);
                    commonEntry(block);
                    position(block.position());
                    blockSnapshot(block.before());
                    blockSnapshot(block.after());
                } else if (entry instanceof InjectedResourceChange input) {
                    output.writeByte(2);
                    commonEntry(input);
                    position(input.position());
                    resource(input.resource());
                } else if (entry instanceof IrreversibleProcessingChange processing) {
                    output.writeByte(3);
                    commonEntry(processing);
                    id(processing.recipeId());
                    resources(processing.consumedInputs());
                    resources(processing.producedOutputs());
                    positions(
                            processing.affectedPositions(),
                            WorldChangeJournal.MAX_AFFECTED_POSITIONS);
                } else {
                    throw new IOException(
                            "Unsupported world-change entry type: " + entry.getClass());
                }
            }
        }

        private void commonEntry(WorldChangeJournal.Entry entry) throws IOException {
            id(entry.changeId());
            id(entry.sourceStepId());
            output.writeLong(entry.recordedTick());
        }

        private void blockSnapshot(WorldBlockSnapshot snapshot) throws IOException {
            id(snapshot.blockId());
            output.writeInt(snapshot.properties().size());
            List<Map.Entry<String, String>> properties = new ArrayList<>(
                    snapshot.properties().entrySet());
            properties.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, String> entry : properties) {
                string(entry.getKey(), MAX_PROPERTY_NAME_CHARS);
                string(entry.getValue(), WorldChangeJournal.MAX_PROPERTY_VALUE_LENGTH);
            }
            optional(snapshot.blockEntityData(), this::journalData);
        }

        private void journalData(JournalData data) throws IOException {
            id(data.schemaId());
            string(data.value(), WorldChangeJournal.MAX_JOURNAL_DATA_LENGTH);
        }

        private void resources(List<ProcessResource> resources) throws IOException {
            output.writeInt(resources.size());
            for (ProcessResource resource : resources) {
                resource(resource);
            }
        }

        private void resource(ProcessResource resource) throws IOException {
            id(resource.resourceId());
            string(resource.resourceType().serializedName(), MAX_ENUM_CHARS);
            output.writeLong(resource.amount());
        }

        private void ids(List<ResourceId> values, int maximum) throws IOException {
            if (values.size() > maximum) {
                throw new IOException("Identifier count exceeds " + maximum);
            }
            output.writeInt(values.size());
            for (ResourceId value : values) {
                id(value);
            }
        }

        private void positions(List<BlockPos3i> values, int maximum) throws IOException {
            if (values.size() > maximum) {
                throw new IOException("Position count exceeds " + maximum);
            }
            output.writeInt(values.size());
            for (BlockPos3i value : values) {
                position(value);
            }
        }

        private void position(BlockPos3i position) throws IOException {
            output.writeInt(position.x());
            output.writeInt(position.y());
            output.writeInt(position.z());
        }

        private void id(ResourceId id) throws IOException {
            string(id.toString(), MAX_ID_CHARS);
        }

        private void enumeration(Enum<?> value) throws IOException {
            string(value.name(), MAX_ENUM_CHARS);
        }

        private void optionalLong(OptionalLong value) throws IOException {
            output.writeBoolean(value.isPresent());
            if (value.isPresent()) {
                output.writeLong(value.getAsLong());
            }
        }

        private <T> void optional(Optional<T> value, IoConsumer<T> consumer)
                throws IOException {
            output.writeBoolean(value.isPresent());
            if (value.isPresent()) {
                consumer.accept(value.orElseThrow());
            }
        }

        private void string(String value, int maximumCharacters) throws IOException {
            Objects.requireNonNull(value, "value");
            if (value.length() > maximumCharacters) {
                throw new IOException(
                        "String exceeds " + maximumCharacters + " characters");
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            output.writeInt(bytes.length);
            output.write(bytes);
        }
    }

    private static final class Reader {
        private final DataInputStream input;

        private Reader(DataInputStream input) {
            this.input = input;
        }

        private RecoveryCheckpoint checkpoint() throws IOException {
            RecoveryPlanSnapshot plan = plan();
            RecoverySessionSnapshot session = session();
            WorldChangeJournal journal = journal();
            List<BlockPos3i> modifiedPositions = positions(WorldChangeJournal.MAX_ENTRIES);
            long savedTick = input.readLong();
            return new RecoveryCheckpoint(
                    plan, session, journal, modifiedPositions, savedTick);
        }

        private RecoveryPlanSnapshot plan() throws IOException {
            return new RecoveryPlanSnapshot(
                    id(),
                    graph(),
                    id(),
                    id(),
                    ids(GenericExecutionPlan.MAX_STEPS),
                    string(64));
        }

        private RecoveryGraphSnapshot graph() throws IOException {
            return new RecoveryGraphSnapshot(
                    id(),
                    ids(UnifiedMachineGraph.MAX_NODES),
                    ids(UnifiedMachineGraph.MAX_PORTS),
                    ids(UnifiedMachineGraph.MAX_EDGES),
                    string(64));
        }

        private RecoverySessionSnapshot session() throws IOException {
            ResourceId sessionId = id();
            ResourceId planId = id();
            GenericExecutionSessionStatus status = enumeration(
                    GenericExecutionSessionStatus.class);
            int currentStepIndex = input.readInt();
            Optional<ResourceId> currentStepId = optional(this::id);
            int currentAttempt = input.readInt();
            GenericStepRunState stepRunState = enumeration(GenericStepRunState.class);
            long currentStepStartedTick = input.readLong();
            long startedTick = input.readLong();
            long lastProgressTick = input.readLong();
            List<ResourceId> completedStepIds = ids(GenericExecutionPlan.MAX_STEPS);
            int evidenceCount = count(GenericExecutionSession.MAX_EVIDENCE_REFERENCES);
            List<VerificationEvidence> evidence = new ArrayList<>(evidenceCount);
            for (int index = 0; index < evidenceCount; index++) {
                evidence.add(evidence());
            }
            int referenceCount = count(GenericExecutionSession.MAX_WORLD_CHANGE_REFERENCES);
            List<SessionWorldChangeReference> references = new ArrayList<>(referenceCount);
            for (int index = 0; index < referenceCount; index++) {
                references.add(worldChangeReference());
            }
            Optional<SessionFailure> failure = optional(this::failure);
            Optional<ResourceId> cancellationReason = optional(this::id);
            OptionalLong terminalTick = optionalLong();
            return new RecoverySessionSnapshot(
                    sessionId,
                    planId,
                    status,
                    currentStepIndex,
                    currentStepId,
                    currentAttempt,
                    stepRunState,
                    currentStepStartedTick,
                    startedTick,
                    lastProgressTick,
                    completedStepIds,
                    evidence,
                    references,
                    failure,
                    cancellationReason,
                    terminalTick);
        }

        private VerificationEvidence evidence() throws IOException {
            return new VerificationEvidence(
                    id(),
                    VerificationEvidenceKind.fromSerializedName(string(MAX_ENUM_CHARS)),
                    id(),
                    id(),
                    id(),
                    id(),
                    evidenceValue(),
                    evidenceValue(),
                    input.readLong(),
                    booleanValue(),
                    optional(this::diagnostic));
        }

        private EvidenceValue evidenceValue() throws IOException {
            return new EvidenceValue(id(), string(EvidenceValue.MAX_VALUE_LENGTH));
        }

        private EvidenceDiagnostic diagnostic() throws IOException {
            return new EvidenceDiagnostic(id(), string(EvidenceDiagnostic.MAX_DETAIL_LENGTH));
        }

        private SessionFailure failure() throws IOException {
            return new SessionFailure(
                    id(), id(), input.readLong(), string(SessionFailure.MAX_DETAIL_LENGTH));
        }

        private SessionWorldChangeReference worldChangeReference() throws IOException {
            return new SessionWorldChangeReference(
                    id(), id(), input.readLong(), booleanValue());
        }

        private WorldChangeJournal journal() throws IOException {
            ResourceId sessionId = id();
            int entryCount = count(WorldChangeJournal.MAX_ENTRIES);
            List<WorldChangeJournal.Entry> entries = new ArrayList<>(entryCount);
            for (int index = 0; index < entryCount; index++) {
                int type = input.readUnsignedByte();
                ResourceId changeId = id();
                ResourceId sourceStepId = id();
                long recordedTick = input.readLong();
                if (type == 1) {
                    entries.add(new BlockChange(
                            changeId,
                            sessionId,
                            sourceStepId,
                            recordedTick,
                            position(),
                            blockSnapshot(),
                            blockSnapshot()));
                } else if (type == 2) {
                    entries.add(new InjectedResourceChange(
                            changeId,
                            sessionId,
                            sourceStepId,
                            recordedTick,
                            position(),
                            resource()));
                } else if (type == 3) {
                    entries.add(new IrreversibleProcessingChange(
                            changeId,
                            sessionId,
                            sourceStepId,
                            recordedTick,
                            id(),
                            resources(),
                            resources(),
                            positions(WorldChangeJournal.MAX_AFFECTED_POSITIONS)));
                } else {
                    throw new IOException("Unknown world-change entry tag: " + type);
                }
            }
            return new WorldChangeJournal(sessionId, entries);
        }

        private WorldBlockSnapshot blockSnapshot() throws IOException {
            ResourceId blockId = id();
            int propertyCount = count(WorldChangeJournal.MAX_BLOCK_PROPERTIES);
            Map<String, String> properties = new LinkedHashMap<>();
            for (int index = 0; index < propertyCount; index++) {
                String name = string(MAX_PROPERTY_NAME_CHARS);
                String previous = properties.put(
                        name,
                        string(WorldChangeJournal.MAX_PROPERTY_VALUE_LENGTH));
                if (previous != null) {
                    throw new IOException("Duplicate block-state property: " + name);
                }
            }
            Optional<JournalData> data = optional(this::journalData);
            return new WorldBlockSnapshot(blockId, properties, data);
        }

        private JournalData journalData() throws IOException {
            return new JournalData(id(), string(WorldChangeJournal.MAX_JOURNAL_DATA_LENGTH));
        }

        private List<ProcessResource> resources() throws IOException {
            int count = count(32);
            List<ProcessResource> resources = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                resources.add(resource());
            }
            return resources;
        }

        private ProcessResource resource() throws IOException {
            return new ProcessResource(
                    id(),
                    GenericResourceType.fromSerializedName(string(MAX_ENUM_CHARS)),
                    input.readLong());
        }

        private List<ResourceId> ids(int maximum) throws IOException {
            int count = count(maximum);
            List<ResourceId> values = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                values.add(id());
            }
            return values;
        }

        private List<BlockPos3i> positions(int maximum) throws IOException {
            int count = count(maximum);
            List<BlockPos3i> values = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                values.add(position());
            }
            return values;
        }

        private BlockPos3i position() throws IOException {
            return new BlockPos3i(input.readInt(), input.readInt(), input.readInt());
        }

        private ResourceId id() throws IOException {
            return ResourceId.parse(string(MAX_ID_CHARS));
        }

        private int count(int maximum) throws IOException {
            int value = input.readInt();
            if (value < 0 || value > maximum) {
                throw new IOException("Collection count is outside 0.." + maximum);
            }
            return value;
        }

        private boolean booleanValue() throws IOException {
            int value = input.readUnsignedByte();
            if (value != 0 && value != 1) {
                throw new IOException("Boolean byte is invalid: " + value);
            }
            return value == 1;
        }

        private OptionalLong optionalLong() throws IOException {
            return booleanValue() ? OptionalLong.of(input.readLong()) : OptionalLong.empty();
        }

        private <T> Optional<T> optional(IoSupplier<T> supplier) throws IOException {
            return booleanValue() ? Optional.of(supplier.get()) : Optional.empty();
        }

        private <E extends Enum<E>> E enumeration(Class<E> type) throws IOException {
            String value = string(MAX_ENUM_CHARS);
            try {
                return Enum.valueOf(type, value);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Unknown " + type.getSimpleName() + ": " + value, exception);
            }
        }

        private String string(int maximumCharacters) throws IOException {
            int byteLength = input.readInt();
            int maximumBytes = Math.min(MAX_ENCODED_BYTES, maximumCharacters * 4);
            if (byteLength < 0 || byteLength > maximumBytes) {
                throw new IOException("Encoded string length is invalid");
            }
            byte[] bytes = input.readNBytes(byteLength);
            if (bytes.length != byteLength) {
                throw new IOException("Encoded string is truncated");
            }
            String value = new String(bytes, StandardCharsets.UTF_8);
            if (value.length() > maximumCharacters
                    || !Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
                throw new IOException("Encoded string is not canonical bounded UTF-8");
            }
            return value;
        }
    }

    @FunctionalInterface
    private interface IoConsumer<T> {
        void accept(T value) throws IOException;
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static final class LimitedOutputStream extends OutputStream {
        private final int maximum;
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        private LimitedOutputStream(int maximum) {
            this.maximum = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, values.length);
            requireCapacity(length);
            delegate.write(values, offset, length);
        }

        private void requireCapacity(int additional) throws IOException {
            if (additional < 0 || delegate.size() > maximum - additional) {
                throw new IOException("Encoded recovery payload exceeds " + maximum + " bytes");
            }
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }
}
