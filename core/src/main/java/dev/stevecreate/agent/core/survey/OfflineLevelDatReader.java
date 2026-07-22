package dev.stevecreate.agent.core.survey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Guard-backed gzip level.dat reader for exact supported DataVersion values. */
public final class OfflineLevelDatReader implements FormalLevelMetadataReader {
    private final FormalWorldReadOnlyGuard guard;
    private final NbtReadLimits limits;
    private final OfflineNbtParser parser = new OfflineNbtParser();

    public OfflineLevelDatReader(FormalWorldReadOnlyGuard guard, NbtReadLimits limits) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public FormalLevelMetadata read(Path levelDat) throws IOException {
        return guard.read(levelDat, FormalReadIntent.LEVEL_METADATA, channel -> {
            long size = channel.size();
            if (size <= 0 || size > limits.maxCompressedNbtBytes()) {
                throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_TOO_LARGE,
                        "level.dat compressed size is outside configured bounds");
            }
            byte[] compressed = new byte[(int) size];
            readFully(channel, ByteBuffer.wrap(compressed), 0);
            byte[] decoded = OfflineNbtCompression.decompress(compressed, 1, limits);
            OfflineNbtParser.CompoundNode root = parser.parse(decoded, limits);
            OfflineNbtParser.CompoundNode data = root.compound("Data");
            int dataVersion = Math.toIntExact(data.number("DataVersion"));
            if (!limits.supportedDataVersions().contains(dataVersion)) {
                throw new FormalNbtException(FormalSurveyFailureCode.WORLD_DATA_VERSION_UNSUPPORTED,
                        "unsupported DataVersion " + dataVersion);
            }
            OfflineNbtParser.CompoundNode version = data.compound("Version");
            OfflineNbtParser.CompoundNode worldGen = data.compound("WorldGenSettings");
            OfflineNbtParser.CompoundNode dimensions = worldGen.compound("dimensions");
            List<String> dimensionIds = dimensions.values().keySet().stream().sorted().toList();
            if (dimensionIds.isEmpty()) {
                throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_CORRUPT,
                        "level.dat has no dimensions");
            }
            return new FormalLevelMetadata(
                    data.string("LevelName"),
                    dataVersion,
                    data.number("LastPlayed"),
                    version.string("Name"),
                    dimensionIds,
                    hash(compressed));
        });
    }

    static void readFully(java.nio.channels.FileChannel channel, ByteBuffer target, long position)
            throws IOException {
        while (target.hasRemaining()) {
            int count = channel.read(target, position);
            if (count < 0) throw new IOException("unexpected end of file");
            if (count == 0) throw new IOException("zero-progress file read");
            position += count;
        }
    }

    static String hash(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
