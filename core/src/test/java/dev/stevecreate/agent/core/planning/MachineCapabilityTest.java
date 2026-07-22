package dev.stevecreate.agent.core.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.verification.VerificationEvidenceKind;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MachineCapabilityTest {
    @Test
    void describesAnAdapterOwnedCapabilityAndMatchesACompatibleRecipe() {
        Set<ResourceId> recipeTypes = new LinkedHashSet<>(Set.of(id("create:pressing")));
        Set<GenericResourceType> inputs = new LinkedHashSet<>(Set.of(GenericResourceType.ITEM));
        Set<ResourceId> actions = new LinkedHashSet<>(Set.of(
                id("industrial:feed_item"), id("industrial:process")));

        MachineCapability capability = new MachineCapability(
                id("industrial:pressing"),
                id("steve_industrial:create_6_0_6"),
                recipeTypes,
                inputs,
                Set.of(GenericResourceType.ITEM),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                actions,
                Set.of(
                        VerificationEvidenceKind.INPUT_CONSUMED,
                        VerificationEvidenceKind.PROCESS_COMPLETED,
                        VerificationEvidenceKind.OUTPUT_PRODUCED),
                Set.of(id("industrial:no_power"), id("industrial:overstressed")),
                new CapabilityVersionLimits(
                        Optional.of("6.0.0"),
                        Optional.of("7.0.0"),
                        Set.of("create=6.0.6-150")));

        recipeTypes.clear();
        inputs.clear();
        actions.clear();

        assertThat(capability.supportedRecipeTypes()).containsExactly(id("create:pressing"));
        assertThat(capability.inputPortTypes()).containsExactly(GenericResourceType.ITEM);
        assertThat(capability.availableActions()).containsExactly(
                id("industrial:feed_item"), id("industrial:process"));
        assertThat(capability.collectibleEvidence()).containsExactly(
                VerificationEvidenceKind.INPUT_CONSUMED,
                VerificationEvidenceKind.PROCESS_COMPLETED,
                VerificationEvidenceKind.OUTPUT_PRODUCED);
        assertThat(capability.isCompatibleWith(pressingRecipe())).isTrue();
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> capability.supportedDiagnostics().clear());
    }

    @Test
    void rejectsIncompleteDeclarationsAndIncompatibleRecipeRequirements() {
        assertThatIllegalArgumentException().isThrownBy(() -> capability(
                Set.of(), Set.of(GenericResourceType.ITEM), Set.of(GenericResourceType.ITEM),
                Set.of(), Set.of(id("test:action")), Set.of(VerificationEvidenceKind.OUTPUT_PRODUCED)));
        assertThatIllegalArgumentException().isThrownBy(() -> capability(
                Set.of(id("create:pressing")), Set.of(), Set.of(GenericResourceType.ITEM),
                Set.of(), Set.of(id("test:action")), Set.of(VerificationEvidenceKind.OUTPUT_PRODUCED)));
        assertThatIllegalArgumentException().isThrownBy(() -> capability(
                Set.of(id("create:pressing")), Set.of(GenericResourceType.ITEM),
                Set.of(GenericResourceType.ITEM),
                Set.of(
                        new CapabilityResourceRequirement(GenericResourceType.ROTATIONAL_POWER, 1, true),
                        new CapabilityResourceRequirement(GenericResourceType.ROTATIONAL_POWER, 2, false)),
                Set.of(id("test:action")), Set.of(VerificationEvidenceKind.OUTPUT_PRODUCED)));
        assertThatIllegalArgumentException().isThrownBy(() -> new CapabilityVersionLimits(
                Optional.of("6.0.0"), Optional.of("6.0.0"), Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new CapabilityVersionLimits(
                Optional.empty(), Optional.empty(), Set.of()));

        MachineCapability wrongOutput = capability(
                Set.of(id("create:pressing")),
                Set.of(GenericResourceType.ITEM),
                Set.of(GenericResourceType.FLUID),
                Set.of(new CapabilityResourceRequirement(
                        GenericResourceType.ROTATIONAL_POWER, 1, true)),
                Set.of(id("test:action")),
                Set.of(VerificationEvidenceKind.OUTPUT_PRODUCED));
        assertThat(wrongOutput.isCompatibleWith(pressingRecipe())).isFalse();
    }

    private static MachineCapability capability(
            Set<ResourceId> recipeTypes,
            Set<GenericResourceType> inputTypes,
            Set<GenericResourceType> outputTypes,
            Set<CapabilityResourceRequirement> requirements,
            Set<ResourceId> actions,
            Set<VerificationEvidenceKind> evidence) {
        return new MachineCapability(
                id("industrial:pressing"), id("test:adapter"), recipeTypes,
                inputTypes, outputTypes, requirements, actions, evidence, Set.of(),
                new CapabilityVersionLimits(Optional.of("1"), Optional.empty(), Set.of()));
    }

    static CatalogRecipe pressingRecipe() {
        return new CatalogRecipe(
                id("create:pressing/iron_ingot"),
                id("create:pressing"),
                List.of(item("minecraft:iron_ingot")),
                List.of(item("create:iron_sheet")),
                List.of(),
                Set.of(id("industrial:pressing")),
                Set.of(GenericResourceType.ITEM, GenericResourceType.ROTATIONAL_POWER),
                OptionalLong.of(240),
                new RecipeSource(
                        id("steve_industrial:create_6_0_6"),
                        "create",
                        "create=6.0.6-150",
                        true));
    }

    private static ProcessResource item(String value) {
        return new ProcessResource(id(value), GenericResourceType.ITEM, 1);
    }

    static ResourceId id(String value) {
        return ResourceId.parse(value);
    }
}
