package dev.stevecreate.agent.core.survey;

import java.util.Set;

public record NbtReadLimits(
        long maxRegionBytes,
        int maxCompressedNbtBytes,
        int maxUncompressedNbtBytes,
        int maxDepth,
        int maxArrayLength,
        int maxListLength,
        int maxCompoundEntries,
        Set<Integer> supportedDataVersions) {
    public NbtReadLimits {
        if (maxRegionBytes < 8_192 || maxRegionBytes > Integer.MAX_VALUE
                || maxCompressedNbtBytes <= 0 || maxCompressedNbtBytes > 256 * 1_024 * 1_024
                || maxUncompressedNbtBytes <= 0 || maxUncompressedNbtBytes > 512 * 1_024 * 1_024
                || maxDepth <= 0 || maxDepth > 512
                || maxArrayLength <= 0 || maxArrayLength > 16_777_216
                || maxListLength <= 0 || maxListLength > 16_777_216
                || maxCompoundEntries <= 0 || maxCompoundEntries > 1_000_000) {
            throw new IllegalArgumentException("NBT limits are invalid");
        }
        supportedDataVersions = Set.copyOf(supportedDataVersions);
        if (supportedDataVersions.isEmpty() || supportedDataVersions.stream().anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException("supportedDataVersions are invalid");
        }
    }

    public static NbtReadLimits minecraft1201Defaults() {
        return new NbtReadLimits(
                512L * 1_024 * 1_024,
                16 * 1_024 * 1_024,
                64 * 1_024 * 1_024,
                128,
                1_048_576,
                262_144,
                262_144,
                Set.of(3_465));
    }
}
