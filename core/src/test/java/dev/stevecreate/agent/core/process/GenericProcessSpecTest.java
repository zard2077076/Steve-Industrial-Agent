package dev.stevecreate.agent.core.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.BlockPos3i;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.plan.BeltPressPlan;
import dev.stevecreate.agent.core.plan.PressingProcessSpec;
import dev.stevecreate.agent.core.plan.WaterWheelMillstonePlan;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GenericProcessSpecTest {
    @Test
    void defensivelyCopiesACompleteLoaderNeutralProcessDescription() {
        List<ProcessResource> inputs = new ArrayList<>(List.of(resource(
                "minecraft:iron_ore", GenericResourceType.ITEM, 1)));
        List<ProcessResource> outputs = new ArrayList<>(List.of(resource(
                "minecraft:iron_ingot", GenericResourceType.ITEM, 1)));
        List<ProcessResource> byproducts = new ArrayList<>(List.of(resource(
                "test:slag", GenericResourceType.ITEM, 1)));
        Set<ResourceId> capabilities = new LinkedHashSet<>(Set.of(id("test:smelting")));
        Set<ResourceId> evidence = new LinkedHashSet<>(Set.of(id("test:output_stored")));
        Map<ResourceId, String> extensions = new LinkedHashMap<>(Map.of(
                id("test:temperature"), "1200"));

        GenericProcessSpec spec = new GenericProcessSpec(
                id("test:smelting/iron"),
                id("test:smelting"),
                inputs,
                outputs,
                byproducts,
                capabilities,
                evidence,
                600,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                extensions);

        inputs.clear();
        outputs.clear();
        byproducts.clear();
        capabilities.clear();
        evidence.clear();
        extensions.clear();

        assertThat(spec.inputs()).hasSize(1);
        assertThat(spec.outputs()).hasSize(1);
        assertThat(spec.optionalByproducts()).hasSize(1);
        assertThat(spec.requiredMachineCapabilities()).containsExactly(id("test:smelting"));
        assertThat(spec.requiredCompletionEvidence()).containsExactly(id("test:output_stored"));
        assertThat(spec.extensionData()).containsEntry(id("test:temperature"), "1200");
        assertThatThrownBy(() -> spec.inputs().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.extensionData().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingResourcesRequirementsAndUnboundedWaits() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> spec(List.of(), outputs(), capabilities(), evidence(), 20))
                .withMessage("inputs count must be between 1 and 32");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> spec(inputs(), List.of(), capabilities(), evidence(), 20))
                .withMessage("outputs count must be between 1 and 32");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> spec(inputs(), outputs(), Set.of(), evidence(), 20))
                .withMessage("requiredMachineCapabilities count must be between 1 and 64");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> spec(inputs(), outputs(), capabilities(), Set.of(), 20))
                .withMessage("requiredCompletionEvidence count must be between 1 and 64");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> spec(inputs(), outputs(), capabilities(), evidence(), 0))
                .withMessage("maximumWaitTicks must be between 1 and 2400");
    }

    @Test
    void rejectsInvalidQuantitiesDuplicatesAndRequiredOutputByproductOverlap() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> resource("test:item", GenericResourceType.ITEM, 0))
                .withMessage("amount must be between 1 and 1000000000");

        List<ProcessResource> duplicateInputs = List.of(
                resource("test:item", GenericResourceType.ITEM, 1),
                resource("test:item", GenericResourceType.ITEM, 2));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> spec(
                        duplicateInputs, outputs(), capabilities(), evidence(), 20))
                .withMessage("inputs contains duplicate resource: test:item");

        ProcessResource output = resource("test:product", GenericResourceType.ITEM, 1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GenericProcessSpec(
                        id("test:recipe"),
                        id("test:type"),
                        inputs(),
                        List.of(output),
                        List.of(new ProcessResource(output.resourceId(), output.resourceType(), 2)),
                        capabilities(),
                        evidence(),
                        20,
                        InputConsumptionRequirement.EXACT_DECLARED,
                        OutputVerificationRequirement.AT_LEAST_DECLARED,
                        Map.of()))
                .withMessage("optionalByproducts overlaps required output: test:product");
    }

    @Test
    void boundsResourceAndExtensionCollectionSizes() {
        List<ProcessResource> tooManyInputs = new ArrayList<>();
        for (int index = 0; index <= GenericProcessSpec.MAX_INPUTS; index++) {
            tooManyInputs.add(resource("test:input_" + index, GenericResourceType.ITEM, 1));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> spec(
                        tooManyInputs, outputs(), capabilities(), evidence(), 20))
                .withMessage("inputs count must be between 1 and 32");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GenericProcessSpec(
                        id("test:recipe"),
                        id("test:type"),
                        inputs(),
                        outputs(),
                        List.of(),
                        capabilities(),
                        evidence(),
                        20,
                        InputConsumptionRequirement.EXACT_DECLARED,
                        OutputVerificationRequirement.AT_LEAST_DECLARED,
                        Map.of(id("test:extension"), " ")))
                .withMessageContaining("must contain 1 to 256 characters");
    }

    @Test
    void mapsC03AndC04CommonFieldsWithoutErasingTheirEvidenceDifferences() {
        var milling = WaterWheelMillstonePlan.at(new BlockPos3i(0, 0, 0)).process();
        var pressing = BeltPressPlan.at(new BlockPos3i(0, 0, 0)).process();

        assertThat(milling.genericSpec().recipeId()).isEqualTo(milling.recipeId());
        assertThat(milling.genericSpec().inputs().get(0).resourceId()).isEqualTo(milling.inputItem());
        assertThat(milling.genericSpec().outputs().get(0).resourceId())
                .isEqualTo(milling.expectedOutputItem());
        assertThat(milling.genericSpec().maximumWaitTicks()).isEqualTo(milling.processingTimeoutTicks());
        assertThat(milling.genericSpec().extensionData())
                .containsEntry(id("create:processing_path"), "millstone_inventory");
        assertThat(milling.genericSpec().requiredCompletionEvidence())
                .contains(id("create:evidence/millstone_inventory_output"))
                .doesNotContain(
                        id("create:evidence/belt_input_observed"),
                        id("create:evidence/press_cycle_observed"),
                        id("create:evidence/chest_output_observed"));

        assertThat(pressing.genericSpec().recipeId()).isEqualTo(pressing.recipeId());
        assertThat(pressing.genericSpec().inputs().get(0).resourceId()).isEqualTo(pressing.inputItem());
        assertThat(pressing.genericSpec().outputs().get(0).resourceId())
                .isEqualTo(pressing.expectedOutputItem());
        assertThat(pressing.genericSpec().maximumWaitTicks()).isEqualTo(pressing.processingTimeoutTicks());
        assertThat(pressing.genericSpec().extensionData())
                .containsEntry(id("create:processing_path"), "belt_press")
                .containsEntry(id("create:output_storage"), "chest");
        assertThat(pressing.genericSpec().requiredCompletionEvidence()).contains(
                id("create:evidence/belt_input_observed"),
                id("create:evidence/press_cycle_observed"),
                id("create:evidence/chest_output_observed"));
    }

    @Test
    void preservesThePressingOnlyDistinctInputOutputGuard() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PressingProcessSpec(
                        id("create:pressing/test"),
                        id("create:pressing"),
                        id("test:same"),
                        1,
                        id("test:same"),
                        1,
                        20,
                        20))
                .withMessage("Pressing input and output must be distinct");
    }

    private static GenericProcessSpec spec(
            List<ProcessResource> inputs,
            List<ProcessResource> outputs,
            Set<ResourceId> capabilities,
            Set<ResourceId> evidence,
            int maximumWaitTicks) {
        return new GenericProcessSpec(
                id("test:recipe"),
                id("test:type"),
                inputs,
                outputs,
                List.of(),
                capabilities,
                evidence,
                maximumWaitTicks,
                InputConsumptionRequirement.EXACT_DECLARED,
                OutputVerificationRequirement.AT_LEAST_DECLARED,
                Map.of());
    }

    private static List<ProcessResource> inputs() {
        return List.of(resource("test:input", GenericResourceType.ITEM, 1));
    }

    private static List<ProcessResource> outputs() {
        return List.of(resource("test:output", GenericResourceType.ITEM, 1));
    }

    private static Set<ResourceId> capabilities() {
        return Set.of(id("test:capability"));
    }

    private static Set<ResourceId> evidence() {
        return Set.of(id("test:evidence"));
    }

    private static ProcessResource resource(
            String id,
            GenericResourceType resourceType,
            long amount) {
        return new ProcessResource(id(id), resourceType, amount);
    }

    private static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
