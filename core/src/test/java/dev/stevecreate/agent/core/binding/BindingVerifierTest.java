package dev.stevecreate.agent.core.binding;

import static dev.stevecreate.agent.core.binding.BindingTestFixtures.catalog;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.constraints;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.descriptor;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.id;
import static dev.stevecreate.agent.core.binding.BindingTestFixtures.singlePlan;
import static org.assertj.core.api.Assertions.assertThat;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.CandidateQuantityConversion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BindingVerifierTest {
    private final BindingVerifier verifier = new BindingVerifier();

    @Test
    void rejectsMissingCoverageAndSelectionsAbsentFromTheCatalog() {
        MachineImplementationDescriptor mill = descriptor(
                "fixture:mill", "industrial:milling", "fixture:milling", 0);
        VerifiedImplementationBoundPlan verified = success(new ImplementationBindingService()
                .bind(singlePlan(), catalog(mill), constraints()));
        ImplementationBoundMachineGraph original = verified.graph();
        ImplementationBoundMachineGraph missing = graph(original, List.of());

        assertCode(verifier.verify(missing, catalog(mill), constraints()),
                BindingFailureCode.LOGICAL_NODE_UNBOUND);
        MachineImplementationDescriptor replacement = descriptor(
                "fixture:other", "industrial:milling", "fixture:milling", 0);
        assertCode(verifier.verify(original, catalog(replacement), constraints()),
                BindingFailureCode.IMPLEMENTATION_NOT_FOUND);
    }

    @Test
    void rejectsPortQuantityAndIngredientIdentityMutationsIndependently() {
        MachineImplementationDescriptor mill = descriptor(
                "fixture:mill", "industrial:milling", "fixture:milling", 0);
        VerifiedImplementationBoundPlan verified = success(new ImplementationBindingService()
                .bind(singlePlan(), catalog(mill), constraints()));
        ImplementationBoundMachineGraph original = verified.graph();
        BoundMachineNode node = original.boundProcessNodes().values().iterator().next();

        assertCode(verifier.verify(graph(original, List.of(copy(
                        node, Map.of(), node.quantityConversion(), node.recipeInputs()))),
                catalog(mill), constraints()),
                BindingFailureCode.IMPLEMENTATION_PORT_CONTRACT_INVALID);

        CandidateQuantityConversion quantity = new CandidateQuantityConversion(
                node.stepId(), node.recipeId(), node.quantityConversion().executions() + 1,
                node.quantityConversion().inputs(), node.quantityConversion().outputs(),
                node.quantityConversion().byproducts());
        assertCode(verifier.verify(graph(original, List.of(copy(
                        node, node.logicalToImplementationPorts(), quantity, node.recipeInputs()))),
                catalog(mill), constraints()), BindingFailureCode.BINDING_GRAPH_INVALID);

        BoundRecipeInput input = node.recipeInputs().get(0);
        BoundRecipeInput changed = new BoundRecipeInput(
                input.recipeId(), input.inputIndex(), input.ingredientKind(),
                "exact:fixture:invented@1", input.selectedResource(), input.amount(),
                input.selectionReason());
        assertCode(verifier.verify(graph(original, List.of(copy(
                        node, node.logicalToImplementationPorts(), node.quantityConversion(),
                        List.of(changed)))), catalog(mill), constraints()),
                BindingFailureCode.BINDING_GRAPH_INVALID);
    }

    @Test
    void everySuccessfulVerificationProducesTheCompleteChecklist() {
        MachineImplementationDescriptor mill = descriptor(
                "fixture:mill", "industrial:milling", "fixture:milling", 0);
        VerifiedImplementationBoundPlan plan = success(new ImplementationBindingService()
                .bind(singlePlan(), catalog(mill), constraints()));

        BindingVerificationResult repeated = verifier.verify(
                plan.graph(), catalog(mill), constraints());
        assertThat(repeated).isInstanceOf(BindingVerificationResult.Success.class);
        assertThat(((BindingVerificationResult.Success) repeated).plan().evidence())
                .containsOnlyKeys(BindingVerificationCheck.values());
    }

    private static ImplementationBoundMachineGraph graph(
            ImplementationBoundMachineGraph original,
            List<BoundMachineNode> nodes) {
        return new ImplementationBoundMachineGraph(
                original.id(), original.logicalPlan(), nodes, original.runtimeFingerprint(),
                original.catalogReloadGeneration(), original.bindingTrace());
    }

    private static BoundMachineNode copy(
            BoundMachineNode original,
            Map<ResourceId, ResourceId> ports,
            CandidateQuantityConversion quantity,
            List<BoundRecipeInput> inputs) {
        return new BoundMachineNode(
                original.logicalNodeId(), original.stepId(), original.recipeId(),
                original.recipeType(), original.implementationId(), original.adapterId(),
                original.implementationFamily(), original.processingMode(), ports, inputs,
                quantity, original.selectionScore(), original.consideredImplementationIds(),
                original.selectionReasons(), original.limitations(), original.runtimeFingerprint());
    }

    private static VerifiedImplementationBoundPlan success(BindingResult result) {
        assertThat(result).isInstanceOf(BindingResult.Success.class);
        return ((BindingResult.Success) result).plan();
    }

    private static void assertCode(
            BindingVerificationResult result,
            BindingFailureCode code) {
        assertThat(result).isInstanceOf(BindingVerificationResult.Failure.class);
        assertThat(((BindingVerificationResult.Failure) result).failure().code())
                .isEqualTo(code);
    }
}
