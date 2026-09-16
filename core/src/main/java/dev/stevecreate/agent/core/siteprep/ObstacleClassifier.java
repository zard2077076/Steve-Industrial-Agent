package dev.stevecreate.agent.core.siteprep;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Fail-closed deterministic SP-04 classifier. UNKNOWN never becomes clearable. */
public final class ObstacleClassifier {
    private static final Set<String> SAFE_NATURAL = Set.of(
            "minecraft:grass", "minecraft:short_grass", "minecraft:tall_grass",
            "minecraft:fern", "minecraft:large_fern", "minecraft:dandelion",
            "minecraft:poppy", "minecraft:snow", "minecraft:dirt", "minecraft:grass_block",
            "minecraft:stone", "minecraft:oak_leaves", "minecraft:birch_leaves",
            "minecraft:spruce_leaves", "minecraft:oak_log", "minecraft:birch_log",
            "minecraft:spruce_log");
    private static final Set<String> ALWAYS_PROTECTED = Set.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
            "minecraft:shulker_box", "minecraft:bed", "minecraft:command_block",
            "minecraft:repeating_command_block", "minecraft:chain_command_block");
    private static final Set<String> CONFIRM = Set.of(
            "minecraft:torch", "minecraft:wall_torch", "minecraft:glass",
            "minecraft:glass_pane", "minecraft:cobblestone", "minecraft:planks");

    public ObstacleFinding classify(ObstacleObservation observation) {
        Objects.requireNonNull(observation, "observation");
        String id = observation.blockId().toString().toLowerCase(Locale.ROOT);
        ObstacleClassification classification;
        String reason;
        int confidence;
        if (!observation.insideAuthorizedRegion()) {
            classification = ObstacleClassification.PROTECTED_NO_AUTOMATIC_REMOVAL;
            reason = "position is outside the exact authorized region";
            confidence = 100;
        } else if (observation.environmentalRisk() || observation.fluidRisk()
                || id.equals("minecraft:lava") || id.equals("minecraft:tnt")) {
            classification = ObstacleClassification.ENVIRONMENTAL_HAZARD;
            reason = "fluid or environmental hazard requires a separate plan";
            confidence = 100;
        } else if (!observation.protectionKnown() || observation.protectedByClaim()
                || observation.container() || observation.hasInventory()
                || observation.blockEntity() || observation.machine()
                || observation.runningMachine() || ALWAYS_PROTECTED.contains(id)
                || id.contains("sign") || id.contains("bed")) {
            classification = ObstacleClassification.PROTECTED_NO_AUTOMATIC_REMOVAL;
            reason = "container, block entity, machine, ownership, or protection is unsafe";
            confidence = 100;
        } else if (observation.naturalCandidate() && !observation.playerPlacedUnknown()
                && SAFE_NATURAL.contains(id)) {
            classification = ObstacleClassification.SAFE_NATURAL_CLEARABLE;
            reason = "exact allowlisted natural block with known protection state";
            confidence = 100;
        } else if (!observation.playerPlacedUnknown() && CONFIRM.contains(id)) {
            classification = ObstacleClassification.CONFIRM_EACH_OR_GROUP;
            reason = "ordinary block requires explicit item or group approval";
            confidence = 90;
        } else {
            classification = ObstacleClassification.UNKNOWN;
            reason = "unrecognized or player-placement-unknown block fails closed";
            confidence = 0;
        }
        String obstacleId = "obstacle:" + SitePreparationHashes.sha256(
                observation.position() + "\n" + observation.blockId() + "\n"
                        + observation.blockStateFingerprint());
        return new ObstacleFinding(obstacleId, observation.position(), observation.blockId(),
                observation.blockStateFingerprint(), observation.blockEntity(),
                observation.container(), observation.hasInventory(), observation.machine(),
                observation.naturalCandidate(), observation.hardness(),
                observation.toolRequirement(), observation.dropsExpectation(),
                observation.fluidRisk(), classification, confidence,
                observation.evidenceSource(), reason);
    }

    /**
     * Final player confirmation may upgrade an ordinary state-only block from UNKNOWN to a
     * group-approved grading obstacle. Data-bearing, protected and hazardous blocks remain
     * fail-closed and can never be upgraded here.
     */
    public ObstacleFinding classifyForExplicitGrading(ObstacleObservation observation) {
        ObstacleFinding ordinary = classify(observation);
        if (ordinary.classification() != ObstacleClassification.UNKNOWN) return ordinary;
        if (!observation.insideAuthorizedRegion() || !observation.protectionKnown()
                || observation.protectedByClaim() || observation.environmentalRisk()
                || observation.fluidRisk() || observation.container()
                || observation.hasInventory() || observation.blockEntity()
                || observation.machine() || observation.runningMachine()
                || observation.hardness() < 0.0D) {
            return ordinary;
        }
        return new ObstacleFinding(ordinary.obstacleId(), ordinary.position(), ordinary.blockId(),
                ordinary.blockStateFingerprint(), false, false, false, false,
                ordinary.naturalCandidate(), ordinary.hardness(), ordinary.toolRequirement(),
                ordinary.dropsExpectation(), false,
                ObstacleClassification.CONFIRM_EACH_OR_GROUP, 100,
                ordinary.evidenceSource(),
                "ordinary state-only block explicitly group-approved for exact grading");
    }
}
