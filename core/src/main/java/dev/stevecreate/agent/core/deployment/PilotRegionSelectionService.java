package dev.stevecreate.agent.core.deployment;

import static dev.stevecreate.agent.core.deployment.WritableTestWorldFailure.UNAVAILABLE;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Pure region selection/preview/confirmation state machine. */
public final class PilotRegionSelectionService {
    public static final int MAX_X = 64;
    public static final int MAX_Z = 64;
    public static final int MAX_Y = 32;
    public static final int DEFAULT_BELOW = 2;

    public PilotRegionResult<PilotRegionSelection> selectHere(
            WritableTestWorldIdentity world,
            ResourceId dimension,
            BlockPos3i center,
            int sizeX,
            int sizeZ,
            int sizeY,
            int minimumBuildY,
            int maximumBuildY,
            String playerIdentity,
            String sessionIdentity,
            Instant selectedAt,
            Instant expiresAt,
            String policyVersion) {
        Objects.requireNonNull(center, "center");
        int minX;
        int minY;
        int minZ;
        try {
            minX = Math.subtractExact(center.x(), sizeX / 2);
            minY = Math.subtractExact(center.y(), DEFAULT_BELOW);
            minZ = Math.subtractExact(center.z(), sizeZ / 2);
        } catch (ArithmeticException overflow) {
            return regionRefusal(WritableTestWorldFailureCode.REGION_TOO_LARGE, world,
                    dimension, UNAVAILABLE, sessionIdentity, "Region coordinates overflowed",
                    "Select a bounded region inside the current dimension");
        }
        BlockPos3i first = new BlockPos3i(minX, minY, minZ);
        BlockPos3i second;
        try {
            second = new BlockPos3i(Math.addExact(minX, sizeX - 1),
                    Math.addExact(minY, sizeY - 1), Math.addExact(minZ, sizeZ - 1));
        } catch (ArithmeticException overflow) {
            return regionRefusal(WritableTestWorldFailureCode.REGION_TOO_LARGE, world,
                    dimension, UNAVAILABLE, sessionIdentity, "Region coordinates overflowed",
                    "Select a bounded region inside the current dimension");
        }
        return selectCorners(world, dimension, first, second, minimumBuildY, maximumBuildY,
                playerIdentity, sessionIdentity, selectedAt, expiresAt, policyVersion);
    }

    public PilotRegionResult<PilotRegionSelection> selectCorners(
            WritableTestWorldIdentity world,
            ResourceId dimension,
            BlockPos3i pos1,
            BlockPos3i pos2,
            int minimumBuildY,
            int maximumBuildY,
            String playerIdentity,
            String sessionIdentity,
            Instant selectedAt,
            Instant expiresAt,
            String policyVersion) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(pos1, "pos1");
        Objects.requireNonNull(pos2, "pos2");
        Objects.requireNonNull(selectedAt, "selectedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        BlockPos3i minimum = new BlockPos3i(Math.min(pos1.x(), pos2.x()),
                Math.min(pos1.y(), pos2.y()), Math.min(pos1.z(), pos2.z()));
        BlockPos3i maximum = new BlockPos3i(Math.max(pos1.x(), pos2.x()),
                Math.max(pos1.y(), pos2.y()), Math.max(pos1.z(), pos2.z()));
        DeploymentBoundingBox bounds = new DeploymentBoundingBox(minimum, maximum);
        long x = (long) maximum.x() - minimum.x() + 1;
        long y = (long) maximum.y() - minimum.y() + 1;
        long z = (long) maximum.z() - minimum.z() + 1;
        if (x < 1 || y < 1 || z < 1 || x > MAX_X || y > MAX_Y || z > MAX_Z) {
            return regionRefusal(WritableTestWorldFailureCode.REGION_TOO_LARGE, world,
                    dimension, bounds.toString(), sessionIdentity,
                    "Region exceeds 64x64x32", "Select a smaller region in the current dimension");
        }
        if (minimum.y() < minimumBuildY || maximum.y() >= maximumBuildY) {
            return regionRefusal(WritableTestWorldFailureCode.REGION_INSUFFICIENT_SPACE, world,
                    dimension, bounds.toString(), sessionIdentity,
                    "Region exceeds the current dimension build height",
                    "Move vertically until the entire region is inside build height");
        }
        String canonical = world.value() + "\n" + dimension + "\n"
                + minimum.x() + "," + minimum.y() + "," + minimum.z() + "\n"
                + maximum.x() + "," + maximum.y() + "," + maximum.z() + "\n"
                + playerIdentity + "\n" + sessionIdentity + "\n" + expiresAt + "\n" + policyVersion;
        String regionHash = sha256(canonical);
        return PilotRegionResult.success(new PilotRegionSelection(
                "region-selection:" + regionHash, world.value(), dimension, bounds, regionHash,
                playerIdentity, sessionIdentity, selectedAt, expiresAt, policyVersion,
                PilotRegionState.SELECTED, UNAVAILABLE));
    }

    public PilotRegionResult<PilotRegionSelection> preview(
            PilotRegionSelection selection,
            String currentWorldIdentity,
            ResourceId currentDimension,
            String currentWorldFingerprint,
            Instant evaluatedAt) {
        Objects.requireNonNull(selection, "selection");
        if (!selection.writableWorldIdentity().equals(currentWorldIdentity)) {
            return selectionRefusal(WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                    WritableTestWorldStage.REGION_PREVIEW, selection, currentWorldFingerprint,
                    "Selection belongs to a different test world", "Select a new region in this world");
        }
        if (!selection.dimension().equals(currentDimension)) {
            return selectionRefusal(WritableTestWorldFailureCode.REGION_DIMENSION_MISMATCH,
                    WritableTestWorldStage.REGION_PREVIEW, selection, currentWorldFingerprint,
                    "Selection belongs to a different dimension", "Return to the selected dimension");
        }
        if (!evaluatedAt.isBefore(selection.expiresAt())) {
            return selectionRefusal(WritableTestWorldFailureCode.REGION_HASH_STALE,
                    WritableTestWorldStage.REGION_PREVIEW, selection, currentWorldFingerprint,
                    "Region selection expired", "Select and preview a fresh region");
        }
        return PilotRegionResult.success(new PilotRegionSelection(
                selection.selectionIdentity(), selection.writableWorldIdentity(), selection.dimension(),
                selection.bounds(), selection.regionHash(), selection.playerIdentity(),
                selection.sessionIdentity(), selection.selectedAt(), selection.expiresAt(),
                selection.policyVersion(), PilotRegionState.PREVIEWED, currentWorldFingerprint));
    }

    public PilotRegionResult<PilotRegionConfirmation> confirm(
            PilotRegionSelection selection,
            String currentWorldIdentity,
            ResourceId currentDimension,
            String currentWorldFingerprint,
            String currentPlayerIdentity,
            String currentSessionIdentity,
            Instant evaluatedAt) {
        Objects.requireNonNull(selection, "selection");
        if (selection.state() != PilotRegionState.PREVIEWED) {
            return selectionRefusal(WritableTestWorldFailureCode.PILOT_PREVIEW_REQUIRED,
                    WritableTestWorldStage.REGION_CONFIRMATION, selection, currentWorldFingerprint,
                    "Region must be previewed before confirmation", "Run pilot region preview first");
        }
        if (!selection.writableWorldIdentity().equals(currentWorldIdentity)) {
            return selectionRefusal(WritableTestWorldFailureCode.TEST_WORLD_IDENTITY_MISMATCH,
                    WritableTestWorldStage.REGION_CONFIRMATION, selection, currentWorldFingerprint,
                    "Preview belongs to a different test world", "Select and preview a region in this world");
        }
        if (!selection.dimension().equals(currentDimension)) {
            return selectionRefusal(WritableTestWorldFailureCode.REGION_DIMENSION_MISMATCH,
                    WritableTestWorldStage.REGION_CONFIRMATION, selection, currentWorldFingerprint,
                    "Preview belongs to a different dimension", "Return to the previewed dimension");
        }
        if (!selection.playerIdentity().equals(currentPlayerIdentity)
                || !selection.sessionIdentity().equals(currentSessionIdentity)) {
            return selectionRefusal(WritableTestWorldFailureCode.REGION_HASH_STALE,
                    WritableTestWorldStage.REGION_CONFIRMATION, selection, currentWorldFingerprint,
                    "Player or session identity changed after preview", "Create a fresh preview in this session");
        }
        if (!selection.previewWorldFingerprint().equals(currentWorldFingerprint)) {
            return selectionRefusal(WritableTestWorldFailureCode.PILOT_PREVIEW_STALE,
                    WritableTestWorldStage.REGION_CONFIRMATION, selection, currentWorldFingerprint,
                    "World fingerprint changed after preview", "Inspect changes and create a fresh preview");
        }
        if (!evaluatedAt.isBefore(selection.expiresAt())) {
            return selectionRefusal(WritableTestWorldFailureCode.REGION_HASH_STALE,
                    WritableTestWorldStage.REGION_CONFIRMATION, selection, currentWorldFingerprint,
                    "Region preview expired", "Create a fresh selection and preview");
        }
        String identity = "region-confirmation:" + sha256(selection.regionHash() + "\n"
                + currentPlayerIdentity + "\n" + currentSessionIdentity + "\n"
                + currentWorldFingerprint + "\n" + selection.policyVersion());
        return PilotRegionResult.success(new PilotRegionConfirmation(
                identity, selection.writableWorldIdentity(), selection.dimension(), selection.bounds(),
                selection.regionHash(), selection.playerIdentity(), selection.sessionIdentity(),
                selection.expiresAt(), selection.policyVersion(), currentWorldFingerprint));
    }

    private static <T> PilotRegionResult<T> regionRefusal(
            WritableTestWorldFailureCode code,
            WritableTestWorldIdentity world,
            ResourceId dimension,
            String region,
            String session,
            String reason,
            String next) {
        return PilotRegionResult.refused(new WritableTestWorldFailure(
                code, WritableTestWorldStage.REGION_SELECTION, world.testInstanceIdentity(), world.value(),
                CanonicalWorldPaths.identity(world.canonicalWorldPath()), world.worldFingerprint(), region,
                UNAVAILABLE, UNAVAILABLE, session, List.of("dimension=" + dimension), reason, next));
    }

    private static <T> PilotRegionResult<T> selectionRefusal(
            WritableTestWorldFailureCode code,
            WritableTestWorldStage stage,
            PilotRegionSelection selection,
            String fingerprint,
            String reason,
            String next) {
        return PilotRegionResult.refused(new WritableTestWorldFailure(
                code, stage, UNAVAILABLE, selection.writableWorldIdentity(), UNAVAILABLE,
                fingerprint, selection.bounds().toString(), selection.regionHash(), UNAVAILABLE,
                selection.sessionIdentity(), List.of("selection=" + selection.selectionIdentity(),
                        "state=" + selection.state()), reason, next));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
