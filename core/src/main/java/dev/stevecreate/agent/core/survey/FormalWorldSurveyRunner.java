package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.deployment.DeploymentBoundingBox;
import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Single-threaded FS-12 orchestrator. Every source observation is delegated to the
 * guard; this class has no process, session, inventory-content, write or execution API.
 */
public final class FormalWorldSurveyRunner {
    private static final String NOT_APPLICABLE = "not-applicable";
    private static final int MAX_TOPOLOGY_FINDINGS = 4_096;
    private static final Comparator<InfrastructureFinding> FINDING_ORDER =
            Comparator.comparing((InfrastructureFinding finding) -> finding.location().dimension().toString())
                    .thenComparingInt(finding -> finding.location().position().y())
                    .thenComparingInt(finding -> finding.location().position().z())
                    .thenComparingInt(finding -> finding.location().position().x())
                    .thenComparing(finding -> finding.resourceId().toString())
                    .thenComparing(finding -> finding.category().name());

    private final FormalWorldFingerprintService fingerprintService = new FormalWorldFingerprintService();
    private final FormalRegionMetadataReader metadataReader = new FormalRegionMetadataReader();
    private final AnvilRegionReader regionReader = new AnvilRegionReader();
    private final CreateInfrastructureClassifier classifier = new CreateInfrastructureClassifier();
    private final OfflineTopologyApproximator topologyApproximator = new OfflineTopologyApproximator();
    private final LongSupplier monotonicMillis;

    public FormalWorldSurveyRunner() {
        this(() -> System.nanoTime() / 1_000_000);
    }

    FormalWorldSurveyRunner(LongSupplier monotonicMillis) {
        this.monotonicMillis = Objects.requireNonNull(monotonicMillis, "monotonicMillis");
    }

    public FormalWorldSurveyRun run(
            FormalWorldReadOnlyGuard guard,
            FormalWorldIdentity identity,
            FormalSurveyRunConfiguration configuration) throws IOException {
        return run(guard, identity, configuration, ignored -> {});
    }

    FormalWorldSurveyRun run(
            FormalWorldReadOnlyGuard guard,
            FormalWorldIdentity identity,
            FormalSurveyRunConfiguration configuration,
            Consumer<FormalWorldFingerprint> preflightObserver) throws IOException {
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(preflightObserver, "preflightObserver");
        if (!identity.value().equals(guard.worldIdentity())) {
            throw refusal(guard, identity, FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED,
                    FormalSurveyStage.READ_ONLY_GUARD, NOT_APPLICABLE,
                    "The selected world identity does not match the read-only policy",
                    List.of("selected=" + identity.value()),
                    "Construct the guard from the exact uniquely discovered WorldIdentity");
        }

        FormalFingerprintPolicy fingerprintPolicy = new FormalFingerprintPolicy(
                configuration.maximumFingerprintFiles(), Set.of(), true);
        FormalWorldFingerprint pre = fingerprintService.capture(guard, identity.value(), fingerprintPolicy);
        try {
            preflightObserver.accept(pre);
            List<Path> files = guard.enumerateOrdinaryFiles(configuration.maximumFingerprintFiles());
            verifyLevelIdentity(guard, identity, configuration.nbtReadLimits(), files);

            Map<String, FormalFileFingerprintEntry> manifest = new HashMap<>();
            pre.manifest().forEach(entry -> manifest.put(entry.relativePath(), entry));
            List<Path> regionFiles = files.stream()
                .filter(path -> FormalRegionMetadataReader.dimension(guard.relative(path)).isPresent())
                .sorted(Comparator.<Path>comparingLong(path ->
                                manifest.get(guard.relative(path)).lastModifiedEpochMillis()).reversed()
                        .thenComparing(guard::relative))
                .toList();
            List<FormalRegionSource> metadataSources = new ArrayList<>();
            List<FormalSurveyFailure> metadataFailures = new ArrayList<>();
            for (Path path : regionFiles.stream().limit(configuration.maximumMetadataRegions()).toList()) {
                FormalFileFingerprintEntry entry = manifest.get(guard.relative(path));
                if (entry.sizeBytes() < 8_192) {
                    metadataFailures.add(invalidRegionHeader(guard, identity, path, entry));
                    continue;
                }
                metadataReader.read(guard, path).ifPresent(metadataSources::add);
            }
            metadataSources.sort(Comparator.comparing((FormalRegionSource source) ->
                            source.metadata().dimension().toString())
                    .thenComparingInt(source -> source.metadata().regionX())
                    .thenComparingInt(source -> source.metadata().regionZ()));

            BoundedSurveyPlanner planner = new BoundedSurveyPlanner(
                    new SurveyPlanContext(identity.value(), guard.approvedSaveRootIdentity(), pre.worldFingerprint()),
                    configuration.budget(), monotonicMillis);
            List<SurveyRegionMetadata> metadata = metadataSources.stream().map(FormalRegionSource::metadata).toList();
            if (!metadata.isEmpty()) {
                planner.scheduleHotSample(metadata, Math.min(configuration.hotSampleRegions(), metadata.size()));
            }
            BoundedSurveyPlan plan = planner.snapshot();
            ScanDraft draft = scan(guard, identity, configuration, pre, regionFiles.size(),
                    metadataSources, metadataFailures, plan);

            FormalWorldFingerprint post = fingerprintService.capture(guard, identity.value(), fingerprintPolicy);
            verifyPostFingerprint(guard, identity, pre, post);

            FormalWorldSurvey survey = new FormalWorldSurvey(identity, draft.status(),
                    pre.worldFingerprint(), post.worldFingerprint(), false, false, false, false,
                    draft.dimensions(), draft.candidates(), draft.coverage(), draft.evidence(), draft.limitations());
            return new FormalWorldSurveyRun(survey, pre, post, draft.topology(), configuration.budget(),
                    draft.failures(), true, false, false, false, false, false);
        } catch (IOException | RuntimeException exception) {
            try {
                FormalWorldFingerprint failurePost = fingerprintService.capture(
                        guard, identity.value(), fingerprintPolicy);
                verifyPostFingerprint(guard, identity, pre, failurePost);
            } catch (IOException | RuntimeException verificationFailure) {
                verificationFailure.addSuppressed(exception);
                throw verificationFailure;
            }
            throw exception;
        }
    }

    private static void verifyPostFingerprint(
            FormalWorldReadOnlyGuard guard,
            FormalWorldIdentity identity,
            FormalWorldFingerprint pre,
            FormalWorldFingerprint post) throws FormalReadAccessException {
        if (!pre.exactlyMatches(post)) {
            throw refusal(guard, identity,
                    FormalSurveyFailureCode.WORLD_FINGERPRINT_CHANGED_DURING_SURVEY,
                    FormalSurveyStage.FINGERPRINT_VERIFICATION, NOT_APPLICABLE,
                    "The formal world fingerprint changed during the read-only survey",
                    List.of("pre=" + pre.worldFingerprint(), "post=" + post.worldFingerprint(),
                            "preManifest=" + pre.manifestSha256(), "postManifest=" + post.manifestSha256()),
                    "Stop, preserve the audit and fingerprint evidence, and wait for explicit user direction");
        }
        if (sessionLockChanged(pre, post)) {
            throw refusal(guard, identity, FormalSurveyFailureCode.EXTERNAL_MUTATION_DETECTED,
                    FormalSurveyStage.FINGERPRINT_VERIFICATION, NOT_APPLICABLE,
                    "session.lock was created, removed or modified during the survey",
                    List.of("sessionLockChanged=true"),
                    "Stop without restoring anything and review the external writer");
        }
    }

    private ScanDraft scan(
            FormalWorldReadOnlyGuard guard,
            FormalWorldIdentity identity,
            FormalSurveyRunConfiguration configuration,
            FormalWorldFingerprint pre,
            int totalRegionFiles,
            List<FormalRegionSource> metadataSources,
            List<FormalSurveyFailure> metadataFailures,
            BoundedSurveyPlan plan) throws IOException {
        long started = monotonicMillis.getAsLong();
        Map<String, FormalRegionSource> byKey = new HashMap<>();
        metadataSources.forEach(source -> byKey.put(source.metadata().key(), source));
        Map<ResourceId, List<RegionSurvey>> regionsByDimension = new HashMap<>();
        List<InfrastructureFinding> findings = new ArrayList<>();
        Set<ChunkKey> parsedChunks = new HashSet<>();
        List<FormalSurveyFailure> failures = new ArrayList<>(plan.failures());
        List<SurveyLimitation> limitations = new ArrayList<>(plan.limitations());
        failures.addAll(metadataFailures);
        limitations.addAll(metadataFailures.stream().map(FormalWorldSurveyRunner::limitation).toList());
        int chunksScanned = 0;
        int chunksParsed = 0;
        int regionsScanned = 0;
        long bytesRead = 0;
        boolean runtimeBudgetExhausted = false;

        for (SurveyRegionWork work : plan.work()) {
            long beforeRegion = Math.max(0, monotonicMillis.getAsLong() - started);
            if (beforeRegion > configuration.budget().maximumDurationMillis()) {
                runtimeBudgetExhausted = true;
                addDurationExhaustion(guard, identity, configuration, failures, limitations, beforeRegion);
                break;
            }
            FormalRegionSource source = byKey.get(work.metadata().key());
            if (source == null) throw new IllegalStateException("planned region has no guarded source");
            String fileHash = pre.keyFileHashes().get(source.metadata().relativePath());
            if (fileHash == null) throw new IllegalStateException("planned region has no pre-fingerprint SHA-256");
            OfflineRegionSnapshot snapshot = regionReader.read(guard, source.path(), identity.value(),
                    source.metadata().dimension(), configuration.parseGeneration(),
                    configuration.nbtReadLimits(), fileHash);
            chunksScanned += source.metadata().populatedChunks();
            chunksParsed += snapshot.chunks().size();
            bytesRead = Math.addExact(bytesRead, source.metadata().fileBytes());
            regionsScanned++;
            failures.addAll(snapshot.failures());
            List<ChunkSurvey> chunkSurveys = new ArrayList<>();
            List<SurveyLimitation> regionLimitations = snapshot.failures().stream()
                    .map(FormalWorldSurveyRunner::limitation).toList();
            limitations.addAll(regionLimitations);
            for (OfflineChunkSnapshot chunk : snapshot.chunks()) {
                InfrastructureClassificationContext context = new InfrastructureClassificationContext(
                        identity.value(), source.metadata().dimension(), snapshot.regionX(), snapshot.regionZ(),
                        snapshot.relativePath(), snapshot.fileFingerprint(), snapshot.parseGeneration(), List.of());
                try {
                    ClassifiedChunkInfrastructure classified = classifier.classify(context, chunk);
                    findings.addAll(classified.findings());
                    parsedChunks.add(new ChunkKey(source.metadata().dimension(), chunk.chunkX(), chunk.chunkZ()));
                    chunkSurveys.add(new ChunkSurvey(chunk.chunkX(), chunk.chunkZ(), SurveyStatus.COMPLETE,
                            chunk.compressedBytes(), chunk.uncompressedNbtBytes(), classified.findings(),
                            List.of(), classified.limitations()));
                } catch (IllegalArgumentException exception) {
                    SurveyLimitation limitation = new SurveyLimitation("CHUNK_CLASSIFICATION_BOUNDED",
                            source.metadata().dimension() + "/" + chunk.chunkX() + "," + chunk.chunkZ(),
                            "The bounded classifier refused this chunk: " + exception.getMessage(), false, true);
                    limitations.add(limitation);
                    chunkSurveys.add(new ChunkSurvey(chunk.chunkX(), chunk.chunkZ(), SurveyStatus.SURVEY_PARTIAL,
                            chunk.compressedBytes(), chunk.uncompressedNbtBytes(), List.of(), List.of(),
                            List.of(limitation)));
                }
            }
            chunkSurveys.sort(Comparator.comparingInt(ChunkSurvey::chunkX).thenComparingInt(ChunkSurvey::chunkZ));
            SurveyStatus regionStatus = regionLimitations.isEmpty()
                    && chunkSurveys.stream().allMatch(chunk -> chunk.status() == SurveyStatus.COMPLETE)
                    ? SurveyStatus.COMPLETE : SurveyStatus.SURVEY_PARTIAL;
            RegionSurvey region = new RegionSurvey(snapshot.regionX(), snapshot.regionZ(), snapshot.relativePath(),
                    snapshot.fileFingerprint(), regionStatus, chunkSurveys, List.of(), regionLimitations);
            regionsByDimension.computeIfAbsent(source.metadata().dimension(), ignored -> new ArrayList<>()).add(region);
            long afterRegion = Math.max(0, monotonicMillis.getAsLong() - started);
            if (afterRegion > configuration.budget().maximumDurationMillis()) {
                runtimeBudgetExhausted = true;
                addDurationExhaustion(guard, identity, configuration, failures, limitations, afterRegion);
                break;
            }
        }

        long elapsedMillis = Math.max(0, monotonicMillis.getAsLong() - started);
        SurveyCoverage coverage = new SurveyCoverage(totalRegionFiles, regionsScanned, chunksScanned,
                chunksParsed, bytesRead, Duration.ofMillis(elapsedMillis),
                plan.status() == SurveyStatus.SURVEY_PARTIAL || runtimeBudgetExhausted);
        findings.sort(FINDING_ORDER);
        List<CandidateIndustrialZone> candidates = candidates(
                identity.value(), findings, parsedChunks, coverage, configuration.budget().maximumCandidateZones());
        if (findings.stream().noneMatch(FormalWorldSurveyRunner::isIndustrialSeed)) {
            failures.add(failure(guard, identity, FormalSurveyFailureCode.CREATE_INFRASTRUCTURE_NOT_FOUND,
                    FormalSurveyStage.INFRASTRUCTURE_DISCOVERY, NOT_APPLICABLE,
                    "No Create infrastructure was observed in the bounded sample",
                    List.of("regionsScanned=" + regionsScanned, "chunksParsed=" + chunksParsed),
                    "Review coverage and explicitly increase the bounded sample if needed"));
        }
        if (candidates.isEmpty()) {
            failures.add(failure(guard, identity, FormalSurveyFailureCode.CANDIDATE_ZONE_NOT_FOUND,
                    FormalSurveyStage.CANDIDATE_SELECTION, NOT_APPLICABLE,
                    "No candidate industrial zone could be derived from observed Create infrastructure",
                    List.of("industrialFindings="
                            + findings.stream().filter(FormalWorldSurveyRunner::isIndustrialSeed).count()),
                    "Do not select or approve a construction area; review or extend the bounded survey"));
        }
        if (metadataSources.size() < totalRegionFiles) {
            limitations.add(new SurveyLimitation("REGION_METADATA_SAMPLE_BOUNDED", "all-dimensions",
                    "Region headers were inspected for " + metadataSources.size() + " of " + totalRegionFiles
                            + " enumerated region files", false, false));
        }
        if (regionsScanned < totalRegionFiles) {
            limitations.add(new SurveyLimitation("UNSCANNED_REGION_BOUNDARIES", "all-dimensions",
                    "Unscanned regions remain unknown outside the explicit hot-sample budget", true, false));
        }

        List<InfrastructureFinding> topologyEligible = findings.stream()
                .filter(FormalWorldSurveyRunner::isTopologyRelevant).toList();
        List<InfrastructureFinding> topologyFindings = topologyEligible.stream()
                .limit(MAX_TOPOLOGY_FINDINGS).toList();
        boolean topologyComplete = regionsScanned == totalRegionFiles
                && topologyEligible.size() <= MAX_TOPOLOGY_FINDINGS;
        List<SurveyLimitation> topologyBoundaries = topologyComplete ? List.of() : List.of(
                new SurveyLimitation("TOPOLOGY_COVERAGE_BOUNDED", "all-dimensions",
                        "Offline adjacency covers only retained findings in scanned regions", true, false));
        OfflineTopologyGraph topology = topologyApproximator.approximate(
                topologyFindings, topologyComplete, topologyBoundaries);
        limitations.addAll(topologyBoundaries);

        List<DimensionSurvey> dimensions = identity.dimensions().stream().map(ResourceId::parse)
                .sorted(Comparator.comparing(ResourceId::toString))
                .map(dimension -> {
                    List<RegionSurvey> regions = new ArrayList<>(regionsByDimension.getOrDefault(dimension, List.of()));
                    regions.sort(Comparator.comparingInt(RegionSurvey::regionX).thenComparingInt(RegionSurvey::regionZ));
                    SurveyStatus status = regions.stream().anyMatch(region -> region.status() != SurveyStatus.COMPLETE)
                            ? SurveyStatus.SURVEY_PARTIAL : SurveyStatus.COMPLETE;
                    return new DimensionSurvey(dimension, status, regions, List.of(), List.of());
                }).toList();
        SurveyEvidence fingerprintEvidence = new SurveyEvidence(SurveyEvidenceSource.FINGERPRINT_MANIFEST,
                "level.dat", pre.manifestSha256(), configuration.parseGeneration(), SurveyConfidence.OBSERVED,
                "preFingerprint=" + pre.worldFingerprint(), true);
        SurveyStatus status = plan.status() == SurveyStatus.SURVEY_PARTIAL || runtimeBudgetExhausted
                || !failures.isEmpty()
                ? SurveyStatus.SURVEY_PARTIAL : SurveyStatus.COMPLETE;
        return new ScanDraft(status, dimensions, candidates, coverage, List.of(fingerprintEvidence),
                List.copyOf(limitations), List.copyOf(failures), topology);
    }

    private static void addDurationExhaustion(
            FormalWorldReadOnlyGuard guard,
            FormalWorldIdentity identity,
            FormalSurveyRunConfiguration configuration,
            List<FormalSurveyFailure> failures,
            List<SurveyLimitation> limitations,
            long elapsedMillis) {
        List<String> evidence = List.of("dimension=DURATION", "elapsedMillis=" + elapsedMillis,
                "maximumMillis=" + configuration.budget().maximumDurationMillis());
        failures.add(failure(guard, identity, FormalSurveyFailureCode.SURVEY_BUDGET_EXHAUSTED,
                FormalSurveyStage.REGION_METADATA, NOT_APPLICABLE,
                "The live survey duration exceeded its explicit single-threaded bound", evidence,
                "Review the partial evidence and explicitly change the duration budget if needed"));
        failures.add(failure(guard, identity, FormalSurveyFailureCode.SURVEY_PARTIAL,
                FormalSurveyStage.REGION_METADATA, NOT_APPLICABLE,
                "The survey stopped at the first observed duration boundary", evidence,
                "Treat every unscanned region and chunk as unknown"));
        limitations.add(new SurveyLimitation("SURVEY_BUDGET_EXHAUSTED", "DURATION",
                "The live survey duration boundary was reached", false, true));
    }

    private void verifyLevelIdentity(
            FormalWorldReadOnlyGuard guard,
            FormalWorldIdentity identity,
            NbtReadLimits limits,
            List<Path> files) throws IOException {
        Path level = files.stream().filter(path -> guard.relative(path).equals("level.dat"))
                .findFirst().orElseThrow(() -> refusal(guard, identity,
                        FormalSurveyFailureCode.LEVEL_DAT_UNREADABLE, FormalSurveyStage.LEVEL_METADATA,
                        "level.dat", "The selected formal world has no ordinary level.dat",
                        List.of("selected=" + identity.value()),
                        "Stop and repeat guarded formal save discovery"));
        FormalLevelMetadata metadata = new OfflineLevelDatReader(guard, limits).read(level);
        boolean matches = metadata.levelName().equals(identity.levelName())
                && metadata.dataVersion() == identity.dataVersion()
                && metadata.versionName().equals(identity.versionName())
                && metadata.dimensions().equals(identity.dimensions());
        if (!matches) {
            throw refusal(guard, identity, FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED,
                    FormalSurveyStage.LEVEL_METADATA, "level.dat",
                    "level.dat identity no longer matches the uniquely selected formal world",
                    List.of("levelName=" + metadata.levelName(), "dataVersion=" + metadata.dataVersion(),
                            "version=" + metadata.versionName(), "dimensions=" + metadata.dimensions()),
                    "Stop before region scanning and repeat unique formal save discovery");
        }
    }

    private static List<CandidateIndustrialZone> candidates(
            String worldIdentity,
            List<InfrastructureFinding> findings,
            Set<ChunkKey> parsedChunks,
            SurveyCoverage coverage,
            int maximumCandidates) {
        Map<ChunkKey, List<InfrastructureFinding>> createByChunk = new LinkedHashMap<>();
        findings.stream().filter(FormalWorldSurveyRunner::isIndustrialSeed).forEach(finding -> {
            SurveyLocation location = finding.location();
            createByChunk.computeIfAbsent(new ChunkKey(location.dimension(), location.chunkX(), location.chunkZ()),
                    ignored -> new ArrayList<>()).add(finding);
        });
        List<Map.Entry<ChunkKey, List<InfrastructureFinding>>> seeds = createByChunk.entrySet().stream()
                .sorted(Comparator.<Map.Entry<ChunkKey, List<InfrastructureFinding>>>comparingInt(
                                entry -> entry.getValue().size()).reversed()
                        .thenComparing(entry -> entry.getKey().dimension().toString())
                        .thenComparingInt(entry -> entry.getKey().chunkX())
                        .thenComparingInt(entry -> entry.getKey().chunkZ()))
                .limit(maximumCandidates).toList();
        List<CandidateZoneObservation> observations = new ArrayList<>();
        for (Map.Entry<ChunkKey, List<InfrastructureFinding>> seed : seeds) {
            ChunkKey key = seed.getKey();
            int centerY = seed.getValue().stream().mapToInt(value -> value.location().position().y())
                    .sorted().skip((seed.getValue().size() - 1L) / 2).findFirst().orElse(64);
            int minimumX = Math.addExact(Math.multiplyExact(key.chunkX(), 16), -24);
            int minimumZ = Math.addExact(Math.multiplyExact(key.chunkZ(), 16), -24);
            DeploymentBoundingBox bounds = new DeploymentBoundingBox(
                    new BlockPos3i(minimumX, centerY - 16, minimumZ),
                    new BlockPos3i(minimumX + 63, centerY + 31, minimumZ + 63));
            BlockPos3i anchor = new BlockPos3i(Math.multiplyExact(key.chunkX(), 16) + 8,
                    centerY, Math.multiplyExact(key.chunkZ(), 16) + 8);
            List<InfrastructureFinding> nearby = findings.stream()
                    .filter(FormalWorldSurveyRunner::isTopologyRelevant)
                    .filter(finding -> finding.location().dimension().equals(key.dimension()))
                    .filter(finding -> distanceToBox(finding.location().position(), bounds) <= 128)
                    .limit(65_536).toList();
            int minChunkX = Math.floorDiv(bounds.minimum().x(), 16);
            int maxChunkX = Math.floorDiv(bounds.maximum().x(), 16);
            int minChunkZ = Math.floorDiv(bounds.minimum().z(), 16);
            int maxChunkZ = Math.floorDiv(bounds.maximum().z(), 16);
            int totalChunks = Math.multiplyExact(maxChunkX - minChunkX + 1, maxChunkZ - minChunkZ + 1);
            int exploredChunks = 0;
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    if (parsedChunks.contains(new ChunkKey(key.dimension(), chunkX, chunkZ))) exploredChunks++;
                }
            }
            int blockEntities = (int) nearby.stream().filter(StorageFinding.class::isInstance).count();
            int playerRisk = (int) nearby.stream().filter(StorageFinding.class::isInstance)
                    .filter(finding -> !finding.resourceId().namespace().equals("create")).count();
            List<SurveyLimitation> candidateLimitations = List.of(
                    new SurveyLimitation("CLEAR_SPACE_NOT_VERIFIED", key.toString(),
                            "The offline sample does not prove that candidate volume is clear", true, true),
                    new SurveyLimitation("CLAIM_PERMISSION_UNKNOWN", key.toString(),
                            "Claim permission remains UNKNOWN and construction is refused", true, true),
                    new SurveyLimitation("PLAYER_BUILDING_BOUNDARY_UNKNOWN", key.toString(),
                            "Offline block evidence cannot reliably identify every player-built boundary", true, true));
            boolean complete = exploredChunks == totalChunks && !coverage.budgetExhausted();
            observations.add(new CandidateZoneObservation(worldIdentity, key.dimension(), bounds, List.of(anchor),
                    nearby, candidateLimitations, 0, exploredChunks, totalChunks, blockEntities, playerRisk,
                    Math.max(0, totalChunks - 1), CandidatePermissionStatus.UNKNOWN,
                    false, complete, coverage));
        }
        return new CandidateIndustrialZoneScorer().score(observations);
    }

    private static long distanceToBox(BlockPos3i position, DeploymentBoundingBox box) {
        return axisDistance(position.x(), box.minimum().x(), box.maximum().x())
                + axisDistance(position.y(), box.minimum().y(), box.maximum().y())
                + axisDistance(position.z(), box.minimum().z(), box.maximum().z());
    }

    private static long axisDistance(int value, int minimum, int maximum) {
        if (value < minimum) return (long) minimum - value;
        if (value > maximum) return (long) value - maximum;
        return 0;
    }

    private static boolean isIndustrialSeed(InfrastructureFinding finding) {
        return finding.resourceId().namespace().equals("create")
                && finding.category() != SurveyFindingCategory.OTHER_INFRASTRUCTURE
                && finding.category() != SurveyFindingCategory.STORAGE;
    }

    private static boolean isTopologyRelevant(InfrastructureFinding finding) {
        return finding.category() != SurveyFindingCategory.OTHER_INFRASTRUCTURE;
    }

    private static SurveyLimitation limitation(FormalSurveyFailure failure) {
        return new SurveyLimitation(failure.code().name(),
                failure.dimension() + "/" + failure.region() + "/" + failure.chunk(),
                failure.reason(), false, true);
    }

    private static FormalSurveyFailure invalidRegionHeader(
            FormalWorldReadOnlyGuard guard,
            FormalWorldIdentity identity,
            Path path,
            FormalFileFingerprintEntry entry) {
        String relative = guard.relative(path);
        ResourceId dimension = FormalRegionMetadataReader.dimension(relative)
                .orElse(ResourceId.parse("minecraft:overworld"));
        String fileName = path.getFileName().toString();
        String region = fileName.startsWith("r.") && fileName.endsWith(".mca")
                ? fileName.substring(2, fileName.length() - 4) : NOT_APPLICABLE;
        return new FormalSurveyFailure(FormalSurveyFailureCode.REGION_HEADER_INVALID,
                FormalSurveyStage.REGION_METADATA, identity.value(),
                guard.approvedSaveRootIdentity() + "/" + relative, dimension.toString(), region,
                NOT_APPLICABLE, entry.sha256(), "guarded-fs12",
                List.of("relativePath=" + relative, "sizeBytes=" + entry.sizeBytes()),
                "The region file is smaller than the mandatory 8192-byte Anvil header",
                "Retain the typed partial evidence, skip this region, and do not repair or rewrite it");
    }

    private static boolean sessionLockChanged(
            FormalWorldFingerprint pre,
            FormalWorldFingerprint post) {
        return !pre.manifest().stream().filter(entry -> entry.relativePath().equals("session.lock")).toList()
                .equals(post.manifest().stream().filter(entry -> entry.relativePath().equals("session.lock")).toList());
    }

    private static FormalReadAccessException refusal(
            FormalWorldReadOnlyGuard guard,
            FormalWorldIdentity identity,
            FormalSurveyFailureCode code,
            FormalSurveyStage stage,
            String relativePath,
            String reason,
            List<String> evidence,
            String safeNextStep) {
        return new FormalReadAccessException(failure(
                guard, identity, code, stage, relativePath, reason, evidence, safeNextStep));
    }

    private static FormalSurveyFailure failure(
            FormalWorldReadOnlyGuard guard,
            FormalWorldIdentity identity,
            FormalSurveyFailureCode code,
            FormalSurveyStage stage,
            String relativePath,
            String reason,
            List<String> evidence,
            String safeNextStep) {
        String path = relativePath.equals(NOT_APPLICABLE)
                ? guard.approvedSaveRootIdentity() : relativePath;
        return new FormalSurveyFailure(code, stage, identity.value(), path, NOT_APPLICABLE,
                NOT_APPLICABLE, NOT_APPLICABLE, NOT_APPLICABLE, "guarded-fs12",
                evidence, reason, safeNextStep);
    }

    private record ChunkKey(ResourceId dimension, int chunkX, int chunkZ) {
        @Override
        public String toString() {
            return dimension + "/" + chunkX + "," + chunkZ;
        }
    }

    private record ScanDraft(
            SurveyStatus status,
            List<DimensionSurvey> dimensions,
            List<CandidateIndustrialZone> candidates,
            SurveyCoverage coverage,
            List<SurveyEvidence> evidence,
            List<SurveyLimitation> limitations,
            List<FormalSurveyFailure> failures,
            OfflineTopologyGraph topology) {}
}
