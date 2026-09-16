package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeHeatRequirement;
import dev.stevecreate.agent.core.planning.RecipeHeatTier;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Adapter-side attestation that heat metadata still matches the verified planning chain. */
public record CreateV606VerifiedExecutionMetadata(
        ResourceId sessionId,
        String runtimeFingerprint,
        Map<ResourceId, RecipeHeatRequirement> heatByStep,
        Map<ResourceId, List<ProcessResource>> fluidInputsByStep,
        List<FuelReservationRequirement> fuelReservations,
        List<String> trace) {
    public CreateV606VerifiedExecutionMetadata(
            ResourceId sessionId,
            String runtimeFingerprint,
            Map<ResourceId, RecipeHeatRequirement> heatByStep,
            List<FuelReservationRequirement> fuelReservations,
            List<String> trace) {
        this(sessionId, runtimeFingerprint, heatByStep, Map.of(), fuelReservations, trace);
    }

    public CreateV606VerifiedExecutionMetadata {
        Objects.requireNonNull(sessionId, "sessionId");
        runtimeFingerprint = requireText(runtimeFingerprint, "runtimeFingerprint");
        Objects.requireNonNull(heatByStep, "heatByStep");
        List<Map.Entry<ResourceId, RecipeHeatRequirement>> ordered =
                new ArrayList<>(heatByStep.entrySet());
        ordered.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        Map<ResourceId, RecipeHeatRequirement> copied = new LinkedHashMap<>();
        for (Map.Entry<ResourceId, RecipeHeatRequirement> entry : ordered) {
            copied.put(
                    Objects.requireNonNull(entry.getKey(), "step id"),
                    Objects.requireNonNull(entry.getValue(), "heat requirement"));
        }
        heatByStep = Map.copyOf(copied);
        Objects.requireNonNull(fluidInputsByStep, "fluidInputsByStep");
        Map<ResourceId, List<ProcessResource>> copiedFluids = new LinkedHashMap<>();
        fluidInputsByStep.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceId::toString)))
                .forEach(entry -> {
                    ResourceId step = Objects.requireNonNull(entry.getKey(), "fluid step id");
                    List<ProcessResource> resources = List.copyOf(Objects.requireNonNull(
                            entry.getValue(), "fluid inputs for " + step));
                    if (resources.isEmpty() || resources.stream().anyMatch(value ->
                            value == null || value.resourceType() != GenericResourceType.FLUID)) {
                        throw new IllegalArgumentException(
                                "Fluid execution metadata must contain only nonempty FLUID inputs");
                    }
                    copiedFluids.put(step, resources);
                });
        if (!heatByStep.keySet().containsAll(copiedFluids.keySet())) {
            throw new IllegalArgumentException(
                    "Fluid execution metadata must belong to a verified process step");
        }
        fluidInputsByStep = Map.copyOf(copiedFluids);
        fuelReservations = List.copyOf(Objects.requireNonNull(
                fuelReservations, "fuelReservations"));
        trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
        if (!fuelReservations.stream().allMatch(value ->
                value.sessionId().equals(sessionId))) {
            throw new IllegalArgumentException(
                    "Every fuel reservation must belong to the verified root session");
        }
        Set<ResourceId> reservationIds = new HashSet<>();
        for (FuelReservationRequirement reservation : fuelReservations) {
            RecipeHeatRequirement requirement = heatByStep.get(reservation.stepId());
            if (reservation.fuel().resourceType() != GenericResourceType.ITEM
                    || requirement == null
                    || requirement.heatTier() != RecipeHeatTier.HEATED
                    || !requirement.fuelPerExecution().equals(
                            java.util.Optional.of(reservation.fuel()))) {
                throw new IllegalArgumentException(
                        "Every fuel reservation must exactly match one HEATED ITEM step");
            }
            if (!reservationIds.add(reservation.reservationId())) {
                throw new IllegalArgumentException(
                        "Fuel reservation ids must be unique");
            }
        }
    }

    public RecipeHeatTier heatTier(ResourceId stepId) {
        RecipeHeatRequirement requirement = heatByStep.get(
                Objects.requireNonNull(stepId, "stepId"));
        if (requirement == null) {
            throw new IllegalArgumentException(
                    "No verified heat requirement for step " + stepId);
        }
        return requirement.heatTier();
    }

    public List<ProcessResource> fluidInputs(ResourceId stepId) {
        Objects.requireNonNull(stepId, "stepId");
        return fluidInputsByStep.getOrDefault(stepId, List.of());
    }

    public enum ReloadBehavior {
        RELEASE_AND_REVERIFY_BEFORE_RESOURCE_USE,
        REFUSE_AFTER_RESOURCE_USE
    }

    public record FuelReservationRequirement(
            ResourceId reservationId,
            ResourceId sessionId,
            ResourceId stepId,
            ProcessResource fuel,
            long expiresAfterTicks,
            boolean releaseOnCancel,
            ReloadBehavior reloadBehavior) {
        public FuelReservationRequirement {
            Objects.requireNonNull(reservationId, "reservationId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(fuel, "fuel");
            if (expiresAfterTicks < 1 || !releaseOnCancel) {
                throw new IllegalArgumentException(
                        "Fuel reservations require a bounded expiry and cancel release");
            }
            Objects.requireNonNull(reloadBehavior, "reloadBehavior");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
