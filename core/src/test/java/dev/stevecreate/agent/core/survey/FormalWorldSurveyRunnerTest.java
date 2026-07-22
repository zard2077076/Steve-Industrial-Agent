package dev.stevecreate.agent.core.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormalWorldSurveyRunnerTest {
    private static final String WORLD = "world:" + "a".repeat(64);
    private static final List<String> DIMENSIONS = List.of(
            "minecraft:overworld", "minecraft:the_end", "minecraft:the_nether");
    @TempDir Path temp;

    @Test
    void guardedRunProducesPendingCandidatesAndEqualFingerprintsWithoutPrivateReads() throws Exception {
        Fixture fixture = fixture();

        FormalWorldSurveyRun result = new FormalWorldSurveyRunner(() -> 10).run(
                fixture.guard(), identity(), configuration());

        assertThat(result.preFingerprint()).isEqualTo(result.postFingerprint());
        assertThat(result.survey().status()).isEqualTo(SurveyStatus.COMPLETE);
        assertThat(result.survey().candidateZones()).isNotEmpty()
                .allSatisfy(zone -> {
                    assertThat(zone.status()).isEqualTo(CandidateZoneStatus.PENDING_USER_SELECTION);
                    assertThat(zone.requiredFutureAuthorization()).contains(
                            "CLAIM_PERMISSION", "DRY_RUN_VALIDATION", "USER_SELECTION");
                });
        assertThat(result.survey().dimensions()).hasSize(3);
        assertThat(result.survey().coverage().regionsEnumerated()).isEqualTo(1);
        assertThat(result.survey().coverage().regionsScanned()).isEqualTo(1);
        assertThat(result.survey().coverage().chunksParsed()).isEqualTo(1);
        assertThat(result.topology().coverageComplete()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.savesOrFormalWorldRead()).isTrue();
        assertThat(result.formalWorldWrite()).isFalse();
        assertThat(result.sessionLockCreatedOrModified()).isFalse();
        assertThat(result.processStarted()).isFalse();
        assertThat(result.inventoryContentsRead()).isFalse();
        assertThat(result.executionAllowed()).isFalse();
        List<String> auditLines = Files.readAllLines(Path.of(fixture.guard().auditFileIdentity()));
        assertThat(auditLines).anyMatch(line -> line.contains("REGION_METADATA"))
                .anyMatch(line -> line.contains("CHUNK_PAYLOAD"))
                .anyMatch(line -> line.contains("FINGERPRINT_METADATA"));
        assertThat(auditLines.stream().filter(line -> line.contains("playerdata/private.dat")))
                .noneMatch(line -> line.contains("\"event\":\"READ_OPEN\""));
        assertThat(Files.exists(fixture.world().resolve("session.lock"))).isFalse();
    }

    @Test
    void postFingerprintDriftIsTypedAndNeverReturnsAnAcceptedSurvey() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        FormalWorldSurveyRunner runner = new FormalWorldSurveyRunner(() -> {
            int call = calls.incrementAndGet();
            if (call == 6) {
                try {
                    Files.writeString(fixture.world().resolve("external-change.txt"), "outside writer");
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }
            return call;
        });

        assertThatExceptionOfType(FormalReadAccessException.class)
                .isThrownBy(() -> runner.run(fixture.guard(), identity(), configuration()))
                .satisfies(exception -> assertThat(exception.failure().code())
                        .isEqualTo(FormalSurveyFailureCode.WORLD_FINGERPRINT_CHANGED_DURING_SURVEY));
    }

    @Test
    void guardIdentityMismatchRefusesBeforeAnyFormalContentRead() throws Exception {
        Fixture fixture = fixture();
        FormalWorldIdentity wrong = new FormalWorldIdentity("world:" + "b".repeat(64),
                "Formal Test World", "world", 3_465, "1.20.1", DIMENSIONS);

        assertThatExceptionOfType(FormalReadAccessException.class)
                .isThrownBy(() -> new FormalWorldSurveyRunner().run(
                        fixture.guard(), wrong, configuration()))
                .satisfies(exception -> assertThat(exception.failure().code())
                        .isEqualTo(FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED));
        assertThat(Files.exists(Path.of(fixture.guard().auditFileIdentity()))).isFalse();
    }

    @Test
    void liveDurationBoundaryReturnsTypedPartialAfterTheCurrentAtomicRegion() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        FormalWorldSurveyRun result = new FormalWorldSurveyRunner(() ->
                calls.incrementAndGet() < 7 ? 0 : 20_000).run(
                fixture.guard(), identity(), configuration());

        assertThat(result.survey().status()).isEqualTo(SurveyStatus.SURVEY_PARTIAL);
        assertThat(result.survey().coverage().budgetExhausted()).isTrue();
        assertThat(result.failures()).extracting(FormalSurveyFailure::code)
                .contains(FormalSurveyFailureCode.SURVEY_BUDGET_EXHAUSTED,
                        FormalSurveyFailureCode.SURVEY_PARTIAL);
        assertThat(result.preFingerprint()).isEqualTo(result.postFingerprint());
    }

    @Test
    void undersizedRegionIsTypedPartialAndDoesNotAbortAValidSibling() throws Exception {
        Fixture fixture = fixture();
        Files.write(fixture.world().resolve("region/r.0.1.mca"), new byte[0]);

        FormalWorldSurveyRun result = new FormalWorldSurveyRunner(() -> 10).run(
                fixture.guard(), identity(), configuration());

        assertThat(result.survey().status()).isEqualTo(SurveyStatus.SURVEY_PARTIAL);
        assertThat(result.failures()).extracting(FormalSurveyFailure::code)
                .contains(FormalSurveyFailureCode.REGION_HEADER_INVALID);
        assertThat(result.failures()).anySatisfy(failure -> {
            assertThat(failure.canonicalPath()).endsWith("/region/r.0.1.mca");
            assertThat(failure.evidence()).contains("sizeBytes=0");
        });
        assertThat(result.survey().coverage().regionsScanned()).isEqualTo(1);
        assertThat(result.survey().candidateZones()).isNotEmpty();
        assertThat(result.preFingerprint()).isEqualTo(result.postFingerprint());
    }

    @Test
    void everyExceptionAfterPreflightStillCapturesAndVerifiesAPostFingerprint() throws Exception {
        Fixture fixture = fixture();

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                new FormalWorldSurveyRunner(() -> 10).run(
                        fixture.guard(), identity(), configuration(), ignored -> {
                            throw new IllegalStateException("observer fixture failure");
                        }));

        List<String> audit = Files.readAllLines(Path.of(fixture.guard().auditFileIdentity()));
        assertThat(audit.stream().filter(line -> line.contains("\"event\":\"ENUMERATE_OPEN\"")))
                .hasSize(2);
        assertThat(audit.stream().filter(line -> line.contains("playerdata/private.dat")))
                .noneMatch(line -> line.contains("\"event\":\"READ_OPEN\""));
    }

    private Fixture fixture() throws Exception {
        Path formal = Files.createDirectories(temp.resolve("formal"));
        Path world = Files.createDirectories(formal.resolve("saves/world"));
        Files.write(world.resolve("level.dat"), gzip(levelNbt()));
        Files.createDirectories(world.resolve("playerdata"));
        Files.writeString(world.resolve("playerdata/private.dat"), "private inventory-like fixture");
        Path region = Files.createDirectories(world.resolve("region")).resolve("r.0.0.mca");
        writeRegion(region, deflate(chunkNbt()));
        Path audit = temp.resolve("repository/work/formal-survey/world");
        FormalWorldReadOnlyGuard guard = new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                "formal-survey-v1", WORLD, formal, world, audit));
        return new Fixture(world, guard);
    }

    private static FormalWorldIdentity identity() {
        return new FormalWorldIdentity(WORLD, "Formal Test World", "world", 3_465, "1.20.1", DIMENSIONS);
    }

    private static FormalSurveyRunConfiguration configuration() {
        return new FormalSurveyRunConfiguration(
                new SurveyBudget(2, 8, 2 * 1_024 * 1_024, 10_000,
                        2 * 1_024 * 1_024, 2, 1, 1),
                64, 2, 1, 7, limits());
    }

    private static NbtReadLimits limits() {
        return new NbtReadLimits(4 * 1_024 * 1_024, 512 * 1_024, 2 * 1_024 * 1_024,
                32, 65_536, 65_536, 65_536, Set.of(3_465));
    }

    private static byte[] levelNbt() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            compound(out, "Data", data -> {
                string(data, "LevelName", "Formal Test World");
                integer(data, "DataVersion", 3_465);
                longValue(data, "LastPlayed", 1_234_567_890L);
                compound(data, "Version", version -> string(version, "Name", "1.20.1"));
                compound(data, "WorldGenSettings", worldGen ->
                        compound(worldGen, "dimensions", dimensions -> {
                            compound(dimensions, "minecraft:overworld", ignored -> {});
                            compound(dimensions, "minecraft:the_nether", ignored -> {});
                            compound(dimensions, "minecraft:the_end", ignored -> {});
                        }));
            });
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static byte[] chunkNbt() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            integer(out, "DataVersion", 3_465);
            integer(out, "xPos", 0);
            integer(out, "zPos", 0);
            out.writeByte(9);
            out.writeUTF("sections");
            out.writeByte(10);
            out.writeInt(1);
            out.writeByte(1);
            out.writeUTF("Y");
            out.writeByte(4);
            compound(out, "block_states", blockStates -> {
                blockStates.writeByte(9);
                blockStates.writeUTF("palette");
                blockStates.writeByte(10);
                blockStates.writeInt(2);
                string(blockStates, "Name", "minecraft:air");
                blockStates.writeByte(0);
                string(blockStates, "Name", "create:millstone");
                blockStates.writeByte(0);
                blockStates.writeByte(12);
                blockStates.writeUTF("data");
                blockStates.writeInt(256);
                blockStates.writeLong(1L);
                for (int index = 1; index < 256; index++) blockStates.writeLong(0L);
            });
            out.writeByte(0);
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static void writeRegion(Path path, byte[] payload) throws IOException {
        byte[] file = new byte[3 * 4_096];
        ByteBuffer bytes = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
        bytes.putInt(0, (2 << 8) | 1);
        bytes.putInt(4_096, 1_700_000_000);
        bytes.putInt(2 * 4_096, payload.length + 1);
        file[2 * 4_096 + 4] = 2;
        System.arraycopy(payload, 0, file, 2 * 4_096 + 5, payload.length);
        Files.write(path, file);
    }

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write(input);
        }
        return bytes.toByteArray();
    }

    private static byte[] deflate(byte[] input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(bytes)) {
            output.write(input);
        }
        return bytes.toByteArray();
    }

    private static void string(DataOutputStream out, String name, String value) throws IOException {
        out.writeByte(8);
        out.writeUTF(name);
        out.writeUTF(value);
    }

    private static void integer(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        out.writeUTF(name);
        out.writeInt(value);
    }

    private static void longValue(DataOutputStream out, String name, long value) throws IOException {
        out.writeByte(4);
        out.writeUTF(name);
        out.writeLong(value);
    }

    private static void compound(DataOutputStream out, String name, IoConsumer body) throws IOException {
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
}
