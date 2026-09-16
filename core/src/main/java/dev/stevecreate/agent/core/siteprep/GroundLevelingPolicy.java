package dev.stevecreate.agent.core.siteprep;

public record GroundLevelingPolicy(
        int maximumRaisedBlocks,
        int maximumHoleDepth,
        int maximumFillBlocks,
        int maximumTotalMutations) {
    public static GroundLevelingPolicy conservative() {
        return new GroundLevelingPolicy(2, 2, 64, 128);
    }

    public static GroundLevelingPolicy boundedGrading(
            int maximumRaisedBlocks,
            int maximumHoleDepth,
            int maximumFillBlocks,
            int maximumTotalMutations) {
        return new GroundLevelingPolicy(maximumRaisedBlocks, maximumHoleDepth,
                maximumFillBlocks, maximumTotalMutations);
    }

    public GroundLevelingPolicy {
        if (maximumRaisedBlocks < 0
                || maximumRaisedBlocks > TerrainGradingSpecification.MAX_CLEARANCE_HEIGHT
                || maximumHoleDepth < 0
                || maximumHoleDepth > TerrainGradingSpecification.MAX_FILL_DEPTH
                || maximumFillBlocks < 0
                || maximumFillBlocks > TerrainGradingSpecification.MAX_MUTATIONS
                || maximumTotalMutations < 0
                || maximumTotalMutations > TerrainGradingSpecification.MAX_MUTATIONS
                || maximumFillBlocks > maximumTotalMutations) {
            throw new IllegalArgumentException("leveling policy exceeds bounded Phase IV-S limits");
        }
    }
}
