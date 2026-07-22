package dev.stevecreate.agent.core.survey;

import java.util.List;

public record OfflineChunkSection(int sectionY, List<OfflineBlockState> palette, long[] packedBlockStates) {
    public OfflineChunkSection {
        palette = List.copyOf(palette);
        packedBlockStates = packedBlockStates.clone();
        if (palette.isEmpty() || palette.size() > 4_096 || packedBlockStates.length > 4_096) {
            throw new IllegalArgumentException("chunk section palette/data are invalid");
        }
        if (palette.size() > 1) {
            int expected = expectedPackedLongs(palette.size());
            if (packedBlockStates.length != expected) {
                throw new IllegalArgumentException("multi-entry palette has invalid packed data length");
            }
            for (int blockIndex = 0; blockIndex < 4_096; blockIndex++) {
                if (paletteIndex(packedBlockStates, palette.size(), blockIndex) >= palette.size()) {
                    throw new IllegalArgumentException("packed block state references outside its palette");
                }
            }
        } else if (packedBlockStates.length != 0) {
            throw new IllegalArgumentException("single-entry palette must not carry packed data");
        }
    }

    @Override
    public long[] packedBlockStates() {
        return packedBlockStates.clone();
    }

    public OfflineBlockState blockStateAt(int localX, int localY, int localZ) {
        if (localX < 0 || localX > 15 || localY < 0 || localY > 15 || localZ < 0 || localZ > 15) {
            throw new IllegalArgumentException("local block coordinates are outside one section");
        }
        int blockIndex = (localY << 8) | (localZ << 4) | localX;
        int paletteIndex = palette.size() == 1 ? 0 : paletteIndex(packedBlockStates, palette.size(), blockIndex);
        return palette.get(paletteIndex);
    }

    static int expectedPackedLongs(int paletteSize) {
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        int valuesPerLong = 64 / bits;
        return (4_096 + valuesPerLong - 1) / valuesPerLong;
    }

    private static int paletteIndex(long[] packed, int paletteSize, int blockIndex) {
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        int valuesPerLong = 64 / bits;
        int longIndex = blockIndex / valuesPerLong;
        int bitOffset = (blockIndex % valuesPerLong) * bits;
        long mask = (1L << bits) - 1;
        return (int) ((packed[longIndex] >>> bitOffset) & mask);
    }
}
