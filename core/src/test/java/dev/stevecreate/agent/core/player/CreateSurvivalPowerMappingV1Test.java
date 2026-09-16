package dev.stevecreate.agent.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CreateSurvivalPowerMappingV1Test {
    @Test
    void freezesOneReviewedEntryForEveryMaterialBearingCapability() {
        assertEquals(List.of("C-03", "C-04", "C-05", "C-06", "C-07", "C-08", "C-09", "C-10"),
                CreateSurvivalPowerMappingV1.ordered().stream()
                        .map(entry -> entry.capability().capabilityId()).toList());
        assertEquals(8, CreateSurvivalPowerMappingV1.ordered().size());
        assertTrue(CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C03)
                .ordinaryPlayerReady());
        assertTrue(CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C04)
                .ordinaryPlayerReady());
        assertTrue(CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C05)
                .ordinaryPlayerReady());
        assertTrue(CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C06)
                .ordinaryPlayerReady());
        assertTrue(CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C07)
                .ordinaryPlayerReady());
        assertTrue(CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C08)
                .ordinaryPlayerReady());
        assertTrue(CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C09)
                .ordinaryPlayerReady());
        assertTrue(CreateSurvivalPowerMappingV1.forCapability(CreateCapabilityContractV1.C10)
                .ordinaryPlayerReady());
        assertTrue(CreateSurvivalPowerMappingV1.ordered().stream()
                .filter(entry -> entry.capability() != CreateCapabilityContractV1.C03)
                .filter(entry -> entry.capability() != CreateCapabilityContractV1.C04)
                .filter(entry -> entry.capability() != CreateCapabilityContractV1.C05)
                .filter(entry -> entry.capability() != CreateCapabilityContractV1.C06)
                .filter(entry -> entry.capability() != CreateCapabilityContractV1.C07)
                .filter(entry -> entry.capability() != CreateCapabilityContractV1.C08)
                .filter(entry -> entry.capability() != CreateCapabilityContractV1.C09)
                .filter(entry -> entry.capability() != CreateCapabilityContractV1.C10)
                .noneMatch(CreateSurvivalPowerMappingV1.Entry::ordinaryPlayerReady));
    }

    @Test
    void preservesTheExactRolesThatNeedARealKineticProof() {
        assertEquals(List.of("belt_water_wheel", "belt_gearbox", "belt_drive_shaft",
                "press_water_wheel", "press_gearbox", "press_drive_shaft",
                "belt_start", "belt_pressing", "belt_end", "mechanical_press"),
                roles("create:pressing"));
        assertEquals(List.of("left_drive", "left_wheel", "right_drive", "right_wheel"),
                roles("create:crushing"));
        assertEquals(List.of(
                        "water_wheel", "bottom_gearbox", "vertical_shaft",
                        "top_gearbox", "fan_drive_shaft", "encased_fan"),
                roles("create:fan_processing"));
        assertEquals(List.of(
                        "water_wheel", "bottom_gearbox", "vertical_shaft",
                        "top_gearbox", "horizontal_shaft", "mechanical_saw"),
                roles("create:cutting"));
        assertEquals(List.of(
                        "water_wheel", "bottom_gearbox", "vertical_shaft",
                        "large_cogwheel_input", "small_cogwheel",
                        "large_cogwheel_output", "mechanical_mixer"),
                roles("create:mixing"));
        assertEquals(List.of(
                        "water_wheel", "bottom_gearbox", "vertical_shaft",
                        "top_gearbox", "horizontal_shaft", "mechanical_press"),
                roles("create:compacting"));
        assertEquals(List.of(
                        "water_wheel", "bottom_gearbox", "vertical_shaft",
                        "top_gearbox", "horizontal_shaft", "deployer"),
                roles("create:deploying"));
    }

    @Test
    void everyReviewedCapabilityUsesSurvivalPower() {
        assertEquals(Set.of(), CreateSurvivalPowerMappingV1.unresolvedPowerResources());
        assertEquals(ResourceId.parse("create:water_wheel"),
                CreateSurvivalPowerMappingV1.forProcessCapability(id("create:milling"))
                        .orElseThrow().powerResource());
        var splashing = CreateSurvivalPowerMappingV1.forProcessCapability(id("create:splashing"))
                .orElseThrow();
        assertTrue(splashing.ordinaryPlayerReady());
        assertEquals(null, splashing.blockerCode());
        assertEquals(ResourceId.parse("create:water_wheel"), splashing.powerResource());
        assertFalse(CreateSurvivalPowerMappingV1.forProcessCapability(id("minecraft:smelting"))
                .isPresent());
    }

    private static List<String> roles(String capability) {
        return CreateSurvivalPowerMappingV1.forProcessCapability(id(capability))
                .orElseThrow().roles();
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }
}
