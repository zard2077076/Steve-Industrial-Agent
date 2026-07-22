package dev.stevecreate.agent.core.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.stevecreate.agent.core.model.ResourceId;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineAnvilNbtParserTest {
    @TempDir Path temp;

    @Test
    void guardedGzipLevelDatExtractsExact1201IdentityAndDimensions() throws Exception {
        Fixture fixture = fixture();
        Path level = fixture.world().resolve("level.dat");
        byte[] compressed = gzip(levelNbt(3_465));
        Files.write(level, compressed);
        OfflineLevelDatReader reader = new OfflineLevelDatReader(fixture.guard(), limits());

        FormalLevelMetadata metadata = reader.read(level);

        assertThat(metadata.levelName()).isEqualTo("Formal Test World");
        assertThat(metadata.dataVersion()).isEqualTo(3_465);
        assertThat(metadata.lastPlayedEpochMillis()).isEqualTo(1_234_567_890L);
        assertThat(metadata.versionName()).isEqualTo("1.20.1");
        assertThat(metadata.dimensions()).containsExactly(
                "minecraft:overworld", "minecraft:the_end", "minecraft:the_nether");
        assertThat(metadata.levelDatSha256()).isEqualTo(OfflineLevelDatReader.hash(compressed));
        assertThat(Files.readString(Path.of(fixture.guard().auditFileIdentity())))
                .contains("LEVEL_METADATA", "READ_COMPLETE");
    }

    @Test
    void parsesAnvilHeaderZlibChunkPalettePackedDataAndRedactedBlockEntity() throws Exception {
        Fixture fixture = fixture();
        Path region = Files.createDirectories(fixture.world().resolve("region")).resolve("r.0.0.mca");
        byte[] chunkNbt = chunkNbt(0, 0);
        writeRegion(region, Map.of(0, new ChunkPayload(2, deflate(chunkNbt))));

        OfflineRegionSnapshot snapshot = new AnvilRegionReader().read(
                fixture.guard(), region, "world:" + "a".repeat(64),
                ResourceId.parse("minecraft:overworld"), 1, limits());

        assertThat(snapshot.failures()).isEmpty();
        assertThat(snapshot.chunks()).hasSize(1);
        OfflineChunkSnapshot chunk = snapshot.chunks().get(0);
        assertThat(chunk.dataVersion()).isEqualTo(3_465);
        assertThat(chunk.sections()).hasSize(1);
        assertThat(chunk.sections().get(0).palette()).containsExactly(
                new OfflineBlockState(ResourceId.parse("create:millstone"), Map.of("facing", "north")),
                new OfflineBlockState(ResourceId.parse("minecraft:air"), Map.of()));
        assertThat(chunk.sections().get(0).packedBlockStates()).hasSize(256).startsWith(1L);
        assertThat(chunk.sections().get(0).blockStateAt(0, 0, 0).resourceId())
                .isEqualTo(ResourceId.parse("minecraft:air"));
        assertThat(chunk.blockEntities()).containsExactly(
                new OfflineBlockEntity(ResourceId.parse("minecraft:chest"),
                        new dev.stevecreate.agent.core.model.BlockPos3i(4, 64, 5)));
        OfflineNbtParser.CompoundNode parsed = new OfflineNbtParser().parse(chunkNbt, limits());
        OfflineNbtParser.CompoundNode retainedEntity = (OfflineNbtParser.CompoundNode)
                parsed.optionalList("block_entities").values().get(0);
        assertThat(retainedEntity.values()).containsOnlyKeys("id", "x", "y", "z");
        assertThat(Arrays.stream(OfflineBlockEntity.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase()))
                .noneMatch(name -> name.contains("item") || name.contains("content") || name.contains("inventory"));
    }

    @Test
    void unsupportedOrCorruptChunkDoesNotAbortValidSibling() throws Exception {
        Fixture fixture = fixture();
        Path region = Files.createDirectories(fixture.world().resolve("region")).resolve("r.0.0.mca");
        Map<Integer, ChunkPayload> chunks = new LinkedHashMap<>();
        chunks.put(0, new ChunkPayload(99, new byte[] {1, 2, 3}));
        chunks.put(1, new ChunkPayload(2, deflate(chunkNbt(1, 0))));
        chunks.put(2, new ChunkPayload(3, chunkNbt(2, 0)));
        chunks.put(3, new ChunkPayload(1, gzip(chunkNbt(3, 0))));
        writeRegion(region, chunks);

        OfflineRegionSnapshot snapshot = new AnvilRegionReader().read(
                fixture.guard(), region, "world:" + "a".repeat(64),
                ResourceId.parse("minecraft:overworld"), 2, limits());

        assertThat(snapshot.chunks()).extracting(OfflineChunkSnapshot::chunkX).containsExactly(1, 2, 3);
        assertThat(snapshot.chunks()).extracting(OfflineChunkSnapshot::compressionType)
                .containsExactly(2, 3, 1);
        assertThat(snapshot.failures()).extracting(FormalSurveyFailure::code)
                .containsExactly(FormalSurveyFailureCode.CHUNK_COMPRESSION_UNSUPPORTED);
        assertThat(snapshot.failures().get(0).chunk()).isEqualTo("0,0");
    }

    @Test
    void acceptsMinecraftNonCrossingPackedStorageForPaletteWiderThanFourBits() throws Exception {
        Fixture fixture = fixture();
        Path region = Files.createDirectories(fixture.world().resolve("region")).resolve("r.0.0.mca");
        writeRegion(region, Map.of(0, new ChunkPayload(2, deflate(widePaletteChunkNbt()))));

        OfflineRegionSnapshot snapshot = new AnvilRegionReader().read(
                fixture.guard(), region, "world:" + "a".repeat(64),
                ResourceId.parse("minecraft:overworld"), 4, limits());

        assertThat(snapshot.failures()).isEmpty();
        OfflineChunkSection section = snapshot.chunks().get(0).sections().get(0);
        assertThat(section.palette()).hasSize(17);
        assertThat(section.packedBlockStates()).hasSize(342);
        assertThat(section.blockStateAt(0, 0, 0).resourceId()).isEqualTo(ResourceId.parse("test:block16"));
        assertThat(section.blockStateAt(1, 0, 0).resourceId()).isEqualTo(ResourceId.parse("test:block0"));
    }

    @Test
    void invalidSectorOffsetIsTypedAndValidSiblingStillParses() throws Exception {
        Fixture fixture = fixture();
        Path region = Files.createDirectories(fixture.world().resolve("region")).resolve("r.0.0.mca");
        byte[] valid = deflate(chunkNbt(1, 0));
        byte[] file = new byte[3 * 4_096];
        ByteBuffer header = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
        header.putInt(0, (1 << 8) | 1);
        header.putInt(4, (2 << 8) | 1);
        writeChunkSector(file, 2, 2, valid);
        Files.write(region, file);

        OfflineRegionSnapshot snapshot = new AnvilRegionReader().read(
                fixture.guard(), region, "world:" + "a".repeat(64),
                ResourceId.parse("minecraft:overworld"), 3, limits());

        assertThat(snapshot.chunks()).extracting(OfflineChunkSnapshot::chunkX).containsExactly(1);
        assertThat(snapshot.failures()).extracting(FormalSurveyFailure::code)
                .containsExactly(FormalSurveyFailureCode.REGION_CHUNK_OFFSET_INVALID);
    }

    @Test
    void depthArrayDecompressionAndDataVersionLimitsFailTyped() throws Exception {
        NbtReadLimits strict = new NbtReadLimits(32 * 1_024, 1_024, 256, 3, 2, 8, 8, Set.of(3_465));
        OfflineNbtParser parser = new OfflineNbtParser();

        assertThatExceptionOfType(FormalNbtException.class)
                .isThrownBy(() -> parser.parse(deepNbt(5), strict))
                .satisfies(exception -> assertThat(exception.code())
                        .isEqualTo(FormalSurveyFailureCode.CHUNK_NBT_TOO_LARGE));
        assertThatExceptionOfType(FormalNbtException.class)
                .isThrownBy(() -> parser.parse(longArrayNbt(3), strict))
                .satisfies(exception -> assertThat(exception.code())
                        .isEqualTo(FormalSurveyFailureCode.CHUNK_NBT_TOO_LARGE));
        assertThatExceptionOfType(FormalNbtException.class)
                .isThrownBy(() -> parser.parse(new byte[] {10, 0}, strict))
                .satisfies(exception -> assertThat(exception.code())
                        .isEqualTo(FormalSurveyFailureCode.CHUNK_NBT_CORRUPT));
        assertThatExceptionOfType(FormalNbtException.class)
                .isThrownBy(() -> OfflineNbtCompression.decompress(new byte[300], 3, strict))
                .satisfies(exception -> assertThat(exception.code())
                        .isEqualTo(FormalSurveyFailureCode.CHUNK_NBT_TOO_LARGE));
        assertThatExceptionOfType(FormalNbtException.class)
                .isThrownBy(() -> OfflineNbtCompression.decompress(new byte[0], 4, strict))
                .satisfies(exception -> assertThat(exception.code())
                        .isEqualTo(FormalSurveyFailureCode.CHUNK_COMPRESSION_UNSUPPORTED));

        Fixture fixture = fixture();
        Path level = fixture.world().resolve("level.dat");
        Files.write(level, gzip(levelNbt(3_464)));
        assertThatExceptionOfType(FormalNbtException.class)
                .isThrownBy(() -> new OfflineLevelDatReader(fixture.guard(), limits()).read(level))
                .satisfies(exception -> assertThat(exception.code())
                        .isEqualTo(FormalSurveyFailureCode.WORLD_DATA_VERSION_UNSUPPORTED));
    }

    private Fixture fixture() throws Exception {
        Path formal = Files.createDirectories(temp.resolve("formal"));
        Path world = Files.createDirectories(formal.resolve("saves/world"));
        Path audit = temp.resolve("repository/work/formal-survey/world");
        FormalWorldReadOnlyGuard guard = new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                "formal-survey-v1", "world:" + "a".repeat(64), formal, world, audit));
        return new Fixture(world, guard);
    }

    private static NbtReadLimits limits() {
        return new NbtReadLimits(4 * 1_024 * 1_024, 512 * 1_024, 2 * 1_024 * 1_024,
                32, 65_536, 65_536, 65_536, Set.of(3_465));
    }

    private static byte[] levelNbt(int dataVersion) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            namedCompound(out, "Data", data -> {
                namedString(data, "LevelName", "Formal Test World");
                namedInt(data, "DataVersion", dataVersion);
                namedLong(data, "LastPlayed", 1_234_567_890L);
                namedCompound(data, "Version", version -> namedString(version, "Name", "1.20.1"));
                namedCompound(data, "WorldGenSettings", worldGen ->
                        namedCompound(worldGen, "dimensions", dimensions -> {
                            namedCompound(dimensions, "minecraft:overworld", ignored -> {});
                            namedCompound(dimensions, "minecraft:the_nether", ignored -> {});
                            namedCompound(dimensions, "minecraft:the_end", ignored -> {});
                        }));
            });
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static byte[] chunkNbt(int chunkX, int chunkZ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            namedInt(out, "DataVersion", 3_465);
            namedInt(out, "xPos", chunkX);
            namedInt(out, "zPos", chunkZ);
            out.writeByte(9);
            out.writeUTF("sections");
            out.writeByte(10);
            out.writeInt(1);
            out.writeByte(1);
            out.writeUTF("Y");
            out.writeByte(0);
            namedCompound(out, "block_states", blockStates -> {
                blockStates.writeByte(9);
                blockStates.writeUTF("palette");
                blockStates.writeByte(10);
                blockStates.writeInt(2);
                namedString(blockStates, "Name", "create:millstone");
                namedCompound(blockStates, "Properties", properties ->
                        namedString(properties, "facing", "north"));
                blockStates.writeByte(0);
                namedString(blockStates, "Name", "minecraft:air");
                blockStates.writeByte(0);
                blockStates.writeByte(12);
                blockStates.writeUTF("data");
                blockStates.writeInt(256);
                blockStates.writeLong(1L);
                for (int index = 1; index < 256; index++) blockStates.writeLong(0L);
            });
            out.writeByte(0);
            out.writeByte(9);
            out.writeUTF("block_entities");
            out.writeByte(10);
            out.writeInt(1);
            namedString(out, "id", "minecraft:chest");
            namedInt(out, "x", chunkX * 16 + 4);
            namedInt(out, "y", 64);
            namedInt(out, "z", chunkZ * 16 + 5);
            out.writeByte(9);
            out.writeUTF("Items");
            out.writeByte(10);
            out.writeInt(1);
            namedString(out, "id", "minecraft:diamond");
            out.writeByte(1);
            out.writeUTF("Count");
            out.writeByte(64);
            out.writeByte(0);
            out.writeByte(0);
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static byte[] deepNbt(int compounds) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            for (int index = 0; index < compounds; index++) {
                out.writeByte(10);
                out.writeUTF("d" + index);
            }
            for (int index = 0; index <= compounds; index++) out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static byte[] widePaletteChunkNbt() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            namedInt(out, "DataVersion", 3_465);
            namedInt(out, "xPos", 0);
            namedInt(out, "zPos", 0);
            out.writeByte(9);
            out.writeUTF("sections");
            out.writeByte(10);
            out.writeInt(1);
            out.writeByte(1);
            out.writeUTF("Y");
            out.writeByte(0);
            namedCompound(out, "block_states", blockStates -> {
                blockStates.writeByte(9);
                blockStates.writeUTF("palette");
                blockStates.writeByte(10);
                blockStates.writeInt(17);
                for (int index = 0; index < 17; index++) {
                    namedString(blockStates, "Name", "test:block" + index);
                    blockStates.writeByte(0);
                }
                blockStates.writeByte(12);
                blockStates.writeUTF("data");
                blockStates.writeInt(342);
                blockStates.writeLong(16L);
                for (int index = 1; index < 342; index++) blockStates.writeLong(0L);
            });
            out.writeByte(0);
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static byte[] longArrayNbt(int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(12);
            out.writeUTF("data");
            out.writeInt(length);
            for (int index = 0; index < length; index++) out.writeLong(index);
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static void writeRegion(Path path, Map<Integer, ChunkPayload> chunks) throws IOException {
        int sectors = 2;
        Map<Integer, Integer> offsets = new LinkedHashMap<>();
        Map<Integer, Integer> sectorCounts = new LinkedHashMap<>();
        for (Map.Entry<Integer, ChunkPayload> entry : chunks.entrySet()) {
            int required = (entry.getValue().payload().length + 5 + 4_095) / 4_096;
            offsets.put(entry.getKey(), sectors);
            sectorCounts.put(entry.getKey(), required);
            sectors += required;
        }
        byte[] file = new byte[sectors * 4_096];
        ByteBuffer header = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
        for (Map.Entry<Integer, ChunkPayload> entry : chunks.entrySet()) {
            int index = entry.getKey();
            header.putInt(index * 4, (offsets.get(index) << 8) | sectorCounts.get(index));
            writeChunkSector(file, offsets.get(index), entry.getValue().compressionType(), entry.getValue().payload());
        }
        Files.write(path, file);
    }

    private static void writeChunkSector(byte[] file, int sector, int compression, byte[] payload) {
        ByteBuffer chunk = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
        int position = sector * 4_096;
        chunk.putInt(position, payload.length + 1);
        file[position + 4] = (byte) compression;
        System.arraycopy(payload, 0, file, position + 5, payload.length);
    }

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(input);
        }
        return bytes.toByteArray();
    }

    private static byte[] deflate(byte[] input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(bytes)) {
            deflate.write(input);
        }
        return bytes.toByteArray();
    }

    private static void namedString(DataOutputStream out, String name, String value) throws IOException {
        out.writeByte(8);
        out.writeUTF(name);
        out.writeUTF(value);
    }

    private static void namedInt(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        out.writeUTF(name);
        out.writeInt(value);
    }

    private static void namedLong(DataOutputStream out, String name, long value) throws IOException {
        out.writeByte(4);
        out.writeUTF(name);
        out.writeLong(value);
    }

    private static void namedCompound(DataOutputStream out, String name, IoConsumer body) throws IOException {
        out.writeByte(10);
        out.writeUTF(name);
        body.accept(out);
        out.writeByte(0);
    }

    @FunctionalInterface
    private interface IoConsumer {
        void accept(DataOutputStream output) throws IOException;
    }

    private record Fixture(Path world, FormalWorldReadOnlyGuard guard) {}

    private record ChunkPayload(int compressionType, byte[] payload) {
        private ChunkPayload {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
