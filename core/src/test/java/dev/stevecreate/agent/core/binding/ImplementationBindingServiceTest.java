package dev.stevecreate.agent.core.binding;

import static dev.stevecreate.agent.core.binding.BindingTestFixtures.ADAPTER;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.FINGERPRINT;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.catalog;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.constraints;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.descriptor;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.id;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.multiPlan;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.singlePlan;
import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.LogicalNodeKind;
import dev.stevecreate.agent.core.planning.VerifiedLogicalPlan;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImplementationBindingServiceTest {
    private final ImplementationBindingService service = new ImplementationBindingService();

    @Test
    void bindsSingleStepAndPreservesLogicalRecipeIngredientAndQuantityIdentity() {
        VerifiedLogicalPlan logical = singlePlan();
        BindingResult result = service.bind(logical, catalog(descriptor(
                "fixture:mill", "industrial:milling", "fixture:milling", 0)), constraints());

        VerifiedImplementationBoundPlan plan = success(result);
        BoundMachineNode node = plan.graph().boundProcessNodes().values().iterator().next();
        assertThat(plan.evidence()).containsOnlyKeys(BindingVerificationCheck.values());
        assertThat(plan.graph().logicalPlan()).isSameAs(logical);
        assertThat(node.implementationId()).isEqualTo(id("fixture:mill"));
        assertThat(node.recipeId()).isEqualTo(id("fixture:milling"));
        assertThat(node.recipeInputs()).singleElement().satisfies(input -> {
            assertThat(input.ingredientIdentity()).isEqualTo("exact:fixture:ore@1");
            assertThat(input.selectedResource()).isEqualTo(id("fixture:ore"));
        });
        assertThat(node.quantityConversion().executions()).isEqualTo(2);
        assertThat(node.logicalToImplementationPorts()).hasSize(2);
    }

    @Test
    void bindsEveryNodeOfAMultiStepGraphWithoutReplacingAnyLogicalStructure() {
        VerifiedLogicalPlan logical = multiPlan();
        BindingResult result = service.bind(logical, catalog(
                descriptor("fixture:crusher", "industrial:crushing", "fixture:crushing", 0),
                descriptor("fixture:smelter", "industrial:smelting", "fixture:smelting", 0)),
                constraints());

        VerifiedImplementationBoundPlan plan = success(result);
        long processNodes = logical.logicalGraph().nodes().values().stream()
                .filter(value -> value.kind() == LogicalNodeKind.PROCESS).count();
        assertThat(plan.graph().boundProcessNodes()).hasSize(Math.toIntExact(processNodes));
        assertThat(plan.graph().logicalPlan().logicalGraph().nodes())
                .isSameAs(logical.logicalGraph().nodes());
        assertThat(plan.graph().logicalPlan().logicalGraph().ports())
                .isSameAs(logical.logicalGraph().ports());
        assertThat(plan.graph().logicalPlan().logicalGraph().edges())
                .isSameAs(logical.logicalGraph().edges());
    }

    @Test
    void selectsByExplicitScoreThenCanonicalIdAndRepeatsExactly() {
        var first = descriptor("fixture:z_mill", "industrial:milling", "fixture:milling", 0);
        var second = descriptor("fixture:a_mill", "industrial:milling", "fixture:milling", 0);
        var implementationCatalog = catalog(first, second);

        VerifiedImplementationBoundPlan one = success(service.bind(
                singlePlan(), implementationCatalog, constraints()));
        VerifiedImplementationBoundPlan two = success(service.bind(
                singlePlan(), implementationCatalog, constraints()));

        assertThat(one.graph().boundProcessNodes().values().iterator().next().implementationId())
                .isEqualTo(id("fixture:a_mill"));
        assertThat(one.graph().bindingTrace()).isEqualTo(two.graph().bindingTrace());
    }

    @Test
    void rejectsEqualScoresWhenCanonicalTieBreakIsExplicitlyForbidden() {
        BindingConstraints strict = new BindingConstraints(
                Set.of(ADAPTER), Optional.of(ADAPTER), Set.of(), Map.of("fixture", "1"),
                Set.of(), true, true, false, FINGERPRINT);
        BindingFailure failure = failure(service.bind(singlePlan(), catalog(
                descriptor("fixture:a_mill", "industrial:milling", "fixture:milling", 0),
                descriptor("fixture:b_mill", "industrial:milling", "fixture:milling", 0)), strict));

        assertThat(failure.code()).isEqualTo(
                BindingFailureCode.MULTIPLE_IMPLEMENTATIONS_AMBIGUOUS);
        assertThat(failure.candidateImplementationIds())
                .containsExactly(id("fixture:a_mill"), id("fixture:b_mill"));
    }

    @Test
    void reportsRuntimeModCapabilityRecipePortExecutionAndPolicyFailuresPrecisely() {
        var valid = descriptor("fixture:mill", "industrial:milling", "fixture:milling", 0);
        assertCode(service.bind(singlePlan(), catalog(valid), new BindingConstraints(
                Set.of(ADAPTER), Optional.of(ADAPTER), Set.of(), Map.of("fixture", "1"),
                Set.of(), true, true, true, "fixture=stale")),
                BindingFailureCode.IMPLEMENTATION_RUNTIME_MISMATCH);
        assertCode(service.bind(singlePlan(), catalog(valid), new BindingConstraints(
                Set.of(ADAPTER), Optional.of(ADAPTER), Set.of(), Map.of(), Set.of(),
                true, true, true, FINGERPRINT)),
                BindingFailureCode.IMPLEMENTATION_MOD_UNAVAILABLE);
        assertCode(service.bind(singlePlan(), catalog(descriptor(
                "fixture:press", "industrial:pressing", "fixture:milling", 0)), constraints()),
                BindingFailureCode.IMPLEMENTATION_CAPABILITY_MISMATCH);
        assertCode(service.bind(singlePlan(), catalog(descriptor(
                "fixture:mill", "industrial:milling", "fixture:other", 0)), constraints()),
                BindingFailureCode.IMPLEMENTATION_RECIPE_TYPE_MISMATCH);
        assertCode(service.bind(singlePlan(), catalog(BindingTestFixtures.descriptor(
                "fixture:fluid_mill", ADAPTER, "industrial:milling", "fixture:milling",
                "fixture", "1", FINGERPRINT, 0,
                ImplementationExecutionSupport.PHYSICALLY_VERIFIED, true,
                GenericResourceType.FLUID)), constraints()),
                BindingFailureCode.IMPLEMENTATION_PORT_CONTRACT_INVALID);
        assertCode(service.bind(singlePlan(), catalog(BindingTestFixtures.descriptor(
                "fixture:declared_mill", ADAPTER, "industrial:milling", "fixture:milling",
                "fixture", "1", FINGERPRINT, 0,
                ImplementationExecutionSupport.DESCRIBED_ONLY, false,
                GenericResourceType.ITEM)), constraints()),
                BindingFailureCode.IMPLEMENTATION_EXECUTION_UNVERIFIED);
        BindingConstraints forbidden = new BindingConstraints(
                Set.of(ADAPTER), Optional.of(ADAPTER), Set.of(id("fixture:mill")),
                Map.of("fixture", "1"), Set.of(), true, true, true, FINGERPRINT);
        assertCode(service.bind(singlePlan(), catalog(valid), forbidden),
                BindingFailureCode.IMPLEMENTATION_FORBIDDEN);
    }

    @Test
    void verifiedGateIsNotPubliclyConstructibleAndBindingTypesHaveNoPhysicalAuthority() {
        assertThat(VerifiedImplementationBoundPlan.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(new Class<?>[] {
                    ImplementationBoundMachineGraph.class,
                    BoundMachineNode.class,
                    VerifiedImplementationBoundPlan.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType)
                .map(Class::getName))
                .noneMatch(name -> name.contains("UnifiedMachineGraph")
                        || name.contains("MachineOrientation")
                        || name.contains("BlockPos")
                        || name.contains("GenericExecutionSession")
                        || name.startsWith("net.minecraft")
                        || name.startsWith("net.minecraftforge")
                        || name.startsWith("com.simibubi.create"));
    }

    private static VerifiedImplementationBoundPlan success(BindingResult result) {
        assertThat(result).isInstanceOf(BindingResult.Success.class);
        return ((BindingResult.Success) result).plan();
    }

    private static BindingFailure failure(BindingResult result) {
        assertThat(result).isInstanceOf(BindingResult.Failure.class);
        BindingFailure failure = ((BindingResult.Failure) result).failure();
        assertThat(failure.trace()).isNotEmpty();
        assertThat(failure.safeNextStep()).isNotBlank();
        return failure;
    }

    private static void assertCode(BindingResult result, BindingFailureCode code) {
        assertThat(failure(result).code()).isEqualTo(code);
    }
}
