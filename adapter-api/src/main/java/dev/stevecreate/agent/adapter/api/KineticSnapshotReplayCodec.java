package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** Canonical bounded replay encoding for deterministic JVM and server-fixture verification. */
public final class KineticSnapshotReplayCodec {
    private static final int MAGIC = 0x5349414b;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENCODED_LENGTH = 65_536;
    private static final int MAX_MOD_VERSIONS = 32;
    private static final int MAX_TEXT_BYTES = 1_024;

    private KineticSnapshotReplayCodec() {
    }

    public static String encode(KineticSnapshot snapshot) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeLong(snapshot.gameTick());
                writeRuntime(output, snapshot.runtime());
                writeBoundedUtf(output, snapshot.dimensionId().toString());
                writeBoundedUtf(output, snapshot.blockId().toString());
                output.writeInt(snapshot.position().x());
                output.writeInt(snapshot.position().y());
                output.writeInt(snapshot.position().z());
                output.writeBoolean(snapshot.networkId().isPresent());
                if (snapshot.networkId().isPresent()) {
                    output.writeLong(snapshot.networkId().getAsLong());
                }
                output.writeInt(snapshot.networkSize());
                output.writeDouble(snapshot.speedRpm());
                output.writeByte(directionCode(snapshot.rotationDirection()));
                output.writeBoolean(snapshot.stressEnabled());
                output.writeDouble(snapshot.stressCapacity());
                output.writeDouble(snapshot.stressLoad());
                output.writeBoolean(snapshot.overstressed());
            }
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            if (encoded.length() > MAX_ENCODED_LENGTH) {
                throw new IllegalArgumentException("Replay payload exceeds the size limit");
            }
            return encoded;
        } catch (IOException exception) {
            throw new IllegalStateException("Unexpected in-memory replay encoding failure", exception);
        }
    }

    public static KineticSnapshot decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("Replay payload is blank or exceeds the size limit");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                    throw new IllegalArgumentException("Unsupported kinetic replay format");
                }
                long gameTick = input.readLong();
                RuntimeFingerprint runtime = readRuntime(input);
                ResourceId dimensionId = ResourceId.parse(readBoundedUtf(input));
                ResourceId blockId = ResourceId.parse(readBoundedUtf(input));
                BlockPos3i position = new BlockPos3i(input.readInt(), input.readInt(), input.readInt());
                OptionalLong networkId = input.readBoolean()
                        ? OptionalLong.of(input.readLong())
                        : OptionalLong.empty();
                int networkSize = input.readInt();
                double speedRpm = input.readDouble();
                int directionOrdinal = input.readUnsignedByte();
                KineticRotationDirection direction = decodeDirection(directionOrdinal);
                boolean stressEnabled = input.readBoolean();
                double stressCapacity = input.readDouble();
                double stressLoad = input.readDouble();
                boolean overstressed = input.readBoolean();
                if (input.read() != -1) {
                    throw new IllegalArgumentException("Trailing data in kinetic replay payload");
                }
                KineticSnapshot snapshot = new KineticSnapshot(
                        gameTick,
                        runtime,
                        dimensionId,
                        blockId,
                        position,
                        networkId,
                        networkSize,
                        speedRpm,
                        direction,
                        stressEnabled,
                        stressCapacity,
                        stressLoad,
                        overstressed);
                if (!encode(snapshot).equals(encoded)) {
                    throw new IllegalArgumentException("Non-canonical kinetic replay payload");
                }
                return snapshot;
            }
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Truncated kinetic replay payload", exception);
        } catch (IOException | IllegalArgumentException exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("Invalid kinetic replay payload", exception);
        }
    }

    private static int directionCode(KineticRotationDirection direction) {
        return switch (direction) {
            case STATIONARY -> 0;
            case POSITIVE -> 1;
            case NEGATIVE -> 2;
        };
    }

    private static KineticRotationDirection decodeDirection(int code) {
        return switch (code) {
            case 0 -> KineticRotationDirection.STATIONARY;
            case 1 -> KineticRotationDirection.POSITIVE;
            case 2 -> KineticRotationDirection.NEGATIVE;
            default -> throw new IllegalArgumentException("Invalid kinetic rotation direction");
        };
    }

    private static void writeRuntime(DataOutputStream output, RuntimeFingerprint runtime) throws IOException {
        writeBoundedUtf(output, runtime.minecraftVersion());
        writeBoundedUtf(output, runtime.loader());
        writeBoundedUtf(output, runtime.loaderVersion());
        List<Map.Entry<String, String>> versions = new ArrayList<>(runtime.industrialModVersions().entrySet());
        versions.sort(Comparator.comparing(Map.Entry::getKey));
        if (versions.size() > MAX_MOD_VERSIONS) {
            throw new IllegalArgumentException("Too many mod versions in runtime fingerprint");
        }
        output.writeInt(versions.size());
        for (Map.Entry<String, String> entry : versions) {
            writeBoundedUtf(output, entry.getKey());
            writeBoundedUtf(output, entry.getValue());
        }
        writeBoundedUtf(output, runtime.adapterId());
        output.writeInt(runtime.normalizationSchemaVersion());
    }

    private static RuntimeFingerprint readRuntime(DataInputStream input) throws IOException {
        String minecraftVersion = readBoundedUtf(input);
        String loader = readBoundedUtf(input);
        String loaderVersion = readBoundedUtf(input);
        int versionCount = input.readInt();
        if (versionCount < 0 || versionCount > MAX_MOD_VERSIONS) {
            throw new IllegalArgumentException("Invalid mod-version count in kinetic replay");
        }
        Map<String, String> versions = new LinkedHashMap<>();
        for (int index = 0; index < versionCount; index++) {
            String previous = versions.put(readBoundedUtf(input), readBoundedUtf(input));
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate mod id in kinetic replay");
            }
        }
        String adapterId = readBoundedUtf(input);
        int schemaVersion = input.readInt();
        return new RuntimeFingerprint(
                minecraftVersion,
                loader,
                loaderVersion,
                versions,
                adapterId,
                schemaVersion);
    }

    private static void writeBoundedUtf(DataOutputStream output, String value) throws IOException {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Replay text field exceeds the size limit");
        }
        output.writeUTF(value);
    }

    private static String readBoundedUtf(DataInputStream input) throws IOException {
        String value = input.readUTF();
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Replay text field exceeds the size limit");
        }
        return value;
    }
}
