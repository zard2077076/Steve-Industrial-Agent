package dev.stevecreate.agent.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CreateSurvivalPowerEvidenceV1Test {
    private static final ResourceId WATER_WHEEL = id("create:water_wheel");
    private static final ResourceId CREATIVE_MOTOR = id("create:creative_motor");

    @Test
    void completeReviewedEvidenceMakesC03ThroughC05PlayerEligible() {
        CreateSurvivalPowerEvidenceV1.Accepted accepted =
                assertInstanceOf(CreateSurvivalPowerEvidenceV1.Accepted.class,
                        CreateSurvivalPowerEvidenceV1.validate(candidate(
                                CreateCapabilityContractV1.C03, WATER_WHEEL,
                                Map.of("water_wheel", observation(WATER_WHEEL),
                                        "gearbox", observation(id("create:gearbox")),
                                        "vertical_shaft", observation(id("create:shaft")),
                                        "millstone", observation(id("create:millstone"))))));
        assertTrue(accepted.evidenceComplete());
        assertTrue(accepted.ordinaryPlayerEligible());

        CreateSurvivalPowerEvidenceV1.Accepted c04 =
                assertInstanceOf(CreateSurvivalPowerEvidenceV1.Accepted.class,
                        CreateSurvivalPowerEvidenceV1.validate(candidate(
                                CreateCapabilityContractV1.C04, WATER_WHEEL,
                                observations(CreateCapabilityContractV1.C04))));
        assertTrue(c04.evidenceComplete());
        assertTrue(c04.ordinaryPlayerEligible());

        CreateSurvivalPowerEvidenceV1.Accepted c05 =
                assertInstanceOf(CreateSurvivalPowerEvidenceV1.Accepted.class,
                        CreateSurvivalPowerEvidenceV1.validate(candidate(
                                CreateCapabilityContractV1.C05, WATER_WHEEL,
                                observations(CreateCapabilityContractV1.C05))));
        assertTrue(c05.evidenceComplete());
        assertTrue(c05.ordinaryPlayerEligible());
    }

    @Test
    void refusesWrongSourceAndIncompleteRoleEvidenceBeforeMaterialChecks() {
        var wrongSource = CreateSurvivalPowerEvidenceV1.validate(candidate(
                CreateCapabilityContractV1.C03, CREATIVE_MOTOR,
                Map.of("water_wheel", observation(CREATIVE_MOTOR),
                        "gearbox", observation(CREATIVE_MOTOR),
                        "vertical_shaft", observation(CREATIVE_MOTOR),
                        "millstone", observation(CREATIVE_MOTOR))));
        assertRefused(wrongSource, "POWER_SOURCE_MISMATCH");

        var missingRole = CreateSurvivalPowerEvidenceV1.validate(candidate(
                CreateCapabilityContractV1.C03, WATER_WHEEL,
                Map.of("water_wheel", observation(WATER_WHEEL))));
        assertRefused(missingRole, "POWER_ROLE_EVIDENCE_MISMATCH");
    }

    @Test
    void refusesStoppedOverstressedAndUnbalancedEvidence() {
        Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> stopped =
                new LinkedHashMap<>();
        stopped.put("water_wheel", new CreateSurvivalPowerEvidenceV1.RoleObservation(
                WATER_WHEEL, "network-1", 20, 0, true, 16, 1, false));
        stopped.put("gearbox", observation(id("create:gearbox")));
        stopped.put("vertical_shaft", observation(id("create:shaft")));
        stopped.put("millstone", observation(id("create:millstone")));
        assertRefused(CreateSurvivalPowerEvidenceV1.validate(candidate(
                CreateCapabilityContractV1.C03, WATER_WHEEL, stopped)),
                "POWER_LIVE_OBSERVATION_REQUIRED");

        Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> overloaded =
                new LinkedHashMap<>();
        for (String role : CreateSurvivalPowerMappingV1.forCapability(
                CreateCapabilityContractV1.C03).roles()) {
            overloaded.put(role, new CreateSurvivalPowerEvidenceV1.RoleObservation(
                    CreateSurvivalPowerMappingV1.forCapability(
                            CreateCapabilityContractV1.C03).roleBlockIds().get(role),
                    "network-1", 20, 16, true, 1, 2, true));
        }
        assertRefused(CreateSurvivalPowerEvidenceV1.validate(candidate(
                CreateCapabilityContractV1.C03, WATER_WHEEL, overloaded)),
                "POWER_OVERSTRESSED");

        var unbalanced = candidate(CreateCapabilityContractV1.C03, WATER_WHEEL,
                Map.of("water_wheel", observation(WATER_WHEEL),
                        "gearbox", observation(id("create:gearbox")),
                        "vertical_shaft", observation(id("create:shaft")),
                        "millstone", observation(id("create:millstone"))));
        unbalanced = new CreateSurvivalPowerEvidenceV1.Candidate(
                unbalanced.mapping(), unbalanced.sourceResource(), unbalanced.topologyFingerprint(),
                unbalanced.roleObservations(), unbalanced.materialPlanHash(), true, false, true, true);
        assertRefused(CreateSurvivalPowerEvidenceV1.validate(unbalanced),
                "POWER_MATERIAL_LEDGER_UNBALANCED");
    }

    @Test
    void requiresMaterialBindingRollbackAndBaselineEvidence() {
        var base = candidate(CreateCapabilityContractV1.C03, WATER_WHEEL,
                Map.of("water_wheel", observation(WATER_WHEEL),
                        "gearbox", observation(id("create:gearbox")),
                        "vertical_shaft", observation(id("create:shaft")),
                        "millstone", observation(id("create:millstone"))));
        assertRefused(CreateSurvivalPowerEvidenceV1.validate(
                with(base, false, true, true, true)), "POWER_MATERIAL_PLAN_REQUIRED");
        assertRefused(CreateSurvivalPowerEvidenceV1.validate(
                with(base, true, true, false, true)), "POWER_ROLLBACK_EVIDENCE_REQUIRED");
        assertRefused(CreateSurvivalPowerEvidenceV1.validate(
                with(base, true, true, true, false)), "POWER_BASELINE_NOT_RESTORED");
    }

    private static CreateSurvivalPowerEvidenceV1.Candidate candidate(
            CreateCapabilityContractV1 capability,
            ResourceId source,
            Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> observations) {
        return new CreateSurvivalPowerEvidenceV1.Candidate(
                CreateSurvivalPowerMappingV1.forCapability(capability), source,
                "topology:" + capability.capabilityId(), observations,
                "material-plan:" + capability.capabilityId(), true, true, true, true);
    }

    private static CreateSurvivalPowerEvidenceV1.Candidate with(
            CreateSurvivalPowerEvidenceV1.Candidate value,
            boolean materialBound, boolean balanced, boolean rollback, boolean baseline) {
        return new CreateSurvivalPowerEvidenceV1.Candidate(
                value.mapping(), value.sourceResource(), value.topologyFingerprint(),
                value.roleObservations(), value.materialPlanHash(), materialBound, balanced,
                rollback, baseline);
    }

    private static CreateSurvivalPowerEvidenceV1.RoleObservation observation(ResourceId block) {
        return new CreateSurvivalPowerEvidenceV1.RoleObservation(
                block, "network-1", 20, 16, true, 64, 16, false);
    }

    private static Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> observations(
            CreateCapabilityContractV1 capability) {
        Map<String, CreateSurvivalPowerEvidenceV1.RoleObservation> result = new LinkedHashMap<>();
        CreateSurvivalPowerMappingV1.Entry mapping =
                CreateSurvivalPowerMappingV1.forCapability(capability);
        mapping.roles().forEach(role -> result.put(role, observation(mapping.roleBlockIds().get(role))));
        return result;
    }

    private static void assertRefused(
            CreateSurvivalPowerEvidenceV1.Validation validation, String code) {
        CreateSurvivalPowerEvidenceV1.Refused refused =
                assertInstanceOf(CreateSurvivalPowerEvidenceV1.Refused.class, validation);
        assertEquals(code, refused.code());
        assertFalse(refused.evidenceComplete());
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
