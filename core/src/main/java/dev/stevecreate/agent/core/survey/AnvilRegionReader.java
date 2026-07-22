package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline Anvil reader. One invalid chunk becomes a typed failure and does not abort its siblings. */
public final class AnvilRegionReader {
    private static final int SECTOR_BYTES = 4_096;
    private static final int HEADER_BYTES = 8_192;
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final String NOT_APPLICABLE = "not-applicable";
    private final OfflineNbtParser parser = new OfflineNbtParser();

    public OfflineRegionSnapshot read(
            FormalWorldReadOnlyGuard guard,
            Path regionFile,
            String worldIdentity,
            ResourceId dimension,
            long parseGeneration,
            NbtReadLimits limits) throws IOException {
        return read(guard, regionFile, worldIdentity, dimension, parseGeneration, limits, null);
    }

    public OfflineRegionSnapshot read(
            FormalWorldReadOnlyGuard guard,
            Path regionFile,
            String worldIdentity,
            ResourceId dimension,
            long parseGeneration,
            NbtReadLimits limits,
            String expectedFileFingerprint) throws IOException {
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(regionFile, "regionFile");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(limits, "limits");
        if (expectedFileFingerprint != null && !expectedFileFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedFileFingerprint is invalid");
        }
        Matcher matcher = REGION_NAME.matcher(regionFile.getFileName().toString());
        if (!matcher.matches()) {
            return headerFailure(guard, regionFile, worldIdentity, dimension, 0, 0, parseGeneration,
                    "region filename is not r.<x>.<z>.mca");
        }
        int regionX;
        int regionZ;
        try {
            regionX = Integer.parseInt(matcher.group(1));
            regionZ = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException exception) {
            return headerFailure(guard, regionFile, worldIdentity, dimension, 0, 0, parseGeneration,
                    "region coordinates are outside integer bounds");
        }
        if (regionX < Math.floorDiv(Integer.MIN_VALUE, 32) || regionX > Math.floorDiv(Integer.MAX_VALUE, 32)
                || regionZ < Math.floorDiv(Integer.MIN_VALUE, 32) || regionZ > Math.floorDiv(Integer.MAX_VALUE, 32)) {
            return headerFailure(guard, regionFile, worldIdentity, dimension, regionX, regionZ, parseGeneration,
                    "region coordinates cannot produce bounded chunk coordinates");
        }
        int finalRegionX = regionX;
        int finalRegionZ = regionZ;
        return guard.read(regionFile, FormalReadIntent.CHUNK_PAYLOAD, channel ->
                readChannel(guard, channel, regionFile, worldIdentity, dimension,
                        finalRegionX, finalRegionZ, parseGeneration, limits, expectedFileFingerprint));
    }

    private OfflineRegionSnapshot readChannel(
            FormalWorldReadOnlyGuard guard,
            FileChannel channel,
            Path regionFile,
            String worldIdentity,
            ResourceId dimension,
            int regionX,
            int regionZ,
            long parseGeneration,
            NbtReadLimits limits,
            String expectedFileFingerprint) throws IOException {
        long fileSize = channel.size();
        String relative = guard.relative(regionFile.toRealPath());
        if (fileSize < HEADER_BYTES || fileSize > limits.maxRegionBytes()) {
            return new OfflineRegionSnapshot(regionX, regionZ, relative, "0".repeat(64), parseGeneration, List.of(),
                    List.of(failure(FormalSurveyFailureCode.REGION_HEADER_INVALID, worldIdentity,
                            dimension, regionX, regionZ, null, "0".repeat(64), relative,
                            "region size is outside configured bounds")));
        }
        byte[] headerBytes = new byte[HEADER_BYTES];
        OfflineLevelDatReader.readFully(channel, ByteBuffer.wrap(headerBytes), 0);
        String fileHash = expectedFileFingerprint == null ? hashChannel(channel, fileSize) : expectedFileFingerprint;
        ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN);
        int sectorCount = Math.toIntExact((fileSize + SECTOR_BYTES - 1) / SECTOR_BYTES);
        BitSet claimed = new BitSet(sectorCount);
        claimed.set(0, Math.min(2, sectorCount));
        List<OfflineChunkSnapshot> chunks = new ArrayList<>();
        List<FormalSurveyFailure> failures = new ArrayList<>();
        for (int index = 0; index < 1_024; index++) {
            int location = header.getInt(index * Integer.BYTES);
            if (location == 0) continue;
            int offset = location >>> 8;
            int sectors = location & 0xff;
            int chunkX = Math.addExact(Math.multiplyExact(regionX, 32), index & 31);
            int chunkZ = Math.addExact(Math.multiplyExact(regionZ, 32), index >>> 5);
            if (offset < 2 || sectors <= 0 || offset > sectorCount || offset + sectors > sectorCount
                    || claimed.nextSetBit(offset) >= 0 && claimed.nextSetBit(offset) < offset + sectors) {
                failures.add(failure(FormalSurveyFailureCode.REGION_CHUNK_OFFSET_INVALID, worldIdentity,
                        dimension, regionX, regionZ, new int[] {chunkX, chunkZ}, fileHash, relative,
                        "chunk sector offset/count is invalid or overlaps another chunk"));
                continue;
            }
            claimed.set(offset, offset + sectors);
            long position = (long) offset * SECTOR_BYTES;
            if (position + Integer.BYTES + 1 > fileSize) {
                failures.add(failure(FormalSurveyFailureCode.REGION_CHUNK_OFFSET_INVALID, worldIdentity,
                        dimension, regionX, regionZ, new int[] {chunkX, chunkZ}, fileHash, relative,
                        "chunk sector has no complete length/compression header"));
                continue;
            }
            ByteBuffer lengthBuffer = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
            OfflineLevelDatReader.readFully(channel, lengthBuffer, position);
            int length = lengthBuffer.flip().getInt();
            int maximumLength = sectors * SECTOR_BYTES - Integer.BYTES;
            if (length <= 1 || length > maximumLength || length - 1 > limits.maxCompressedNbtBytes()
                    || position + Integer.BYTES + (long) length > fileSize) {
                failures.add(failure(FormalSurveyFailureCode.REGION_CHUNK_OFFSET_INVALID, worldIdentity,
                        dimension, regionX, regionZ, new int[] {chunkX, chunkZ}, fileHash, relative,
                        "chunk compressed length is outside its allocated sectors or configured limit"));
                continue;
            }
            ByteBuffer compressionBuffer = ByteBuffer.allocate(1);
            OfflineLevelDatReader.readFully(channel, compressionBuffer, position + Integer.BYTES);
            int compression = compressionBuffer.array()[0] & 0xff;
            if ((compression & 0x80) != 0 || compression < 1 || compression > 3) {
                failures.add(failure(FormalSurveyFailureCode.CHUNK_COMPRESSION_UNSUPPORTED, worldIdentity,
                        dimension, regionX, regionZ, new int[] {chunkX, chunkZ}, fileHash, relative,
                        "chunk compression is unsupported: " + compression));
                continue;
            }
            byte[] compressed = new byte[length - 1];
            OfflineLevelDatReader.readFully(
                    channel, ByteBuffer.wrap(compressed), position + Integer.BYTES + 1);
            try {
                byte[] decoded = OfflineNbtCompression.decompress(compressed, compression, limits);
                OfflineNbtParser.CompoundNode root = parser.parse(decoded, limits);
                int dataVersion = Math.toIntExact(root.number("DataVersion"));
                if (!limits.supportedDataVersions().contains(dataVersion)) {
                    throw new FormalNbtException(FormalSurveyFailureCode.WORLD_DATA_VERSION_UNSUPPORTED,
                            "unsupported chunk DataVersion " + dataVersion);
                }
                Long storedX = root.optionalNumber("xPos");
                Long storedZ = root.optionalNumber("zPos");
                if (storedX != null && storedX != chunkX || storedZ != null && storedZ != chunkZ) {
                    throw new FormalNbtException(FormalSurveyFailureCode.CHUNK_NBT_CORRUPT,
                            "chunk coordinates do not match the region location table");
                }
                chunks.add(extractChunk(root, dataVersion, chunkX, chunkZ, compression,
                        compressed.length, decoded.length, parseGeneration));
            } catch (FormalNbtException exception) {
                failures.add(failure(exception.code(), worldIdentity, dimension, regionX, regionZ,
                        new int[] {chunkX, chunkZ}, fileHash, relative, exception.getMessage()));
            } catch (RuntimeException exception) {
                failures.add(failure(FormalSurveyFailureCode.CHUNK_NBT_CORRUPT, worldIdentity,
                        dimension, regionX, regionZ, new int[] {chunkX, chunkZ}, fileHash, relative,
                        "chunk extraction failed: " + exception.getClass().getSimpleName()));
            }
        }
        chunks.sort(Comparator.comparingInt(OfflineChunkSnapshot::chunkX)
                .thenComparingInt(OfflineChunkSnapshot::chunkZ));
        return new OfflineRegionSnapshot(
                regionX, regionZ, relative, fileHash, parseGeneration, chunks, failures);
    }

    private static String hashChannel(FileChannel channel, long fileSize) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1_024);
        long position = 0;
        while (position < fileSize) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), fileSize - position));
            int count = channel.read(buffer, position);
            if (count <= 0) throw new IOException("zero-progress region hash read");
            position += count;
            buffer.flip();
            digest.update(buffer);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static OfflineChunkSnapshot extractChunk(
            OfflineNbtParser.CompoundNode root,
            int dataVersion,
            int chunkX,
            int chunkZ,
            int compression,
            int compressedBytes,
            int decodedBytes,
            long parseGeneration) throws FormalNbtException {
        List<OfflineChunkSection> sections = new ArrayList<>();
        OfflineNbtParser.ListNode sectionList = root.optionalList("sections");
        if (sectionList == null) sectionList = root.optionalList("Sections");
        if (sectionList != null) {
            for (OfflineNbtParser.Node node : sectionList.values()) {
                if (!(node instanceof OfflineNbtParser.CompoundNode section)) {
                    throw new FormalNbtException(FormalSurveyFailureCode.BLOCK_PALETTE_UNSUPPORTED,
                            "section list contains a non-compound value");
                }
                int sectionY = Math.toIntExact(section.number("Y"));
                OfflineNbtParser.CompoundNode blockStates = section.optionalCompound("block_states");
                if (blockStates == null) continue;
                OfflineNbtParser.ListNode paletteList = blockStates.optionalList("palette");
                if (paletteList == null || paletteList.values().isEmpty() || paletteList.values().size() > 4_096) {
                    throw new FormalNbtException(FormalSurveyFailureCode.BLOCK_PALETTE_UNSUPPORTED,
                            "block-state palette is absent or invalid");
                }
                List<OfflineBlockState> palette = new ArrayList<>();
                for (OfflineNbtParser.Node paletteNode : paletteList.values()) {
                    if (!(paletteNode instanceof OfflineNbtParser.CompoundNode entry)) {
                        throw new FormalNbtException(FormalSurveyFailureCode.BLOCK_PALETTE_UNSUPPORTED,
                                "palette entry is not a compound");
                    }
                    ResourceId resource;
                    try {
                        resource = ResourceId.parse(entry.string("Name"));
                    } catch (IllegalArgumentException exception) {
                        throw new FormalNbtException(FormalSurveyFailureCode.BLOCK_PALETTE_UNSUPPORTED,
                                "palette resource id is invalid", exception);
                    }
                    Map<String, String> properties = new LinkedHashMap<>();
                    OfflineNbtParser.CompoundNode propertyNode = entry.optionalCompound("Properties");
                    if (propertyNode != null) {
                        for (Map.Entry<String, OfflineNbtParser.Node> property : propertyNode.values().entrySet()) {
                            if (!(property.getValue() instanceof OfflineNbtParser.StringNode value)) {
                                throw new FormalNbtException(FormalSurveyFailureCode.BLOCK_PALETTE_UNSUPPORTED,
                                        "block-state property is not a string");
                            }
                            properties.put(property.getKey(), value.value());
                        }
                    }
                    palette.add(new OfflineBlockState(resource, properties));
                }
                OfflineNbtParser.LongArrayNode packed = blockStates.optionalLongArray("data");
                long[] packedValues = packed == null ? new long[0] : packed.value();
                if (palette.size() > 1) {
                    int expectedLongs = OfflineChunkSection.expectedPackedLongs(palette.size());
                    if (packedValues.length != expectedLongs) {
                        throw new FormalNbtException(FormalSurveyFailureCode.BLOCK_PALETTE_UNSUPPORTED,
                                "packed block-state data length does not match the palette");
                    }
                } else if (packedValues.length != 0) {
                    throw new FormalNbtException(FormalSurveyFailureCode.BLOCK_PALETTE_UNSUPPORTED,
                            "single-entry palette must not carry packed block-state data");
                }
                sections.add(new OfflineChunkSection(sectionY, palette, packedValues));
            }
        }
        sections.sort(Comparator.comparingInt(OfflineChunkSection::sectionY));

        List<OfflineBlockEntity> blockEntities = new ArrayList<>();
        OfflineNbtParser.ListNode entities = root.optionalList("block_entities");
        if (entities == null) entities = root.optionalList("TileEntities");
        if (entities != null) {
            for (OfflineNbtParser.Node node : entities.values()) {
                if (!(node instanceof OfflineNbtParser.CompoundNode entity)) {
                    throw new FormalNbtException(FormalSurveyFailureCode.BLOCK_ENTITY_UNSUPPORTED,
                            "block entity list contains a non-compound value");
                }
                try {
                    OfflineBlockEntity blockEntity = new OfflineBlockEntity(
                            ResourceId.parse(entity.string("id")),
                            new BlockPos3i(Math.toIntExact(entity.number("x")),
                                    Math.toIntExact(entity.number("y")),
                                    Math.toIntExact(entity.number("z"))));
                    if (Math.floorDiv(blockEntity.position().x(), 16) != chunkX
                            || Math.floorDiv(blockEntity.position().z(), 16) != chunkZ) {
                        throw new FormalNbtException(FormalSurveyFailureCode.BLOCK_ENTITY_UNSUPPORTED,
                                "block entity position is outside its chunk");
                    }
                    blockEntities.add(blockEntity);
                } catch (IllegalArgumentException | ArithmeticException exception) {
                    throw new FormalNbtException(FormalSurveyFailureCode.BLOCK_ENTITY_UNSUPPORTED,
                            "block entity identity or position is invalid", exception);
                }
            }
        }
        return new OfflineChunkSnapshot(dataVersion, chunkX, chunkZ, compression,
                compressedBytes, decodedBytes, parseGeneration, sections, blockEntities);
    }

    private static OfflineRegionSnapshot headerFailure(
            FormalWorldReadOnlyGuard guard,
            Path regionFile,
            String worldIdentity,
            ResourceId dimension,
            int regionX,
            int regionZ,
            long parseGeneration,
            String reason) {
        String relative;
        try {
            relative = guard.relative(regionFile.toAbsolutePath().normalize());
        } catch (RuntimeException exception) {
            relative = regionFile.getFileName().toString();
        }
        return new OfflineRegionSnapshot(regionX, regionZ, relative, "0".repeat(64), parseGeneration, List.of(),
                List.of(failure(FormalSurveyFailureCode.REGION_HEADER_INVALID, worldIdentity,
                        dimension, regionX, regionZ, null, "0".repeat(64), relative, reason)));
    }

    private static FormalSurveyFailure failure(
            FormalSurveyFailureCode code,
            String worldIdentity,
            ResourceId dimension,
            int regionX,
            int regionZ,
            int[] chunk,
            String fingerprint,
            String relativePath,
            String reason) {
        return new FormalSurveyFailure(code,
                chunk == null ? FormalSurveyStage.REGION_METADATA : FormalSurveyStage.CHUNK_PARSE,
                worldIdentity, relativePath, dimension.toString(), regionX + "," + regionZ,
                chunk == null ? NOT_APPLICABLE : chunk[0] + "," + chunk[1],
                fingerprint, "parser-limits-enforced", List.of("relativePath=" + relativePath),
                reason, "Skip the failed chunk, retain evidence, and continue only within survey budget");
    }
}
