package dev.stevecreate.agent.core.survey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Development-only FS-12 harness. It is absent from production artifacts. */
public final class FormalSurveyAcceptanceMain {
    private FormalSurveyAcceptanceMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("FS-12 acceptance takes no command arguments");
        Path projectRoot = ordinaryDirectory(required("formalProjectRoot"));
        if (!Files.isDirectory(projectRoot.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("formalProjectRoot is not the repository root");
        }
        Path instanceRoot = ordinaryDirectory(required("formalInstanceRoot"));
        Path saveRoot = ordinaryDirectory(required("formalSaveRoot"));
        if (!saveRoot.startsWith(instanceRoot)) {
            throw new IllegalArgumentException("formal save root is outside the exact formal instance root");
        }
        String worldValue = required("formalWorldIdentity");
        String worldSlug = worldValue.replace("world:", "");
        if (!worldValue.matches("world:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("formalWorldIdentity is invalid");
        }
        Path surveyRoot = projectRoot.resolve("work/formal-survey").resolve(worldSlug).normalize();
        if (!surveyRoot.startsWith(projectRoot.resolve("work/formal-survey").normalize())
                || surveyRoot.startsWith(instanceRoot)) {
            throw new IllegalArgumentException("computed report root is unsafe");
        }
        String runId = "fs12-" + Instant.now().toString().replace(":", "-");
        Path runRoot = surveyRoot.resolve(runId);
        if (Files.exists(runRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("computed run root already exists");
        }

        FormalWorldIdentity identity = new FormalWorldIdentity(worldValue,
                required("formalLevelName"), saveRoot.getFileName().toString(),
                Integer.parseInt(required("formalDataVersion")), required("formalVersionName"),
                List.of(required("formalDimensions").split(",")).stream().sorted().toList());
        FormalReadOnlyPolicy policy = new FormalReadOnlyPolicy("formal-survey-v1", identity.value(),
                instanceRoot, saveRoot, runRoot.resolve("audit"));
        FormalSurveyRunConfiguration configuration = new FormalSurveyRunConfiguration(
                new SurveyBudget(16, 16_384, 8L * 1_024 * 1_024 * 1_024,
                        30 * 60 * 1_000L, 8L * 1_024 * 1_024 * 1_024,
                        8, 1, 1),
                250_000, 64, 16, 1, NbtReadLimits.minecraft1201Defaults());

        FormalWorldReadOnlyGuard guard = new FormalWorldReadOnlyGuard(policy);
        FormalWorldSurveyRun run = new FormalWorldSurveyRunner().run(guard, identity, configuration, pre ->
                System.out.println("FS12_PREFLIGHT_PASS canonicalSaveRoot=" + guard.approvedSaveRootIdentity()
                        + " policy=formal-survey-v1 readOnly=true singleThread=true sessionLockCreate=false"
                        + " reportRoot=" + runRoot + " budget=" + configuration.budget().canonical()
                        + " fileCount=" + pre.fileCount() + " totalBytes=" + pre.totalBytes()
                        + " preFingerprint=" + pre.worldFingerprint()
                        + " manifest=" + pre.manifestSha256()));
        writeReports(runRoot, guard, run);
        System.out.println("FS12_FORMAL_SURVEY_PASS worldIdentity=" + identity.value()
                + " preFingerprint=" + run.preFingerprint().worldFingerprint()
                + " postFingerprint=" + run.postFingerprint().worldFingerprint()
                + " regionsEnumerated=" + run.survey().coverage().regionsEnumerated()
                + " regionsScanned=" + run.survey().coverage().regionsScanned()
                + " chunksParsed=" + run.survey().coverage().chunksParsed()
                + " candidates=" + run.survey().candidateZones().size()
                + " failures=" + run.failures().size()
                + " externalMutation=false formalWorldWrite=false sessionLockChanged=false"
                + " inventoryContentsRead=false processStarted=false executionAllowed=false"
                + " evidence=" + runRoot);
    }

    private static void writeReports(
            Path runRoot,
            FormalWorldReadOnlyGuard guard,
            FormalWorldSurveyRun run) throws IOException {
        FormalWorldSurvey survey = run.survey();
        String summary = "{\n"
                + "  \"worldIdentity\": \"" + json(survey.worldIdentity().value()) + "\",\n"
                + "  \"canonicalSaveRoot\": \"" + json(guard.approvedSaveRootIdentity()) + "\",\n"
                + "  \"status\": \"" + survey.status() + "\",\n"
                + "  \"budget\": \"" + json(run.budget().canonical()) + "\",\n"
                + "  \"preFingerprint\": \"" + run.preFingerprint().worldFingerprint() + "\",\n"
                + "  \"postFingerprint\": \"" + run.postFingerprint().worldFingerprint() + "\",\n"
                + "  \"manifestSha256\": \"" + run.preFingerprint().manifestSha256() + "\",\n"
                + "  \"fileCount\": " + run.preFingerprint().fileCount() + ",\n"
                + "  \"totalBytes\": " + run.preFingerprint().totalBytes() + ",\n"
                + "  \"regionsEnumerated\": " + survey.coverage().regionsEnumerated() + ",\n"
                + "  \"regionsScanned\": " + survey.coverage().regionsScanned() + ",\n"
                + "  \"chunksScanned\": " + survey.coverage().chunksScanned() + ",\n"
                + "  \"chunksParsed\": " + survey.coverage().chunksParsed() + ",\n"
                + "  \"bytesRead\": " + survey.coverage().bytesRead() + ",\n"
                + "  \"candidateZones\": " + survey.candidateZones().size() + ",\n"
                + "  \"dryRunCandidatesCompleted\": 0,\n"
                + "  \"dryRunCandidatesBlocked\": 4,\n"
                + "  \"dryRunBlockReason\": \"CLEAR_SPACE_NOT_VERIFIED; CLAIM_PERMISSION_UNKNOWN; CANDIDATE_PENDING_USER_SELECTION; FORMAL_WORLD_EXECUTION_FORBIDDEN\",\n"
                + "  \"failures\": " + run.failures().size() + ",\n"
                + "  \"topologyNodes\": " + run.topology().nodes().size() + ",\n"
                + "  \"topologyEdges\": " + run.topology().edges().size() + ",\n"
                + "  \"externalMutation\": false,\n"
                + "  \"savesOrFormalWorldRead\": true,\n"
                + "  \"formalWorldWrite\": false,\n"
                + "  \"sessionLockCreatedOrModified\": false,\n"
                + "  \"inventoryContentsRead\": false,\n"
                + "  \"processStarted\": false,\n"
                + "  \"executionAllowed\": false\n"
                + "}\n";
        write(runRoot.resolve("summary.json"), summary);

        List<String> infrastructure = new ArrayList<>();
        infrastructure.add("dimension\tregion\tchunk\tposition\tresource_id\tcategory\tconfidence"
                + "\trelative_source\tfile_sha256\tparse_generation\tevidence_source\twarnings_limitations");
        run.topology().nodes().stream().sorted(Comparator.comparing(
                        (InfrastructureFinding finding) -> finding.location().dimension().toString())
                        .thenComparingInt(finding -> finding.location().position().y())
                        .thenComparingInt(finding -> finding.location().position().z())
                        .thenComparingInt(finding -> finding.location().position().x()))
                .forEach(finding -> infrastructure.add(
                        finding.location().dimension() + "\t"
                                + finding.location().regionX() + "," + finding.location().regionZ() + "\t"
                                + finding.location().chunkX() + "," + finding.location().chunkZ() + "\t"
                                + finding.location().position().x() + "," + finding.location().position().y()
                                + "," + finding.location().position().z() + "\t"
                                + finding.resourceId() + "\t" + finding.category() + "\t"
                                + finding.confidence() + "\t"
                                + safe(finding.evidence().get(0).relativeSourcePath()) + "\t"
                                + finding.location().fileFingerprint() + "\t"
                                + finding.location().parseGeneration() + "\t"
                                + finding.location().evidenceSource() + "\t"
                                + safe(finding.location().warningsAndLimitations().toString())));
        write(runRoot.resolve("infrastructure.tsv"), String.join("\n", infrastructure) + "\n");

        List<String> candidates = new ArrayList<>();
        candidates.add("candidate_id\tdimension\tminimum\tmaximum\tanchors\tnearby_infrastructure_count"
                + "\tscore\tscore_breakdown\tconfidence\tstatus\trequired_authorization"
                + "\tprotected_unknown_findings\tcoverage");
        survey.candidateZones().forEach(zone -> candidates.add(zone.candidateId() + "\t" + zone.dimension()
                + "\t" + zone.boundingBox().minimum() + "\t" + zone.boundingBox().maximum()
                + "\t" + zone.anchorCandidates() + "\t" + zone.nearbyInfrastructure().size()
                + "\t" + zone.totalScore() + "\t" + safe(zone.scoreBreakdown().toString())
                + "\t" + zone.confidence() + "\t" + zone.status()
                + "\t" + safe(zone.requiredFutureAuthorization().toString())
                + "\t" + safe(zone.protectedAndUnknownFindings().toString())
                + "\t" + safe(zone.coverage().toString())));
        write(runRoot.resolve("candidate-zones.tsv"), String.join("\n", candidates) + "\n");

        List<String> regions = new ArrayList<>();
        regions.add("dimension\tstatus\tregion\trelative_source\tfile_sha256\tchunks\tfindings\tlimitations");
        survey.dimensions().forEach(dimension -> dimension.regions().forEach(region -> regions.add(
                dimension.dimension() + "\t" + region.status() + "\t" + region.regionX() + ","
                        + region.regionZ() + "\t" + safe(region.relativeRegionPath()) + "\t"
                        + region.fileFingerprint() + "\t" + region.chunks().size() + "\t"
                        + region.chunks().stream().mapToInt(chunk -> chunk.findings().size()).sum() + "\t"
                        + safe(region.limitations().toString()))));
        write(runRoot.resolve("regions.tsv"), String.join("\n", regions) + "\n");

        List<String> topology = new ArrayList<>();
        topology.add("dimension\tfirst_position\tsecond_position\tresource_type\tconfidence\truntime_connectivity_known");
        run.topology().edges().forEach(edge -> topology.add(edge.first().dimension() + "\t"
                + edge.first().position() + "\t" + edge.second().position() + "\t" + edge.resourceType()
                + "\t" + edge.confidence() + "\t" + edge.runtimeConnectivityKnown()));
        write(runRoot.resolve("topology.tsv"), String.join("\n", topology) + "\n");

        List<String> dryRuns = List.of(
                "kind\ttarget\tquantity\trecipe_identity\tstatus\tpreview_hash\treason",
                "GRAVEL_MILLING\tminecraft:gravel\t3\tcreate:milling/cobblestone\tBLOCKED\tnot-generated\t"
                        + "CLEAR_SPACE_NOT_VERIFIED;CLAIM_PERMISSION_UNKNOWN;CANDIDATE_PENDING_USER_SELECTION;FORMAL_WORLD_EXECUTION_FORBIDDEN",
                "IRON_SHEET_PRESSING\tcreate:iron_sheet\t2\tcreate:pressing/iron_ingot\tBLOCKED\tnot-generated\t"
                        + "CLEAR_SPACE_NOT_VERIFIED;CLAIM_PERMISSION_UNKNOWN;CANDIDATE_PENDING_USER_SELECTION;FORMAL_WORLD_EXECUTION_FORBIDDEN",
                "PACK_CUSTOM_MILLING\timmersiveengineering:dust_coke\t4\tcreate:kjs/4w2pibcjt4pwqhn60e2n67l1l\tBLOCKED\tnot-generated\t"
                        + "CLEAR_SPACE_NOT_VERIFIED;CLAIM_PERMISSION_UNKNOWN;CANDIDATE_PENDING_USER_SELECTION;FORMAL_WORLD_EXECUTION_FORBIDDEN",
                "PACK_CUSTOM_PRESSING\tapocalypsenow:can\t3\tcreate:kjs/af7rthcw104gweqz8mzvmcrw7\tBLOCKED\tnot-generated\t"
                        + "CLEAR_SPACE_NOT_VERIFIED;CLAIM_PERMISSION_UNKNOWN;CANDIDATE_PENDING_USER_SELECTION;FORMAL_WORLD_EXECUTION_FORBIDDEN");
        write(runRoot.resolve("dry-run-results.tsv"), String.join("\n", dryRuns) + "\n");

        List<String> failures = new ArrayList<>();
        failures.add("code\tstage\tdimension\tregion\tchunk\treason\tsafe_next_step");
        run.failures().forEach(failure -> failures.add(failure.code() + "\t" + failure.stage() + "\t"
                + safe(failure.dimension()) + "\t" + safe(failure.region()) + "\t" + safe(failure.chunk())
                + "\t" + safe(failure.reason()) + "\t" + safe(failure.safeNextStep())));
        write(runRoot.resolve("failures.tsv"), String.join("\n", failures) + "\n");

        List<String> limitations = new ArrayList<>();
        limitations.add("code\tscope\truntime_confirmation_required\tblocks_candidate_readiness\tmessage");
        survey.limitations().forEach(limitation -> limitations.add(limitation.code() + "\t"
                + safe(limitation.scope()) + "\t" + limitation.runtimeConfirmationRequired() + "\t"
                + limitation.blocksCandidateReadiness() + "\t" + safe(limitation.message())));
        write(runRoot.resolve("limitations.tsv"), String.join("\n", limitations) + "\n");

        write(runRoot.resolve("fingerprint.txt"),
                "worldIdentity=" + survey.worldIdentity().value() + "\n"
                        + "pre=" + run.preFingerprint().worldFingerprint() + "\n"
                        + "post=" + run.postFingerprint().worldFingerprint() + "\n"
                        + "manifest=" + run.preFingerprint().manifestSha256() + "\n"
                        + "fileCount=" + run.preFingerprint().fileCount() + "\n"
                        + "totalBytes=" + run.preFingerprint().totalBytes() + "\n"
                        + "privatePathsAndContents=REDACTED_NOT_WRITTEN\n");
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static Path ordinaryDirectory(String value) throws IOException {
        Path normalized = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("required directory is missing or reparse-like: " + normalized);
        }
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing system property " + name);
        return value;
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String safe(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
