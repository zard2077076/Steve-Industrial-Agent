package dev.stevecreate.agent.core.planning;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;

/** Versioned canonical bounded binary codec for a production goal. */
public final class ProductionGoalCodec {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENCODED_BYTES = 1_024 * 1_024;

    private static final int MAGIC = 0x53494750; // SIGP
    private static final int FRAME_OVERHEAD = Integer.BYTES + Short.BYTES
            + Integer.BYTES + Long.BYTES;
    private static final int MAX_ID_BYTES = 512;
    private static final int MAX_MOD_ID_BYTES = 64;
    private static final int MAX_ENUM_BYTES = 64;

    private ProductionGoalCodec() {
    }

    public static byte[] encode(ProductionGoal goal) {
        Objects.requireNonNull(goal, "goal");
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
                writeGoal(output, goal);
            }
            byte[] payload = payloadBytes.toByteArray();
            if (payload.length > MAX_ENCODED_BYTES) {
                throw new IOException("Production goal payload exceeds the codec bound");
            }
            CRC32 checksum = new CRC32();
            checksum.update(payload);
            ByteArrayOutputStream frameBytes = new ByteArrayOutputStream(payload.length + FRAME_OVERHEAD);
            try (DataOutputStream frame = new DataOutputStream(frameBytes)) {
                frame.writeInt(MAGIC);
                frame.writeShort(FORMAT_VERSION);
                frame.writeInt(payload.length);
                frame.write(payload);
                frame.writeLong(checksum.getValue());
            }
            return frameBytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Production goal violates codec bounds", exception);
        }
    }

    public static ProductionGoal decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < FRAME_OVERHEAD
                || encoded.length > MAX_ENCODED_BYTES + FRAME_OVERHEAD) {
            throw new IllegalArgumentException("Production goal frame length is invalid");
        }
        try (DataInputStream frame = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (frame.readInt() != MAGIC) {
                throw new IOException("Production goal magic is invalid");
            }
            int version = frame.readUnsignedShort();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported production goal version: " + version);
            }
            int payloadLength = frame.readInt();
            if (payloadLength < 0 || payloadLength > MAX_ENCODED_BYTES
                    || payloadLength != encoded.length - FRAME_OVERHEAD) {
                throw new IOException("Production goal payload length is invalid");
            }
            byte[] payload = frame.readNBytes(payloadLength);
            if (payload.length != payloadLength) {
                throw new IOException("Production goal payload is truncated");
            }
            long expectedChecksum = frame.readLong();
            if (frame.available() != 0) {
                throw new IOException("Production goal has trailing frame bytes");
            }
            CRC32 checksum = new CRC32();
            checksum.update(payload);
            if (checksum.getValue() != expectedChecksum) {
                throw new IOException("Production goal checksum does not match");
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                ProductionGoal goal = readGoal(input);
                if (input.available() != 0) {
                    throw new IOException("Production goal has trailing payload bytes");
                }
                return goal;
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Invalid production goal", exception);
        }
    }

    private static void writeGoal(DataOutputStream output, ProductionGoal goal) throws IOException {
        writeString(output, goal.target().toString(), MAX_ID_BYTES);
        writeString(output, goal.targetResourceType().serializedName(), MAX_ENUM_BYTES);
        output.writeLong(goal.quantity());
        writeStrings(output, goal.allowedModIds(), ProductionGoal.MAX_MOD_CONSTRAINTS);
        writeStrings(output, goal.forbiddenModIds(), ProductionGoal.MAX_MOD_CONSTRAINTS);
        output.writeBoolean(goal.maximumProcessingDepth().isPresent());
        if (goal.maximumProcessingDepth().isPresent()) {
            output.writeInt(goal.maximumProcessingDepth().orElseThrow());
        }
        writeIds(output, goal.materialConstraints().forbiddenResources(), MaterialConstraints.MAX_CONSTRAINTS);
        writeQuantities(output, goal.materialConstraints().maximumConsumption(), MaterialConstraints.MAX_CONSTRAINTS);
        output.writeInt(goal.strategyPreferences().size());
        for (PlanningStrategyPreference preference : goal.strategyPreferences()) {
            writeString(output, preference.serializedName(), MAX_ENUM_BYTES);
        }
        writeQuantities(output, goal.ownedResources(), ProductionGoal.MAX_OWNED_RESOURCES);
    }

    private static ProductionGoal readGoal(DataInputStream input) throws IOException {
        ResourceId target = ResourceId.parse(readString(input, MAX_ID_BYTES));
        GenericResourceType type = GenericResourceType.fromSerializedName(
                readString(input, MAX_ENUM_BYTES));
        long quantity = input.readLong();
        Set<String> allowed = readStrings(input, ProductionGoal.MAX_MOD_CONSTRAINTS);
        Set<String> forbidden = readStrings(input, ProductionGoal.MAX_MOD_CONSTRAINTS);
        Optional<Integer> depth = input.readBoolean()
                ? Optional.of(input.readInt())
                : Optional.empty();
        MaterialConstraints constraints = new MaterialConstraints(
                readIds(input, MaterialConstraints.MAX_CONSTRAINTS),
                readQuantities(input, MaterialConstraints.MAX_CONSTRAINTS));
        int preferenceCount = readCount(input, PlanningStrategyPreference.values().length);
        List<PlanningStrategyPreference> preferences = new ArrayList<>(preferenceCount);
        for (int index = 0; index < preferenceCount; index++) {
            preferences.add(PlanningStrategyPreference.fromSerializedName(
                    readString(input, MAX_ENUM_BYTES)));
        }
        Map<ResourceId, Long> owned = readQuantities(input, ProductionGoal.MAX_OWNED_RESOURCES);
        return new ProductionGoal(
                target, type, quantity, allowed, forbidden, depth,
                constraints, preferences, owned);
    }

    private static void writeStrings(DataOutputStream output, Set<String> values, int maximum)
            throws IOException {
        if (values.size() > maximum) {
            throw new IOException("String set exceeds codec bound");
        }
        output.writeInt(values.size());
        for (String value : values) {
            writeString(output, value, MAX_MOD_ID_BYTES);
        }
    }

    private static Set<String> readStrings(DataInputStream input, int maximum) throws IOException {
        int count = readCount(input, maximum);
        Set<String> values = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            String value = readString(input, MAX_MOD_ID_BYTES);
            if (!values.add(value)) {
                throw new IOException("Duplicate string in canonical set: " + value);
            }
        }
        return values;
    }

    private static void writeIds(DataOutputStream output, Set<ResourceId> values, int maximum)
            throws IOException {
        if (values.size() > maximum) {
            throw new IOException("Resource set exceeds codec bound");
        }
        output.writeInt(values.size());
        for (ResourceId value : values) {
            writeString(output, value.toString(), MAX_ID_BYTES);
        }
    }

    private static Set<ResourceId> readIds(DataInputStream input, int maximum) throws IOException {
        int count = readCount(input, maximum);
        Set<ResourceId> values = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            ResourceId value = ResourceId.parse(readString(input, MAX_ID_BYTES));
            if (!values.add(value)) {
                throw new IOException("Duplicate resource in canonical set: " + value);
            }
        }
        return values;
    }

    private static void writeQuantities(
            DataOutputStream output,
            Map<ResourceId, Long> values,
            int maximum) throws IOException {
        if (values.size() > maximum) {
            throw new IOException("Resource quantity map exceeds codec bound");
        }
        output.writeInt(values.size());
        for (Map.Entry<ResourceId, Long> entry : values.entrySet()) {
            writeString(output, entry.getKey().toString(), MAX_ID_BYTES);
            output.writeLong(entry.getValue());
        }
    }

    private static Map<ResourceId, Long> readQuantities(DataInputStream input, int maximum)
            throws IOException {
        int count = readCount(input, maximum);
        Map<ResourceId, Long> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            ResourceId resource = ResourceId.parse(readString(input, MAX_ID_BYTES));
            long quantity = input.readLong();
            if (values.putIfAbsent(resource, quantity) != null) {
                throw new IOException("Duplicate resource in canonical map: " + resource);
            }
        }
        return values;
    }

    private static int readCount(DataInputStream input, int maximum) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IOException("Collection count exceeds codec bound: " + count);
        }
        return count;
    }

    private static void writeString(DataOutputStream output, String value, int maximumBytes)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maximumBytes) {
            throw new IOException("String length violates codec bound");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > maximumBytes) {
            throw new IOException("String length violates codec bound");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("String is truncated");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
