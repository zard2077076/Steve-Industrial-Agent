package dev.stevecreate.agent.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WritableTestWorldPilotTest {
    private static final String FORMAL_WORLD = "world:" + "f".repeat(64);
    private static final String TEST_WORLD = "world:" + "1".repeat(64);
    private static final String RUNTIME = "runtime:deceasedcraft-test";
    private static final String FINGERPRINT = "sha256:" + "2".repeat(64);
    private static final String INSTANCE = "test-instance:steveagent-deceasedcraft-test-v1";
    private static final ResourceId OVERWORLD = ResourceId.parse("minecraft:overworld");

    @TempDir Path temp;

    @Test
    void uniqueMarkedDisposableWorldIsDiscoveredOutsideFormalAndBackupRoots() throws Exception {
        Fixture fixture = fixture();
        WritableTestWorldDiscoveryResult result = new WritableTestWorldDiscovery().discover(
                List.of(fixture.candidate(TEST_WORLD, "Steve Agent Test", "Steve Agent Test")), fixture.policy());

        assertThat(result.failure()).isEmpty();
        WritableTestWorldIdentity identity = result.identity().orElseThrow();
        assertThat(identity.value()).matches("test-world:[0-9a-f]{64}");
        assertThat(identity.sourceWorldIdentity()).isEqualTo(TEST_WORLD).isNotEqualTo(FORMAL_WORLD);
        assertThat(identity.canonicalWorldPath()).isEqualTo(fixture.world().toRealPath());
        assertThat(identity.occupancy()).isEqualTo(WritableTestWorldOccupancy.CLOSED);
        assertThat(identity.evidence()).contains("marker=ISOLATED_WRITABLE_TEST_WORLD");
    }

    @Test
    void discoveryRejectsFormalIdentityMissingMarkerAndAmbiguousWorlds() throws Exception {
        Fixture fixture = fixture();
        WritableTestWorldCandidate formal = fixture.candidate(
                FORMAL_WORLD, "Steve Agent Test", "Steve Agent Test");
        WritableTestWorldCandidate unmarked = new WritableTestWorldCandidate(
                TEST_WORLD, "Steve Agent Test", "Steve Agent Test", fixture.game(), fixture.world(),
                "UNMARKED", RUNTIME, FINGERPRINT, INSTANCE, WorldEnvironmentType.ISOLATED_TEST_WORLD,
                true, WritableTestWorldOccupancy.CLOSED, List.of("fixture"));
        WritableTestWorldDiscovery discovery = new WritableTestWorldDiscovery();

        assertThat(discovery.discover(List.of(formal, unmarked), fixture.policy())
                .failure().orElseThrow().code()).isEqualTo(WritableTestWorldFailureCode.TEST_WORLD_NOT_FOUND);

        Path other = Files.createDirectories(fixture.game().resolve("saves/Other Test"));
        WritableTestWorldCandidate second = new WritableTestWorldCandidate(
                "world:" + "3".repeat(64), "Other Test", "Other Test", fixture.game(), other,
                WritableTestWorldIdentity.REQUIRED_MARKER, RUNTIME, "sha256:" + "4".repeat(64),
                INSTANCE, WorldEnvironmentType.ISOLATED_TEST_WORLD, true,
                WritableTestWorldOccupancy.CLOSED, List.of("fixture"));
        assertThat(discovery.discover(List.of(
                fixture.candidate(TEST_WORLD, "Steve Agent Test", "Steve Agent Test"), second), fixture.policy())
                .failure().orElseThrow().code())
                .isEqualTo(WritableTestWorldFailureCode.MULTIPLE_TEST_WORLDS_AMBIGUOUS);
    }

    @Test
    void guardHardStopsFormalWorldAndRequiresClosedWorldForBackup() throws Exception {
        Fixture fixture = fixture();
        WritableTestWorldIdentity identity = identity(fixture);
        WritableTestWorldGuard guard = new WritableTestWorldGuard();
        WritableTestWorldGuardRequest inUseBackup = request(
                WritableTestWorldOperation.BACKUP, identity, WritableTestWorldOccupancy.IN_USE);

        assertThat(guard.check(identity, fixture.policy(), inUseBackup)
                .failure().orElseThrow().code())
                .isEqualTo(WritableTestWorldFailureCode.TEST_WORLD_IN_USE_DURING_BACKUP);

        WritableTestWorldGuardRequest formal = new WritableTestWorldGuardRequest(
                WritableTestWorldOperation.EXECUTE, FORMAL_WORLD, fixture.formal(), fixture.formal(),
                FINGERPRINT, WritableTestWorldIdentity.REQUIRED_MARKER,
                WritableTestWorldOccupancy.IN_USE);
        assertThat(guard.check(identity, fixture.policy(), formal).failure().orElseThrow().code())
                .isEqualTo(WritableTestWorldFailureCode.FORMAL_WORLD_EXECUTION_FORBIDDEN);
    }

    @Test
    void regionHereUsesExactDimensionsAndPreviewConfirmationBindSessionAndFingerprint() throws Exception {
        Fixture fixture = fixture();
        WritableTestWorldIdentity identity = identity(fixture);
        PilotRegionSelectionService service = new PilotRegionSelectionService();
        Instant now = Instant.parse("2026-07-20T08:00:00Z");
        PilotRegionSelection selected = service.selectHere(identity, OVERWORLD,
                new BlockPos3i(100, 70, -20), 32, 32, 20, -64, 320,
                "player:abc", "session:one", now, now.plusSeconds(900), "iwp-policy-v1")
                .value().orElseThrow();

        assertThat(selected.bounds()).isEqualTo(new DeploymentBoundingBox(
                new BlockPos3i(84, 68, -36), new BlockPos3i(115, 87, -5)));
        assertThat(selected.bounds().volume()).isEqualTo(32L * 32L * 20L);
        assertThat(selected.state()).isEqualTo(PilotRegionState.SELECTED);

        PilotRegionSelection previewed = service.preview(selected, identity.value(), OVERWORLD,
                FINGERPRINT, now.plusSeconds(1)).value().orElseThrow();
        PilotRegionConfirmation confirmed = service.confirm(previewed, identity.value(), OVERWORLD,
                FINGERPRINT, "player:abc", "session:one", now.plusSeconds(2))
                .value().orElseThrow();
        assertThat(confirmed.regionHash()).isEqualTo(selected.regionHash());
        assertThat(confirmed.worldFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(confirmed.confirmationIdentity()).startsWith("region-confirmation:");
    }

    @Test
    void cornerSelectionCanonicalizesOrderAndRefusesOversizeStaleOrCrossDimension() throws Exception {
        Fixture fixture = fixture();
        WritableTestWorldIdentity identity = identity(fixture);
        PilotRegionSelectionService service = new PilotRegionSelectionService();
        Instant now = Instant.parse("2026-07-20T08:00:00Z");
        PilotRegionSelection selected = service.selectCorners(identity, OVERWORLD,
                new BlockPos3i(4, 80, 6), new BlockPos3i(-4, 70, -6), -64, 320,
                "player:abc", "session:one", now, now.plusSeconds(60), "iwp-policy-v1")
                .value().orElseThrow();
        assertThat(selected.bounds().minimum()).isEqualTo(new BlockPos3i(-4, 70, -6));
        assertThat(selected.bounds().maximum()).isEqualTo(new BlockPos3i(4, 80, 6));

        assertThat(service.selectCorners(identity, OVERWORLD,
                new BlockPos3i(0, 0, 0), new BlockPos3i(64, 0, 0), -64, 320,
                "player:abc", "session:one", now, now.plusSeconds(60), "iwp-policy-v1")
                .failure().orElseThrow().code()).isEqualTo(WritableTestWorldFailureCode.REGION_TOO_LARGE);
        assertThat(service.preview(selected, identity.value(), ResourceId.parse("minecraft:the_nether"),
                FINGERPRINT, now.plusSeconds(1)).failure().orElseThrow().code())
                .isEqualTo(WritableTestWorldFailureCode.REGION_DIMENSION_MISMATCH);

        PilotRegionSelection previewed = service.preview(selected, identity.value(), OVERWORLD,
                FINGERPRINT, now.plusSeconds(1)).value().orElseThrow();
        assertThat(service.confirm(previewed, identity.value(), OVERWORLD,
                "sha256:" + "9".repeat(64), "player:abc", "session:one", now.plusSeconds(2))
                .failure().orElseThrow().code()).isEqualTo(WritableTestWorldFailureCode.PILOT_PREVIEW_STALE);
    }

    @Test
    void typedFailureVocabularyContainsEveryRequiredPilotFailure() {
        assertThat(Arrays.stream(WritableTestWorldFailureCode.values()).map(Enum::name))
                .containsExactly(
                        "TEST_INSTANCE_NOT_FOUND", "TEST_WORLD_NOT_FOUND",
                        "MULTIPLE_TEST_WORLDS_AMBIGUOUS", "TEST_WORLD_IDENTITY_MISMATCH",
                        "FORMAL_WORLD_FORBIDDEN", "TEST_WORLD_IN_USE_DURING_BACKUP",
                        "TEST_WORLD_BACKUP_REQUIRED", "TEST_WORLD_BACKUP_INVALID",
                        "TEST_WORLD_FINGERPRINT_STALE", "REGION_NOT_SELECTED",
                        "REGION_NOT_CONFIRMED", "REGION_TOO_LARGE",
                        "REGION_DIMENSION_MISMATCH", "REGION_HASH_STALE",
                        "REGION_CONTAINS_PROTECTED_BLOCK", "REGION_CONTAINS_IMPORTANT_BLOCK_ENTITY",
                        "REGION_INSUFFICIENT_SPACE", "PILOT_PREVIEW_REQUIRED",
                        "PILOT_PREVIEW_STALE", "PILOT_TARGET_UNSUPPORTED",
                        "PILOT_MATERIAL_SOURCE_UNAVAILABLE", "PILOT_POWER_SOURCE_UNAVAILABLE",
                        "PILOT_DUPLICATE_SESSION", "PILOT_CANCELLED", "PILOT_CLEANUP_UNSAFE",
                        "PILOT_RECOVERY_UNSAFE", "TEST_ONLY_CAPABILITY_FORBIDDEN_IN_FORMAL_WORLD",
                        "FORMAL_WORLD_EXECUTION_FORBIDDEN");
    }

    private WritableTestWorldIdentity identity(Fixture fixture) {
        return new WritableTestWorldDiscovery().discover(List.of(
                fixture.candidate(TEST_WORLD, "Steve Agent Test", "Steve Agent Test")), fixture.policy())
                .identity().orElseThrow();
    }

    private static WritableTestWorldGuardRequest request(
            WritableTestWorldOperation operation,
            WritableTestWorldIdentity identity,
            WritableTestWorldOccupancy occupancy) {
        return new WritableTestWorldGuardRequest(operation, identity.sourceWorldIdentity(),
                identity.canonicalGameDirectory(), identity.canonicalWorldPath(),
                identity.worldFingerprint(), WritableTestWorldIdentity.REQUIRED_MARKER, occupancy);
    }

    private Fixture fixture() throws Exception {
        Path instance = Files.createDirectories(temp.resolve("work/isolated-pack/SteveAgent_DeceasedCraft_Test"));
        Path game = Files.createDirectories(instance.resolve("run"));
        Path world = Files.createDirectories(game.resolve("saves/Steve Agent Test"));
        Path formal = Files.createDirectories(temp.resolve("PCL2"));
        Path backups = Files.createDirectories(temp.resolve("work/writable-world-backups"));
        return new Fixture(instance, game, world, formal, backups);
    }

    private record Fixture(Path instance, Path game, Path world, Path formal, Path backups) {
        WritableTestWorldPolicy policy() {
            return new WritableTestWorldPolicy(instance, formal, Set.of(backups), INSTANCE,
                    Set.of(FORMAL_WORLD));
        }

        WritableTestWorldCandidate candidate(String identity, String level, String directory) {
            return new WritableTestWorldCandidate(identity, level, directory, game, world,
                    WritableTestWorldIdentity.REQUIRED_MARKER, RUNTIME, FINGERPRINT, INSTANCE,
                    WorldEnvironmentType.ISOLATED_TEST_WORLD, true,
                    WritableTestWorldOccupancy.CLOSED, List.of("fixture:offline-level-metadata"));
        }
    }
}
