package dev.stevecreate.agent.core.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormalWorldReadOnlyGuardTest {
    @TempDir Path temp;
    private Path formalInstance;
    private Path world;
    private Path audit;

    @BeforeEach
    void setUp() throws IOException {
        formalInstance = Files.createDirectories(temp.resolve("formal-instance"));
        world = Files.createDirectories(formalInstance.resolve("saves/world"));
        audit = temp.resolve("repository/work/formal-survey/world");
    }

    @Test
    void opensOnlyReadChannelsAuditsOutsideFormalAndPreservesSourceMetadata() throws Exception {
        Path level = Files.writeString(world.resolve("level.dat"), "level-data");
        FileTime before = Files.getLastModifiedTime(level);
        FormalWorldReadOnlyGuard guard = guard();

        String value = guard.read(level, FormalReadIntent.LEVEL_METADATA, channel -> {
            ByteBuffer buffer = ByteBuffer.allocate(32);
            channel.read(buffer);
            return new String(Arrays.copyOf(buffer.array(), buffer.position()));
        });

        assertThat(value).isEqualTo("level-data");
        assertThat(Files.getLastModifiedTime(level)).isEqualTo(before);
        assertThat(Path.of(guard.auditFileIdentity())).startsWith(audit.toAbsolutePath());
        assertThat(Path.of(guard.auditFileIdentity()).startsWith(formalInstance)).isFalse();
        assertThat(Files.readString(Path.of(guard.auditFileIdentity())))
                .contains("READ_OPEN", "READ_COMPLETE", "LEVEL_METADATA");
        assertThat(Files.exists(world.resolve("session.lock"))).isFalse();
    }

    @Test
    void everyMutationLockAndFormalProcessStartIntentIsTypedAndCannotChangeTheSource() throws Exception {
        Path level = Files.writeString(world.resolve("level.dat"), "unchanged");
        byte[] before = Files.readAllBytes(level);
        FormalWorldReadOnlyGuard guard = guard();

        for (FormalFileOperation operation : FormalFileOperation.values()) {
            if (operation == FormalFileOperation.READ) continue;
            FormalReadOnlyDecision decision = guard.authorize(operation, level, FormalReadIntent.LEVEL_METADATA);
            assertThat(decision.allowed()).as(operation.toString()).isFalse();
            FormalSurveyFailureCode expected = switch (operation) {
                case LOCK -> FormalSurveyFailureCode.FORMAL_WORLD_LOCK_ATTEMPT;
                case PROCESS_START -> FormalSurveyFailureCode.FORMAL_WORLD_PROCESS_START_FORBIDDEN;
                default -> FormalSurveyFailureCode.FORMAL_WORLD_WRITE_ATTEMPT;
            };
            assertThat(decision.typedFailure().orElseThrow().code()).isEqualTo(expected);
        }

        assertThat(Files.readAllBytes(level)).isEqualTo(before);
        assertThat(Files.exists(world.resolve("session.lock"))).isFalse();
    }

    @Test
    void outsidePrivateAndReparsePathsFailClosedBeforeOpen() throws Exception {
        Path level = Files.writeString(world.resolve("level.dat"), "level");
        Path outside = Files.writeString(temp.resolve("outside.dat"), "outside");
        Path player = Files.createDirectories(world.resolve("playerdata")).resolve("player.dat");
        Files.writeString(player, "private");
        FormalWorldReadOnlyGuard guard = guard();

        assertThat(guard.authorize(FormalFileOperation.READ, outside, FormalReadIntent.LEVEL_METADATA)
                .typedFailure().orElseThrow().code())
                .isEqualTo(FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT);
        assertThat(guard.authorize(FormalFileOperation.READ, player, FormalReadIntent.CHUNK_PAYLOAD)
                .typedFailure().orElseThrow().code())
                .isEqualTo(FormalSurveyFailureCode.FORMAL_INVENTORY_CONTENTS_FORBIDDEN);

        Path link = world.resolve("linked-level.dat");
        try {
            Files.createSymbolicLink(link, level);
            assertThat(guard.authorize(FormalFileOperation.READ, link, FormalReadIntent.LEVEL_METADATA)
                    .typedFailure().orElseThrow().code())
                    .isEqualTo(FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assertThat(guard.authorize(FormalFileOperation.READ, world.resolve("../outside.dat"),
                    FormalReadIntent.LEVEL_METADATA).typedFailure().orElseThrow().code())
                    .isIn(FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT,
                            FormalSurveyFailureCode.FORMAL_WORLD_REPARSE_POINT_FORBIDDEN);
        }
    }

    @Test
    void auditDestinationInsideFormalIsRejectedBeforeCreatingIt() throws Exception {
        Path unsafe = world.resolve("audit");
        FormalReadOnlyPolicy policy = new FormalReadOnlyPolicy(
                "formal-survey-v1", "world:" + "1".repeat(64), formalInstance, world, unsafe);

        assertThatExceptionOfType(FormalReadAccessException.class)
                .isThrownBy(() -> new FormalWorldReadOnlyGuard(policy))
                .satisfies(exception -> assertThat(exception.failure().code())
                        .isEqualTo(FormalSurveyFailureCode.FORMAL_WORLD_WRITE_ATTEMPT));
        assertThat(Files.exists(unsafe)).isFalse();
    }

    @Test
    void fingerprintsCompareFileCountSizeMtimeManifestAndConfiguredKeyHashes() throws Exception {
        Path level = Files.writeString(world.resolve("level.dat"), "level");
        Files.createDirectories(world.resolve("region"));
        Path region = Files.writeString(world.resolve("region/r.0.0.mca"), "region");
        Files.createDirectories(world.resolve("playerdata"));
        Files.writeString(world.resolve("playerdata/private.dat"), "private");
        FormalWorldReadOnlyGuard guard = guard();
        FormalFingerprintPolicy policy = new FormalFingerprintPolicy(32, Set.of("level.dat"), true);
        FormalWorldFingerprintService service = new FormalWorldFingerprintService();

        FormalWorldFingerprint before = service.capture(guard, "world:" + "1".repeat(64), policy);
        FormalWorldFingerprint same = service.capture(guard, "world:" + "1".repeat(64), policy);
        Files.writeString(region, "changed-region");
        FormalWorldFingerprint changed = service.capture(guard, "world:" + "1".repeat(64), policy);

        assertThat(before.exactlyMatches(same)).isTrue();
        assertThat(before.fileCount()).isEqualTo(3);
        assertThat(before.keyFileHashes()).containsOnlyKeys("level.dat", "region/r.0.0.mca");
        assertThat(before.manifest()).extracting(FormalFileFingerprintEntry::relativePath)
                .containsExactly("level.dat", "playerdata/private.dat", "region/r.0.0.mca");
        assertThat(before.manifest().get(1).sha256()).isEqualTo(FormalFileFingerprintEntry.NOT_HASHED);
        assertThat(before.exactlyMatches(changed)).isFalse();
        assertThat(changed.totalBytes()).isGreaterThan(before.totalBytes());
        assertThat(before.manifest().get(0).lastModifiedEpochMillis())
                .isEqualTo(Files.getLastModifiedTime(level).toMillis());
        assertThat(Files.readAllLines(Path.of(guard.auditFileIdentity())).stream()
                .filter(line -> line.contains("private.dat")))
                .noneMatch(line -> line.contains("\"event\":\"READ_OPEN\""));
    }

    @Test
    void directDiscoveryEnumerationIsBoundedAuditedAndNeverWalksNestedRegionData() throws Exception {
        Path alpha = Files.createDirectories(world.resolve("Alpha"));
        Files.createDirectories(alpha.resolve("region"));
        Files.writeString(alpha.resolve("region/r.0.0.mca"), "must-not-observe");
        Files.createDirectories(world.resolve("Beta"));
        FormalWorldReadOnlyGuard guard = guard();

        assertThatExceptionOfType(FormalReadAccessException.class)
                .isThrownBy(() -> guard.enumerateOrdinaryDirectDirectories(1))
                .satisfies(exception -> assertThat(exception.failure().code())
                        .isEqualTo(FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED));

        assertThat(Files.readString(Path.of(guard.auditFileIdentity())))
                .contains("ENUMERATE_DIRECT_OPEN", "SAVE_ENUMERATION")
                .doesNotContain("r.0.0.mca", "must-not-observe");
    }

    private FormalWorldReadOnlyGuard guard() throws Exception {
        return new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                "formal-survey-v1", "world:" + "1".repeat(64), formalInstance, world, audit));
    }
}
