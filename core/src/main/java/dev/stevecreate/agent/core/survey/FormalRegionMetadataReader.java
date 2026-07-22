package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only the Anvil header and file metadata through the formal read-only guard. */
final class FormalRegionMetadataReader {
    private static final int HEADER_BYTES = 8_192;
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    Optional<FormalRegionSource> read(FormalWorldReadOnlyGuard guard, Path path) throws IOException {
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(path, "path");
        String relative = guard.relative(path);
        Optional<ResourceId> dimension = dimension(relative);
        Matcher name = REGION_FILE.matcher(path.getFileName().toString());
        if (dimension.isEmpty() || !name.matches()) return Optional.empty();
        int regionX;
        int regionZ;
        try {
            regionX = Integer.parseInt(name.group(1));
            regionZ = Integer.parseInt(name.group(2));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
        FormalFileMetadata metadata = guard.fingerprintMetadata(path);
        HeaderFacts header = guard.read(path, FormalReadIntent.REGION_METADATA, channel -> {
            long size = channel.size();
            if (size < HEADER_BYTES) return new HeaderFacts(0, 0);
            ByteBuffer bytes = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
            OfflineLevelDatReader.readFully(channel, bytes, 0);
            bytes.flip();
            int populated = 0;
            for (int index = 0; index < 1_024; index++) {
                if (bytes.getInt(index * Integer.BYTES) != 0) populated++;
            }
            long activity = 0;
            for (int index = 0; index < 1_024; index++) {
                activity = Math.max(activity, Integer.toUnsignedLong(bytes.getInt(4_096 + index * Integer.BYTES)));
            }
            return new HeaderFacts(populated, Math.multiplyExact(activity, 1_000));
        });
        long expanded = metadata.sizeBytes() > Long.MAX_VALUE / 4
                ? Long.MAX_VALUE : metadata.sizeBytes() * 4;
        long retainedEstimate = Math.min(512L * 1_024 * 1_024,
                Math.max(metadata.sizeBytes(), expanded));
        SurveyRegionMetadata surveyMetadata = new SurveyRegionMetadata(
                dimension.orElseThrow(), regionX, regionZ, relative, metadata.sizeBytes(),
                header.populatedChunks(), retainedEstimate, metadata.lastModifiedEpochMillis(),
                header.activityTimestamp(), 0);
        return Optional.of(new FormalRegionSource(path, surveyMetadata));
    }

    static Optional<ResourceId> dimension(String relative) {
        if (relative.matches("region/r\\.-?\\d+\\.-?\\d+\\.mca")) {
            return Optional.of(ResourceId.parse("minecraft:overworld"));
        }
        if (relative.matches("DIM-1/region/r\\.-?\\d+\\.-?\\d+\\.mca")) {
            return Optional.of(ResourceId.parse("minecraft:the_nether"));
        }
        if (relative.matches("DIM1/region/r\\.-?\\d+\\.-?\\d+\\.mca")) {
            return Optional.of(ResourceId.parse("minecraft:the_end"));
        }
        String prefix = "dimensions/";
        String marker = "/region/";
        if (!relative.startsWith(prefix) || !relative.contains(marker)) return Optional.empty();
        int markerIndex = relative.indexOf(marker, prefix.length());
        String[] identity = relative.substring(prefix.length(), markerIndex).split("/", 2);
        if (identity.length != 2 || identity[0].isBlank() || identity[1].isBlank()) return Optional.empty();
        try {
            return Optional.of(ResourceId.parse(identity[0] + ":" + identity[1]));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private record HeaderFacts(int populatedChunks, long activityTimestamp) {}
}
