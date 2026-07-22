package dev.stevecreate.agent.core.survey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Enumerates only direct save children and never opens region, playerdata or game runtime state. */
public final class FormalSaveDiscovery {
    private static final String NOT_SELECTED = "world:unselected";
    private static final String NOT_APPLICABLE = "not-applicable";
    private final FormalWorldReadOnlyGuard guard;
    private final FormalLevelMetadataReader metadataReader;

    public FormalSaveDiscovery(FormalWorldReadOnlyGuard guard, NbtReadLimits limits) {
        this(guard, new OfflineLevelDatReader(guard, limits));
    }

    FormalSaveDiscovery(FormalWorldReadOnlyGuard guard, FormalLevelMetadataReader metadataReader) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.metadataReader = Objects.requireNonNull(metadataReader, "metadataReader");
    }

    public static FormalSaveDiscoveryResult discoverGuarded(
            Path formalInstanceRoot,
            Path auditRoot,
            NbtReadLimits limits) {
        Objects.requireNonNull(formalInstanceRoot, "formalInstanceRoot");
        Objects.requireNonNull(auditRoot, "auditRoot");
        Objects.requireNonNull(limits, "limits");
        Path normalized = formalInstanceRoot.toAbsolutePath().normalize();
        Path saves = normalized.resolve("saves");
        try {
            FormalWorldReadOnlyGuard guard = new FormalWorldReadOnlyGuard(new FormalReadOnlyPolicy(
                    "formal-survey-v1", NOT_SELECTED, normalized, saves, auditRoot));
            return new FormalSaveDiscovery(guard, limits).discover(normalized);
        } catch (FormalReadAccessException exception) {
            return new FormalSaveDiscoveryResult(identity(saves), List.of(), null, List.of(exception.failure()));
        } catch (IOException | SecurityException exception) {
            return failureResult(saves, FormalSurveyFailureCode.FORMAL_SAVE_ROOT_NOT_FOUND,
                    "The approved instance has no readable guarded saves root",
                    "Confirm the exact formal instance without starting Minecraft");
        }
    }

    public FormalSaveDiscoveryResult discover(Path formalInstanceRoot) {
        Objects.requireNonNull(formalInstanceRoot, "formalInstanceRoot");
        Path normalizedInstance = formalInstanceRoot.toAbsolutePath().normalize();
        Path canonicalSaves = Path.of(guard.approvedSaveRootIdentity());
        if (!identity(normalizedInstance).equals(guard.formalInstanceRootIdentity())
                || !canonicalSaves.getFileName().toString().equalsIgnoreCase("saves")
                || !identity(canonicalSaves.getParent()).equals(guard.formalInstanceRootIdentity())) {
            return failureResult(canonicalSaves, FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT,
                    "The supplied instance and guarded direct saves root do not match",
                    "Recreate the guard for the exact formal instance before retrying");
        }

        List<FormalSaveCandidate> candidates = new ArrayList<>();
        List<FormalSurveyFailure> failures = new ArrayList<>();
        try {
            for (Path world : guard.enumerateOrdinaryDirectDirectories(256)) {
                inspectCandidate(world, candidates, failures);
            }
        } catch (FormalReadAccessException exception) {
            failures.add(exception.failure());
        } catch (IOException | SecurityException exception) {
            failures.add(failure(FormalSurveyFailureCode.FORMAL_SAVE_ROOT_NOT_FOUND, canonicalSaves,
                    "The saves root could not be enumerated through the read-only guard",
                    "Check read-only access and guarded audit evidence", List.of(exception.getClass().getSimpleName())));
        }

        candidates.sort(Comparator.comparingInt(FormalSaveCandidate::evidenceScore).reversed()
                .thenComparing(candidate -> candidate.worldIdentity().saveDirectoryName(), String.CASE_INSENSITIVE_ORDER));
        if (failures.isEmpty() && candidates.size() == 1) {
            return new FormalSaveDiscoveryResult(identity(canonicalSaves), candidates, candidates.get(0), List.of());
        }
        if (failures.isEmpty() && candidates.size() > 1) {
            failures.add(failure(FormalSurveyFailureCode.MULTIPLE_FORMAL_WORLDS_AMBIGUOUS, canonicalSaves,
                    "More than one readable world exists; ranking evidence cannot grant selection authority",
                    "Ask the user to select one listed WorldIdentity before any region read",
                    candidates.stream().map(candidate -> candidate.worldIdentity().value()
                            + ":score=" + candidate.evidenceScore()).toList()));
        }
        if (failures.isEmpty()) {
            failures.add(failure(FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED, canonicalSaves,
                    "No readable level.dat or level.dat_old candidate was found",
                    "Confirm the intended formal world and its read-only metadata files", List.of("candidateCount=0")));
        }
        return new FormalSaveDiscoveryResult(identity(canonicalSaves), candidates, null, failures);
    }

    private void inspectCandidate(
            Path world,
            List<FormalSaveCandidate> candidates,
            List<FormalSurveyFailure> failures) {
        IOException firstFailure = null;
        for (String name : List.of("level.dat", "level.dat_old")) {
            try {
                Path metadataPath = guard.findOrdinaryDirectFile(world, name, FormalReadIntent.LEVEL_METADATA)
                        .orElse(null);
                if (metadataPath == null) continue;
                FormalLevelMetadata metadata = metadataReader.read(metadataPath);
                candidates.add(candidate(world, metadataPath, metadata));
                return;
            } catch (FormalReadAccessException exception) {
                failures.add(exception.failure());
                return;
            } catch (IOException exception) {
                if (firstFailure == null) firstFailure = exception;
            }
        }
        List<String> evidence = new ArrayList<>();
        evidence.add("candidate=" + world.getFileName());
        if (firstFailure != null) evidence.add("readerFailure=" + firstFailure.getClass().getSimpleName());
        failures.add(failure(FormalSurveyFailureCode.LEVEL_DAT_UNREADABLE, world,
                "Neither level.dat nor level.dat_old produced readable bounded metadata",
                "Review this candidate metadata without opening the world", evidence));
    }

    private static FormalSaveCandidate candidate(Path world, Path metadataPath, FormalLevelMetadata metadata) {
        String directory = world.getFileName().toString();
        String stable = String.join("\n", directory.toLowerCase(Locale.ROOT), metadata.levelName(),
                Integer.toString(metadata.dataVersion()), metadata.versionName(), String.join(",", metadata.dimensions()));
        FormalWorldIdentity identity = new FormalWorldIdentity(
                "world:" + sha256(stable), metadata.levelName(), directory, metadata.dataVersion(),
                metadata.versionName(), metadata.dimensions());
        int score = 50;
        List<String> evidence = new ArrayList<>();
        evidence.add("readableMetadata=" + metadataPath.getFileName());
        evidence.add("dataVersion=" + metadata.dataVersion());
        evidence.add("version=" + metadata.versionName());
        evidence.add("dimensions=" + metadata.dimensions().size());
        if ("1.20.1".equals(metadata.versionName())) {
            score += 25;
            evidence.add("minecraft1201=true");
        }
        return new FormalSaveCandidate(identity, identity(world), metadataPath.getFileName().toString(),
                metadata.levelDatSha256(), metadata.lastPlayedEpochMillis(), score, evidence);
    }

    private static FormalSaveDiscoveryResult failureResult(
            Path savesRoot, FormalSurveyFailureCode code, String reason, String nextStep) {
        return new FormalSaveDiscoveryResult(identity(savesRoot), List.of(), null,
                List.of(failure(code, savesRoot, reason, nextStep, List.of("savesRoot=" + identity(savesRoot)))));
    }

    private static FormalSurveyFailure failure(
            FormalSurveyFailureCode code, Path path, String reason, String nextStep, List<String> evidence) {
        return new FormalSurveyFailure(code, FormalSurveyStage.SAVE_DISCOVERY, NOT_SELECTED, identity(path),
                NOT_APPLICABLE, NOT_APPLICABLE, NOT_APPLICABLE, NOT_APPLICABLE, "discoveryLimit=256",
                evidence, reason, nextStep);
    }

    private static String identity(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
