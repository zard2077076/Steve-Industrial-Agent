package dev.stevecreate.agent.core.survey;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormalSaveDiscoveryTest {
    @TempDir Path temp;

    @BeforeEach
    void canonicalizeTemporaryRoot() throws IOException {
        temp = temp.toRealPath();
    }

    @Test
    void uniquelySelectsOneReadableDirectSaveWithoutReadingRegionOrChangingMetadata() throws Exception {
        Path instance = Files.createDirectories(temp.resolve("instance"));
        Path world = Files.createDirectories(instance.resolve("saves/Main World"));
        Path level = Files.writeString(world.resolve("level.dat"), "fixture");
        Path region = Files.createDirectories(world.resolve("region")).resolve("r.0.0.mca");
        Files.writeString(region, "must-not-read");
        FileTime levelTime = Files.getLastModifiedTime(level);
        FileTime regionTime = Files.getLastModifiedTime(region);
        List<Path> reads = new ArrayList<>();

        FormalWorldReadOnlyGuard guard = discoveryGuard(instance);
        FormalSaveDiscoveryResult result = new FormalSaveDiscovery(guard, path -> {
            reads.add(path);
            return metadata("Main World", "a".repeat(64), 100L);
        }).discover(instance);

        assertThat(result.uniquelySelected()).isTrue();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.selectedCandidate().orElseThrow().worldIdentity().value()).startsWith("world:");
        assertThat(result.selectedCandidate().orElseThrow().metadataSource()).isEqualTo("level.dat");
        assertThat(reads).containsExactly(level);
        assertThat(Files.getLastModifiedTime(level)).isEqualTo(levelTime);
        assertThat(Files.getLastModifiedTime(region)).isEqualTo(regionTime);
        assertThat(Files.exists(world.resolve("session.lock"))).isFalse();
        assertThat(Files.readString(Path.of(guard.auditFileIdentity())))
                .contains("ENUMERATE_DIRECT_OPEN", "SAVE_ENUMERATION", "METADATA_LOOKUP", "level.dat")
                .doesNotContain("r.0.0.mca");
    }

    @Test
    void multipleWorldsRemainAmbiguousEvenWhenLastPlayedAndScoresDiffer() throws Exception {
        Path instance = Files.createDirectories(temp.resolve("instance"));
        Path first = createWorld(instance, "Alpha", "one");
        Path second = createWorld(instance, "Beta", "two");

        FormalSaveDiscoveryResult result = new FormalSaveDiscovery(discoveryGuard(instance), path -> {
            if (path.startsWith(first)) return metadata("Alpha", "b".repeat(64), 9_999L);
            return new FormalLevelMetadata("Beta", 3_000, 1L, "older-version",
                    List.of("minecraft:overworld"), "c".repeat(64));
        }).discover(instance);

        assertThat(result.uniquelySelected()).isFalse();
        assertThat(result.candidates()).extracting(candidate -> candidate.worldIdentity().saveDirectoryName())
                .containsExactly("Alpha", "Beta");
        assertThat(result.failures()).extracting(FormalSurveyFailure::code)
                .containsExactly(FormalSurveyFailureCode.MULTIPLE_FORMAL_WORLDS_AMBIGUOUS);
        assertThat(result.failures().get(0).safeNextStep()).contains("select one");
        assertThat(Files.exists(first.resolve("region"))).isFalse();
        assertThat(Files.exists(second.resolve("region"))).isFalse();
    }

    @Test
    void fallsBackToLevelDatOldAndReturnsTypedMissingRootAndUnreadableFailures() throws Exception {
        Path instance = Files.createDirectories(temp.resolve("instance"));
        Path world = Files.createDirectories(instance.resolve("saves/Recovered"));
        Files.writeString(world.resolve("level.dat_old"), "old");

        FormalSaveDiscoveryResult recovered = new FormalSaveDiscovery(discoveryGuard(instance), path ->
                metadata("Recovered", "d".repeat(64), 2L)).discover(instance);
        FormalSaveDiscoveryResult missing = FormalSaveDiscovery.discoverGuarded(
                temp.resolve("missing-instance"), temp.resolve("missing-audit"),
                NbtReadLimits.minecraft1201Defaults());
        Files.createDirectories(instance.resolve("saves/Broken"));
        FormalSaveDiscoveryResult broken = new FormalSaveDiscovery(discoveryGuard(instance), path ->
                metadata("Recovered", "d".repeat(64), 2L)).discover(instance);

        assertThat(recovered.selectedCandidate().orElseThrow().metadataSource()).isEqualTo("level.dat_old");
        assertThat(missing.failures()).extracting(FormalSurveyFailure::code)
                .containsExactly(FormalSurveyFailureCode.FORMAL_SAVE_ROOT_NOT_FOUND);
        assertThat(broken.failures()).extracting(FormalSurveyFailure::code)
                .containsExactly(FormalSurveyFailureCode.LEVEL_DAT_UNREADABLE);
        assertThat(broken.selectedCandidate()).isEmpty();
    }

    private static Path createWorld(Path instance, String name, String contents) throws IOException {
        Path world = Files.createDirectories(instance.resolve("saves").resolve(name));
        Files.writeString(world.resolve("level.dat"), contents);
        return world;
    }

    private static FormalLevelMetadata metadata(String name, String hash, long lastPlayed) {
        return new FormalLevelMetadata(name, 3_465, lastPlayed, "1.20.1",
                List.of("minecraft:the_nether", "minecraft:overworld", "minecraft:the_end"), hash);
    }

    private FormalWorldReadOnlyGuard discoveryGuard(Path instance) throws Exception {
        return new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                "formal-survey-v1", "world:unselected", instance, instance.resolve("saves"),
                temp.resolve("repository/work/formal-survey/discovery")));
    }
}
