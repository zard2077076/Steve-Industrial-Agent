package dev.stevecreate.agent.core.siteprep;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact, loader-neutral authority envelope for one bounded cut-and-fill operation. */
public record TerrainGradingSpecification(
        int minimumX,
        int maximumX,
        int minimumZ,
        int maximumZ,
        int surfaceY,
        int clearanceHeight,
        int maximumFillDepth,
        ResourceId fillBlockId,
        String fillStateFingerprint,
        List<BlockPos3i> removalPositions,
        List<BlockPos3i> placementPositions,
        List<BlockPos3i> expectedFillPositions,
        Map<BlockPos3i, String> initialStateFingerprints,
        String specificationHash) {
    public static final int MAX_MUTATIONS = 4_096;
    public static final int MAX_CLEARANCE_HEIGHT = 32;
    public static final int MAX_FILL_DEPTH = 32;

    public TerrainGradingSpecification {
        Objects.requireNonNull(fillBlockId, "fillBlockId");
        fillStateFingerprint = SitePreparationHashes.hash(
                fillStateFingerprint, "fillStateFingerprint");
        removalPositions = orderedDistinct(removalPositions, "removalPositions");
        placementPositions = orderedDistinct(placementPositions, "placementPositions");
        expectedFillPositions = orderedDistinct(expectedFillPositions, "expectedFillPositions");
        initialStateFingerprints = normalizedFingerprints(initialStateFingerprints);
        specificationHash = SitePreparationHashes.hash(specificationHash, "specificationHash");
        if (minimumX > maximumX || minimumZ > maximumZ
                || clearanceHeight < 1 || clearanceHeight > MAX_CLEARANCE_HEIGHT
                || maximumFillDepth < 1 || maximumFillDepth > MAX_FILL_DEPTH) {
            throw new IllegalArgumentException("terrain grading bounds are invalid");
        }
        long width = Math.addExact(Math.subtractExact((long) maximumX, minimumX), 1L);
        long depth = Math.addExact(Math.subtractExact((long) maximumZ, minimumZ), 1L);
        long area = Math.multiplyExact(width, depth);
        if (area < 1 || area > MAX_MUTATIONS
                || Math.addExact(removalPositions.size(), placementPositions.size())
                > MAX_MUTATIONS) {
            throw new IllegalArgumentException("terrain grading mutation budget exceeded");
        }
        Set<BlockPos3i> expected = Set.copyOf(expectedFillPositions);
        if (!expected.containsAll(placementPositions)) {
            throw new IllegalArgumentException("placed terrain must be part of the expected fill");
        }
        Set<BlockPos3i> mutated = new LinkedHashSet<>(removalPositions);
        mutated.addAll(placementPositions);
        if (!initialStateFingerprints.keySet().equals(mutated)) {
            throw new IllegalArgumentException("initial fingerprints must bind every mutation");
        }
        for (BlockPos3i position : mutated) requireInside(position, minimumX, maximumX,
                minimumZ, maximumZ, surfaceY, maximumFillDepth, clearanceHeight);
        for (BlockPos3i position : expectedFillPositions) {
            requireInside(position, minimumX, maximumX, minimumZ, maximumZ, surfaceY,
                    maximumFillDepth, clearanceHeight);
            if (position.y() > surfaceY) {
                throw new IllegalArgumentException("expected fill is above the surface grade");
            }
        }
        for (int x = minimumX; x <= maximumX; x++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                if (!expected.contains(new BlockPos3i(x, surfaceY, z))) {
                    throw new IllegalArgumentException("every grading column needs an exact surface");
                }
            }
        }
        String actual = canonicalHash(minimumX, maximumX, minimumZ, maximumZ, surfaceY,
                clearanceHeight, maximumFillDepth, fillBlockId, fillStateFingerprint,
                removalPositions, placementPositions, expectedFillPositions,
                initialStateFingerprints);
        if (!specificationHash.equals(actual)) {
            throw new IllegalArgumentException("terrain grading specification hash mismatch");
        }
    }

    public static TerrainGradingSpecification create(
            int firstX,
            int firstZ,
            int secondX,
            int secondZ,
            int surfaceY,
            int clearanceHeight,
            int maximumFillDepth,
            ResourceId fillBlockId,
            String fillStateFingerprint,
            List<BlockPos3i> removalPositions,
            List<BlockPos3i> placementPositions,
            List<BlockPos3i> expectedFillPositions,
            Map<BlockPos3i, String> initialStateFingerprints) {
        int minimumX = Math.min(firstX, secondX);
        int maximumX = Math.max(firstX, secondX);
        int minimumZ = Math.min(firstZ, secondZ);
        int maximumZ = Math.max(firstZ, secondZ);
        List<BlockPos3i> removals = orderedDistinct(removalPositions, "removalPositions");
        List<BlockPos3i> placements = orderedDistinct(placementPositions, "placementPositions");
        List<BlockPos3i> expected = orderedDistinct(expectedFillPositions, "expectedFillPositions");
        Map<BlockPos3i, String> fingerprints = normalizedFingerprints(initialStateFingerprints);
        String hash = canonicalHash(minimumX, maximumX, minimumZ, maximumZ, surfaceY,
                clearanceHeight, maximumFillDepth, fillBlockId, fillStateFingerprint,
                removals, placements, expected, fingerprints);
        return new TerrainGradingSpecification(minimumX, maximumX, minimumZ, maximumZ,
                surfaceY, clearanceHeight, maximumFillDepth, fillBlockId,
                fillStateFingerprint, removals, placements, expected, fingerprints, hash);
    }

    public int mutationCount() {
        return Math.addExact(removalPositions.size(), placementPositions.size());
    }

    public boolean isExpectedFill(BlockPos3i position) {
        return expectedFillPositions.contains(position);
    }

    private static void requireInside(
            BlockPos3i position,
            int minimumX,
            int maximumX,
            int minimumZ,
            int maximumZ,
            int surfaceY,
            int maximumFillDepth,
            int clearanceHeight) {
        if (position.x() < minimumX || position.x() > maximumX
                || position.z() < minimumZ || position.z() > maximumZ
                || position.y() < surfaceY - maximumFillDepth
                || position.y() > surfaceY + clearanceHeight) {
            throw new IllegalArgumentException("terrain grading position is outside authority");
        }
    }

    private static List<BlockPos3i> orderedDistinct(List<BlockPos3i> supplied, String name) {
        Objects.requireNonNull(supplied, name);
        List<BlockPos3i> copy = supplied.stream()
                .map(value -> Objects.requireNonNull(value, name + " entry"))
                .sorted(Comparator.comparingInt(BlockPos3i::y)
                        .thenComparingInt(BlockPos3i::x)
                        .thenComparingInt(BlockPos3i::z))
                .toList();
        if (new LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(name + " contains duplicates");
        }
        return copy;
    }

    private static Map<BlockPos3i, String> normalizedFingerprints(
            Map<BlockPos3i, String> supplied) {
        Objects.requireNonNull(supplied, "initialStateFingerprints");
        List<Map.Entry<BlockPos3i, String>> entries = new ArrayList<>(supplied.entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.comparingInt(BlockPos3i::y)
                .thenComparingInt(BlockPos3i::x).thenComparingInt(BlockPos3i::z)));
        Map<BlockPos3i, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<BlockPos3i, String> entry : entries) {
            normalized.put(Objects.requireNonNull(entry.getKey(), "fingerprint position"),
                    SitePreparationHashes.hash(entry.getValue(), "initialStateFingerprint"));
        }
        return Map.copyOf(normalized);
    }

    private static String canonicalHash(
            int minimumX,
            int maximumX,
            int minimumZ,
            int maximumZ,
            int surfaceY,
            int clearanceHeight,
            int maximumFillDepth,
            ResourceId fillBlockId,
            String fillStateFingerprint,
            List<BlockPos3i> removalPositions,
            List<BlockPos3i> placementPositions,
            List<BlockPos3i> expectedFillPositions,
            Map<BlockPos3i, String> initialStateFingerprints) {
        return SitePreparationHashes.sha256(minimumX + "|" + maximumX + "|" + minimumZ
                + "|" + maximumZ + "|" + surfaceY + "|" + clearanceHeight + "|"
                + maximumFillDepth + "|" + fillBlockId + "|" + fillStateFingerprint + "|"
                + removalPositions + "|" + placementPositions + "|" + expectedFillPositions
                + "|" + initialStateFingerprints.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(BlockPos3i::y)
                        .thenComparingInt(BlockPos3i::x).thenComparingInt(BlockPos3i::z)))
                .toList());
    }
}
