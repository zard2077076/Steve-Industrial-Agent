package dev.stevecreate.agent.adapter.api.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.planning.RecipeIngredient;
import dev.stevecreate.agent.core.process.ProcessResource;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CreateCapabilitySemanticsContractTest {
    private static final String FINGERPRINT = "sha256:c05-c10-runtime";

    @Test
    void capabilityVocabularyCoversEveryRequestedRecipeTypeWithoutExecutorAuthority() {
        assertThat(CreateCapabilityId.values()).extracting(CreateCapabilityId::phaseCode)
                .containsExactly(
                        "C-05",
                        "C-06-WASHING",
                        "C-06-SMOKING",
                        "C-06-HAUNTING",
                        "C-06-BLASTING",
                        "C-07",
                        "C-08",
                        "C-09",
                        "C-10");
        assertThat(CreateCapabilityId.values()).extracting(value -> value.recipeType().toString())
                .containsExactly(
                        "create:crushing",
                        "create:splashing",
                        "minecraft:smoking",
                        "create:haunting",
                        "minecraft:blasting",
                        "create:cutting",
                        "create:mixing",
                        "create:compacting",
                        "create:deploying");

        List<Class<?>> contractTypes = List.of(
                ProcessingEnvironmentRequirement.class,
                DirectionalFlowRequirement.class,
                HeatRequirement.class,
                AirflowRequirement.class,
                MediumRequirement.class,
                ToolRequirement.class,
                CatalystRequirement.class,
                BasinRequirement.class,
                HeldItemRequirement.class,
                MultiOutputSemantics.class,
                ProbabilisticOutputSemantics.class,
                MinimumSpeedRequirement.class,
                RotationDirectionRequirement.class,
                RuntimeObservationRequirement.class);
        assertThat(contractTypes).allSatisfy(type -> {
            assertThat(type.getName()).doesNotContain("minecraft", "forge", "Executor", "World");
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .allSatisfy(parameter -> assertThat(parameter.getName())
                                .doesNotContain("net.minecraft", "net.minecraftforge", "Executor"));
            }
        });
    }

    @Test
    void completeCensusIsDeterministicBoundedAndCarriesNotPresentEvidence() {
        List<CapabilityRecipeSemantics> recipes = new ArrayList<>();
        Map<ResourceId, Long> typeCounts = new LinkedHashMap<>();
        for (CreateCapabilityId capability : CreateCapabilityId.values()) {
            typeCounts.put(capability.recipeType(), 1L);
            recipes.add(recipe(capability, CapabilityRecipeSupport.SUPPORTED_PHASE_I, List.of()));
        }
        RuntimeCapabilityCensusSnapshot snapshot = new RuntimeCapabilityCensusSnapshot(
                FINGERPRINT,
                "minecraft:overworld",
                4,
                2_609,
                typeCounts,
                recipes,
                true,
                false);

        assertThat(snapshot.recipes()).extracting(value -> value.recipeId().toString()).isSorted();
        assertThat(snapshot.summaries()).hasSize(CreateCapabilityId.values().length);
        assertThat(snapshot.summaries().values()).allSatisfy(summary -> {
            assertThat(summary.discovered()).isOne();
            assertThat(summary.supportedPhaseI()).isOne();
            assertThat(summary.notPresent()).isFalse();
            assertThat(summary.acceptanceCandidates()).hasSize(1);
        });
        assertThatThrownBy(() -> snapshot.recipes().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        Map<ResourceId, Long> absentCounts = new LinkedHashMap<>();
        for (CreateCapabilityId capability : CreateCapabilityId.values()) {
            absentCounts.put(capability.recipeType(), 0L);
        }
        RuntimeCapabilityCensusSnapshot absent = new RuntimeCapabilityCensusSnapshot(
                FINGERPRINT,
                "minecraft:overworld",
                0,
                1,
                absentCounts,
                List.of(),
                true,
                false);
        assertThat(absent.summaries().values()).allSatisfy(summary -> {
            assertThat(summary.notPresent()).isTrue();
            assertThat(summary.acceptanceCandidates()).isEmpty();
        });
    }

    @Test
    void phaseOneRestrictionsRemainTypedInsteadOfErasingRuntimeSemantics() {
        CapabilityRecipeLimitation limitation = new CapabilityRecipeLimitation(
                CapabilityRecipeLimitationCode.FLUID_INPUT_PHASE_I_UNSUPPORTED,
                "fluidInputs[0]",
                "Fluid identity is preserved by census but C-08 Phase I is item-only",
                true);
        CapabilityRecipeSemantics semantics = recipe(
                CreateCapabilityId.MIXING_PHASE_I,
                CapabilityRecipeSupport.SEMANTICS_ONLY,
                List.of(limitation));

        assertThat(semantics.limitations()).containsExactly(limitation);
        assertThat(semantics.support()).isEqualTo(CapabilityRecipeSupport.SEMANTICS_ONLY);
        assertThatThrownBy(() -> recipe(
                CreateCapabilityId.MIXING_PHASE_I,
                CapabilityRecipeSupport.SUPPORTED_PHASE_I,
                List.of(limitation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocking limitations");
    }

    @Test
    void outputProbabilityHeatAirflowBasinAndHeldItemContractsFailClosed() {
        ProcessResource output = item("minecraft:gravel", 1);
        CapabilityOutput primary = new CapabilityOutput(0, output, 1_000_000, true);
        CapabilityOutput byproduct = new CapabilityOutput(1, item("minecraft:flint", 1), 125_000, false);
        MultiOutputSemantics outputs = new MultiOutputSemantics(
                List.of(primary, byproduct), true, true);
        ProbabilisticOutputSemantics probability = new ProbabilisticOutputSemantics(
                List.of(1_000_000, 125_000), 1_000_000, true);
        assertThat(probability.hasProbabilisticOutput()).isTrue();
        assertThat(probability.primaryIsDeterministic()).isTrue();
        assertThat(outputs.outputs()).containsExactly(primary, byproduct);

        assertThatThrownBy(() -> new AirflowRequirement(
                true, MediumRequirement.NONE, 1, true, true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HeldItemRequirement(
                Optional.of(exact("minecraft:iron_ingot")), true, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("player inventory");
        assertThatThrownBy(() -> new MinimumSpeedRequirement(OptionalLong.empty(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeObservationRequirement(
                EnumSet.of(RuntimeObservationRequirement.Signal.LIVE_RECIPE), 20, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipe/input/output");
    }

    private static CapabilityRecipeSemantics recipe(
            CreateCapabilityId capability,
            CapabilityRecipeSupport support,
            List<CapabilityRecipeLimitation> limitations) {
        MediumRequirement medium = switch (capability) {
            case FAN_WASHING -> MediumRequirement.WATER;
            case FAN_SMOKING -> MediumRequirement.FIRE;
            case FAN_HAUNTING -> MediumRequirement.SOUL_FIRE;
            case FAN_BLASTING -> MediumRequirement.LAVA;
            default -> MediumRequirement.NONE;
        };
        boolean fan = medium != MediumRequirement.NONE;
        boolean basin = capability == CreateCapabilityId.MIXING_PHASE_I
                || capability == CreateCapabilityId.COMPACTING_PHASE_I;
        boolean deployer = capability == CreateCapabilityId.DEPLOYING_PHASE_I;
        RuntimeObservationRequirement observation = new RuntimeObservationRequirement(
                EnumSet.of(
                        RuntimeObservationRequirement.Signal.LIVE_RECIPE,
                        RuntimeObservationRequirement.Signal.KINETIC_SPEED,
                        RuntimeObservationRequirement.Signal.INPUT_CONSUMED,
                        RuntimeObservationRequirement.Signal.OUTPUT_OBSERVED),
                1_200,
                true);
        RecipeIngredient held = exact("minecraft:iron_ingot");
        ProcessingEnvironmentRequirement environment = new ProcessingEnvironmentRequirement(
                fan
                        ? new DirectionalFlowRequirement(
                                true,
                                RotationDirectionRequirement.ALONG_MACHINE_FACING,
                                true,
                                1)
                        : DirectionalFlowRequirement.none(),
                HeatRequirement.NONE,
                fan
                        ? new AirflowRequirement(true, medium, 1, true, true, true)
                        : AirflowRequirement.none(),
                medium,
                ToolRequirement.none(),
                CatalystRequirement.none(),
                basin
                        ? new BasinRequirement(true, 9, 0, false, true)
                        : BasinRequirement.none(),
                deployer
                        ? new HeldItemRequirement(Optional.of(held), true, true, true)
                        : HeldItemRequirement.none(),
                new MinimumSpeedRequirement(OptionalLong.empty(), true),
                observation,
                true,
                true);
        MultiOutputSemantics outputs = new MultiOutputSemantics(
                List.of(new CapabilityOutput(
                        0,
                        item("minecraft:gravel", 1),
                        CapabilityOutput.PROBABILITY_DENOMINATOR,
                        true)),
                true,
                true);
        return new CapabilityRecipeSemantics(
                ResourceId.parse("test:" + capability.name().toLowerCase()),
                capability.recipeType(),
                capability,
                List.of(exact("minecraft:cobblestone")),
                List.of(),
                List.of(),
                outputs,
                new ProbabilisticOutputSemantics(
                        List.of(CapabilityOutput.PROBABILITY_DENOMINATOR),
                        CapabilityOutput.PROBABILITY_DENOMINATOR,
                        true),
                environment,
                OptionalLong.of(100),
                support,
                limitations,
                FINGERPRINT);
    }

    private static RecipeIngredient exact(String id) {
        return new RecipeIngredient.ExactResource(ResourceId.parse(id), 1);
    }

    private static ProcessResource item(String id, long amount) {
        return new ProcessResource(ResourceId.parse(id), GenericResourceType.ITEM, amount);
    }
}
