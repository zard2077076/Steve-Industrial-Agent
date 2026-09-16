package dev.stevecreate.agent.forge1201.adapter.create.internal.v606;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeHeatRequirement;
import dev.stevecreate.agent.core.planning.RecipeHeatTier;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CreateV606VerifiedExecutionMetadataTest {
    private static final ResourceId SESSION = id("steve_industrial:test/session");
    private static final ResourceId STEP = id("steve_industrial:test/step");
    private static final ProcessResource COAL =
            new ProcessResource(id("minecraft:coal"), GenericResourceType.ITEM, 1);
    private static final ProcessResource WATER =
            new ProcessResource(id("minecraft:water"), GenericResourceType.FLUID, 250);

    @Test
    void fuelReservationMustExactlyMatchOneHeatedStep() {
        var requirement = heated(COAL);
        var mismatched = reservation(
                id("steve_industrial:test/fuel"),
                new ProcessResource(id("minecraft:charcoal"), GenericResourceType.ITEM, 1));

        assertThatThrownBy(() -> metadata(requirement, List.of(mismatched)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly match one HEATED ITEM step");
    }

    @Test
    void fuelReservationIdsMustBeUnique() {
        var requirement = heated(COAL);
        ResourceId reservationId = id("steve_industrial:test/fuel");

        assertThatThrownBy(() -> metadata(
                        requirement,
                        List.of(
                                reservation(reservationId, COAL),
                                reservation(reservationId, COAL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ids must be unique");
    }

    @Test
    void fluidInputsRemainBoundToTheVerifiedProcessStep() {
        var metadata = metadata(
                heated(COAL), Map.of(STEP, List.of(WATER)), List.of(reservation(
                        id("steve_industrial:test/fuel"), COAL)));

        assertThat(metadata.fluidInputs(STEP)).containsExactly(WATER);
        assertThat(metadata.fluidInputs(id("steve_industrial:test/other_step"))).isEmpty();
    }

    @Test
    void fluidMetadataRejectsItemResourcesAndUnknownSteps() {
        assertThatThrownBy(() -> metadata(
                        heated(COAL), Map.of(STEP, List.of(COAL)), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only nonempty FLUID inputs");

        assertThatThrownBy(() -> metadata(
                        heated(COAL),
                        Map.of(id("steve_industrial:test/unknown_step"), List.of(WATER)),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verified process step");
    }

    private static CreateV606VerifiedExecutionMetadata metadata(
            RecipeHeatRequirement requirement,
            List<CreateV606VerifiedExecutionMetadata.FuelReservationRequirement>
                    reservations) {
        return metadata(requirement, Map.of(), reservations);
    }

    private static CreateV606VerifiedExecutionMetadata metadata(
            RecipeHeatRequirement requirement,
            Map<ResourceId, List<ProcessResource>> fluidInputs,
            List<CreateV606VerifiedExecutionMetadata.FuelReservationRequirement>
                    reservations) {
        return new CreateV606VerifiedExecutionMetadata(
                SESSION,
                "runtime",
                Map.of(STEP, requirement),
                fluidInputs,
                reservations,
                List.of());
    }

    private static CreateV606VerifiedExecutionMetadata.FuelReservationRequirement
            reservation(ResourceId reservationId, ProcessResource fuel) {
        return new CreateV606VerifiedExecutionMetadata.FuelReservationRequirement(
                reservationId,
                SESSION,
                STEP,
                fuel,
                200,
                true,
                CreateV606VerifiedExecutionMetadata.ReloadBehavior
                        .RELEASE_AND_REVERIFY_BEFORE_RESOURCE_USE);
    }

    private static RecipeHeatRequirement heated(ProcessResource fuel) {
        return new RecipeHeatRequirement(
                id("steve_industrial:test/heat"),
                RecipeHeatTier.HEATED,
                id("create:mixing/brass_ingot"),
                id("create:mixing"),
                true,
                Set.of(id("steve_industrial:test/preflight")),
                Set.of(id("steve_industrial:test/evidence")),
                Set.of(id("steve_industrial:test/diagnostic")),
                Optional.of(fuel));
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
