package dev.stevecreate.agent.core.player;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Auditable survival-power boundary for the reviewed Create C-03 through C-10 scope.
 *
 * <p>This is a read-only capability ledger, not a power executor. An entry is
 * {@link Status#VERIFIED_SURVIVAL} only when a real, versioned physical path has
 * proved its source, topology and runtime observation. A review entry remains a
 * player-order blocker even when every non-power block in the same plan is payable.
 * Keeping this table separate from the material resolver prevents a new plan from
 * silently turning a creative-only source into a free construction action.</p>
 */
public final class CreateSurvivalPowerMappingV1 {
    private static final ResourceId WATER_WHEEL = id("create:water_wheel");

    private static final List<Entry> ORDERED = List.of(
            entry(CreateCapabilityContractV1.C03, WATER_WHEEL,
                    List.of("water_wheel", "gearbox", "vertical_shaft", "millstone"),
                    Map.of(
                            "water_wheel", id("create:water_wheel"),
                            "gearbox", id("create:gearbox"),
                            "vertical_shaft", id("create:shaft"),
                            "millstone", id("create:millstone")),
                    Status.VERIFIED_SURVIVAL, null, "c03-water-wheel-millstone"),
            entry(CreateCapabilityContractV1.C04, WATER_WHEEL,
                    List.of("belt_water_wheel", "belt_gearbox", "belt_drive_shaft",
                            "press_water_wheel", "press_gearbox", "press_drive_shaft",
                            "belt_start", "belt_pressing", "belt_end", "mechanical_press"),
                    Map.ofEntries(
                            Map.entry("belt_water_wheel", id("create:water_wheel")),
                            Map.entry("belt_gearbox", id("create:gearbox")),
                            Map.entry("belt_drive_shaft", id("create:shaft")),
                            Map.entry("press_water_wheel", id("create:water_wheel")),
                            Map.entry("press_gearbox", id("create:gearbox")),
                            Map.entry("press_drive_shaft", id("create:shaft")),
                            Map.entry("belt_start", id("create:belt")),
                            Map.entry("belt_pressing", id("create:belt")),
                            Map.entry("belt_end", id("create:belt")),
                            Map.entry("mechanical_press", id("create:mechanical_press"))),
                    Status.VERIFIED_SURVIVAL, null, "c04-dual-water-wheel-belt-press"),
            entry(CreateCapabilityContractV1.C05, WATER_WHEEL,
                    List.of("left_drive", "left_wheel", "right_drive", "right_wheel"),
                    Map.of(
                            "left_drive", id("create:water_wheel"),
                            "left_wheel", id("create:crushing_wheel"),
                            "right_drive", id("create:water_wheel"),
                            "right_wheel", id("create:crushing_wheel")),
                    Status.VERIFIED_SURVIVAL, null,
                    "c05-mirrored-water-wheel-crushing"),
            entry(CreateCapabilityContractV1.C06, WATER_WHEEL,
                    List.of(
                            "water_wheel", "bottom_gearbox", "vertical_shaft",
                            "top_gearbox", "fan_drive_shaft", "encased_fan"),
                    Map.of(
                            "water_wheel", id("create:water_wheel"),
                            "bottom_gearbox", id("create:gearbox"),
                            "vertical_shaft", id("create:shaft"),
                            "top_gearbox", id("create:gearbox"),
                            "fan_drive_shaft", id("create:shaft"),
                            "encased_fan", id("create:encased_fan")),
                    Status.VERIFIED_SURVIVAL, null, "c06-water-wheel-fan"),
            entry(CreateCapabilityContractV1.C07, WATER_WHEEL,
                    List.of(
                            "water_wheel", "bottom_gearbox", "vertical_shaft",
                            "top_gearbox", "horizontal_shaft", "mechanical_saw"),
                    Map.of(
                            "water_wheel", id("create:water_wheel"),
                            "bottom_gearbox", id("create:gearbox"),
                            "vertical_shaft", id("create:shaft"),
                            "top_gearbox", id("create:gearbox"),
                            "horizontal_shaft", id("create:shaft"),
                            "mechanical_saw", id("create:mechanical_saw")),
                    Status.VERIFIED_SURVIVAL, null, "c07-water-wheel-mechanical-saw"),
            entry(CreateCapabilityContractV1.C08, WATER_WHEEL,
                    List.of(
                            "water_wheel", "bottom_gearbox", "vertical_shaft",
                            "large_cogwheel_input", "small_cogwheel",
                            "large_cogwheel_output", "mechanical_mixer"),
                    Map.of(
                            "water_wheel", id("create:water_wheel"),
                            "bottom_gearbox", id("create:gearbox"),
                            "vertical_shaft", id("create:shaft"),
                            "large_cogwheel_input", id("create:large_cogwheel"),
                            "small_cogwheel", id("create:cogwheel"),
                            "large_cogwheel_output", id("create:large_cogwheel"),
                            "mechanical_mixer", id("create:mechanical_mixer")),
                    Status.VERIFIED_SURVIVAL, null,
                    "c08-water-wheel-geared-basin-mixer"),
            entry(CreateCapabilityContractV1.C09, WATER_WHEEL,
                    List.of("water_wheel", "bottom_gearbox", "vertical_shaft",
                            "top_gearbox", "horizontal_shaft", "mechanical_press"),
                    Map.of(
                            "water_wheel", id("create:water_wheel"),
                            "bottom_gearbox", id("create:gearbox"),
                            "vertical_shaft", id("create:shaft"),
                            "top_gearbox", id("create:gearbox"),
                            "horizontal_shaft", id("create:shaft"),
                            "mechanical_press", id("create:mechanical_press")),
                    Status.VERIFIED_SURVIVAL, null, "c09-water-wheel-basin-press"),
            entry(CreateCapabilityContractV1.C10, WATER_WHEEL,
                    List.of("water_wheel", "bottom_gearbox", "vertical_shaft",
                            "top_gearbox", "horizontal_shaft", "deployer"),
                    Map.of(
                            "water_wheel", id("create:water_wheel"),
                            "bottom_gearbox", id("create:gearbox"),
                            "vertical_shaft", id("create:shaft"),
                            "top_gearbox", id("create:gearbox"),
                            "horizontal_shaft", id("create:shaft"),
                            "deployer", id("create:deployer")),
                    Status.VERIFIED_SURVIVAL, null, "c10-water-wheel-deployer"));

    private CreateSurvivalPowerMappingV1() {}

    /** Returns C-03 through C-10 in their stable capability order. */
    public static List<Entry> ordered() { return ORDERED; }

    public static Entry forCapability(CreateCapabilityContractV1 capability) {
        Objects.requireNonNull(capability, "capability");
        return ORDERED.stream()
                .filter(entry -> entry.capability() == capability)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no survival-power mapping for " + capability.capabilityId()));
    }

    /** Resolve splashing/smoking/haunting/blasting through C-06's alias contract. */
    public static Optional<Entry> forProcessCapability(ResourceId capability) {
        Objects.requireNonNull(capability, "capability");
        return CreateCapabilityContractV1.forProcessCapability(capability)
                .filter(value -> value.materialBearing())
                .map(CreateSurvivalPowerMappingV1::forCapability);
    }

    /** Exact block IDs still needing a reviewed survival binding. */
    public static Set<ResourceId> unresolvedPowerResources() {
        TreeSet<ResourceId> values = new TreeSet<>(
                java.util.Comparator.comparing(ResourceId::toString));
        ORDERED.stream()
                .filter(entry -> entry.status() == Status.REVIEW_REQUIRED)
                .map(Entry::powerResource)
                .forEach(values::add);
        return Set.copyOf(values);
    }

    /** Build an unlisted candidate mapping for a physical survival-power probe. */
    public static Entry candidate(
            CreateCapabilityContractV1 capability,
            ResourceId powerResource,
            Map<String, ResourceId> roleBlockIds,
            String evidenceId) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(powerResource, "powerResource");
        Objects.requireNonNull(roleBlockIds, "roleBlockIds");
        return new Entry(
                capability,
                powerResource,
                List.copyOf(roleBlockIds.keySet()),
                roleBlockIds,
                Status.REVIEW_REQUIRED,
                capability.capabilityId().toUpperCase(java.util.Locale.ROOT)
                        + "_SURVIVAL_POWER_MAPPING_REQUIRED",
                evidenceId);
    }

    private static Entry entry(
            CreateCapabilityContractV1 capability,
            ResourceId powerResource,
            List<String> roles,
            Map<String, ResourceId> roleBlockIds,
            Status status,
            String blockerCode,
            String evidenceId) {
        return new Entry(capability, powerResource, roles, roleBlockIds, status, blockerCode, evidenceId);
    }

    private static ResourceId id(String value) { return ResourceId.parse(value); }

    public enum Status { VERIFIED_SURVIVAL, REVIEW_REQUIRED }

    public record Entry(
            CreateCapabilityContractV1 capability,
            ResourceId powerResource,
            List<String> roles,
            Map<String, ResourceId> roleBlockIds,
            Status status,
            String blockerCode,
            String evidenceId) {
        public Entry {
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(powerResource, "powerResource");
            Objects.requireNonNull(roles, "roles");
            Objects.requireNonNull(roleBlockIds, "roleBlockIds");
            if (roles.isEmpty() || roles.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("survival-power roles are empty or invalid");
            }
            roles = List.copyOf(roles);
            if (!roleBlockIds.keySet().equals(Set.copyOf(roles))) {
                throw new IllegalArgumentException("survival-power role block IDs do not match roles");
            }
            Map<String, ResourceId> suppliedRoleBlockIds = roleBlockIds;
            LinkedHashMap<String, ResourceId> blocks = new LinkedHashMap<>();
            roles.forEach(role -> blocks.put(role, Objects.requireNonNull(
                    suppliedRoleBlockIds.get(role), "role block ID")));
            roleBlockIds = Map.copyOf(blocks);
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(evidenceId, "evidenceId");
            if (evidenceId.isBlank()) throw new IllegalArgumentException("evidence ID is blank");
            if (status == Status.REVIEW_REQUIRED
                    && (blockerCode == null || blockerCode.isBlank())) {
                throw new IllegalArgumentException("review mapping requires a blocker code");
            }
            if (status == Status.VERIFIED_SURVIVAL && blockerCode != null) {
                throw new IllegalArgumentException("verified mapping cannot carry a blocker");
            }
        }

        public boolean ordinaryPlayerReady() {
            return status == Status.VERIFIED_SURVIVAL;
        }
    }
}
