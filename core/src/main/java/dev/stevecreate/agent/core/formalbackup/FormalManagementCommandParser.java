package dev.stevecreate.agent.core.formalbackup;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact B-10 grammar. Parsing creates no filesystem handle or operational authority. */
public final class FormalManagementCommandParser {
    private static final String NOT_APPLICABLE = "not-applicable";
    private final Set<String> worlds;
    private final Map<String, String> backupsToWorld;
    private final Map<String, String> candidatesToWorld;

    public FormalManagementCommandParser(
            Set<String> registeredWorlds,
            Map<String, String> registeredBackupsToWorld,
            Map<String, String> registeredCandidatesToWorld) {
        worlds = Set.copyOf(Objects.requireNonNull(registeredWorlds, "registeredWorlds"));
        backupsToWorld = Map.copyOf(Objects.requireNonNull(
                registeredBackupsToWorld, "registeredBackupsToWorld"));
        candidatesToWorld = Map.copyOf(Objects.requireNonNull(
                registeredCandidatesToWorld, "registeredCandidatesToWorld"));
        if (worlds.isEmpty() || worlds.size() > 64 || backupsToWorld.size() > 1_024
                || candidatesToWorld.size() > 4_096
                || worlds.stream().anyMatch(value -> !value.matches("world:[0-9a-f]{64}"))
                || backupsToWorld.entrySet().stream().anyMatch(entry ->
                        !entry.getKey().matches("formal-backup:[0-9a-f]{64}")
                                || !worlds.contains(entry.getValue()))
                || candidatesToWorld.entrySet().stream().anyMatch(entry ->
                        !entry.getKey().matches("formal-candidate:[0-9a-f]{64}")
                                || !worlds.contains(entry.getValue()))) {
            throw new IllegalArgumentException("formal command registries are invalid");
        }
    }

    public FormalManagementCommandPlan parse(String command) throws FormalBackupCommandException {
        Objects.requireNonNull(command, "command");
        if (command.isBlank() || command.length() > 8_192 || containsPathSyntax(command)) {
            throw failure(FormalBackupFailureCode.APPROVAL_REQUEST_INVALID, NOT_APPLICABLE,
                    NOT_APPLICABLE, NOT_APPLICABLE, "Command contains path or unsafe syntax");
        }
        String[] tokens = command.trim().split("\\s+");
        if (tokens.length != 4 || !tokens[0].equals("formal")) {
            throw failure(FormalBackupFailureCode.APPROVAL_REQUEST_INVALID, NOT_APPLICABLE,
                    NOT_APPLICABLE, NOT_APPLICABLE, "Command must contain exactly four registered tokens");
        }
        return switch (tokens[1] + " " + tokens[2]) {
            case "backup plan" -> world(FormalManagementCommandType.BACKUP_PLAN, tokens[3]);
            case "backup create" -> world(FormalManagementCommandType.BACKUP_CREATE, tokens[3]);
            case "backup verify" -> backup(FormalManagementCommandType.BACKUP_VERIFY, tokens[3]);
            case "backup restore-drill" -> backup(FormalManagementCommandType.BACKUP_RESTORE_DRILL, tokens[3]);
            case "deployment candidates" -> world(FormalManagementCommandType.DEPLOYMENT_CANDIDATES, tokens[3]);
            case "deployment approval-request" -> candidate(
                    FormalManagementCommandType.DEPLOYMENT_APPROVAL_REQUEST, tokens[3]);
            case "deployment status" -> candidate(FormalManagementCommandType.DEPLOYMENT_STATUS, tokens[3]);
            default -> throw failure(FormalBackupFailureCode.APPROVAL_REQUEST_INVALID,
                    NOT_APPLICABLE, NOT_APPLICABLE, NOT_APPLICABLE,
                    "Command verb is not registered");
        };
    }

    private FormalManagementCommandPlan world(FormalManagementCommandType type, String world)
            throws FormalBackupCommandException {
        if (!worlds.contains(world)) throw failure(FormalBackupFailureCode.BACKUP_SOURCE_WORLD_MISMATCH,
                world, NOT_APPLICABLE, NOT_APPLICABLE, "WorldIdentity is not registered");
        return plan(type, Optional.of(world), Optional.empty(), Optional.empty());
    }

    private FormalManagementCommandPlan backup(FormalManagementCommandType type, String backup)
            throws FormalBackupCommandException {
        String world = backupsToWorld.get(backup);
        if (world == null) throw failure(FormalBackupFailureCode.BACKUP_VERIFICATION_FAILED,
                NOT_APPLICABLE, backup, NOT_APPLICABLE, "BackupIdentity is not registered");
        return plan(type, Optional.of(world), Optional.of(backup), Optional.empty());
    }

    private FormalManagementCommandPlan candidate(FormalManagementCommandType type, String candidate)
            throws FormalBackupCommandException {
        String world = candidatesToWorld.get(candidate);
        if (world == null) throw failure(FormalBackupFailureCode.DEPLOYMENT_CANDIDATE_MISSING,
                NOT_APPLICABLE, NOT_APPLICABLE, candidate, "CandidateIdentity is not registered");
        return plan(type, Optional.of(world), Optional.empty(), Optional.of(candidate));
    }

    private static FormalManagementCommandPlan plan(
            FormalManagementCommandType type,
            Optional<String> world,
            Optional<String> backup,
            Optional<String> candidate) {
        MessageDigest digest = FormalWorldBackupManifest.digest();
        FormalWorldBackupManifest.update(digest, type.name());
        FormalWorldBackupManifest.update(digest, world.orElse(NOT_APPLICABLE));
        FormalWorldBackupManifest.update(digest, backup.orElse(NOT_APPLICABLE));
        FormalWorldBackupManifest.update(digest, candidate.orElse(NOT_APPLICABLE));
        String key = "formal-management/" + type.name().toLowerCase().replace('_', '-') + "/"
                + HexFormat.of().formatHex(digest.digest()) + ".json";
        return new FormalManagementCommandPlan(type, world, backup, candidate, key,
                true, false, false, false, false, false, false);
    }

    private static FormalBackupCommandException failure(
            FormalBackupFailureCode code,
            String world,
            String backup,
            String candidate,
            String reason) {
        return new FormalBackupCommandException(new FormalBackupFailure(
                code, FormalBackupStage.REPORTING, world, backup, candidate,
                NOT_APPLICABLE, NOT_APPLICABLE, NOT_APPLICABLE, NOT_APPLICABLE,
                0, 0, NOT_APPLICABLE, "NOT_REQUESTED", List.of("commandAccepted=false"),
                reason, "Use only an exact B-10 command and a registered identity; paths are forbidden"));
    }

    private static boolean containsPathSyntax(String value) {
        return value.indexOf('\\') >= 0 || value.indexOf('/') >= 0 || value.contains("..")
                || value.contains(":\\") || value.contains("//") || value.indexOf('\u0000') >= 0;
    }
}
